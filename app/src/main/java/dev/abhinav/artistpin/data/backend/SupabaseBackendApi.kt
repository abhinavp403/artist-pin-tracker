package dev.abhinav.artistpin.data.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PostgREST implementation of [BackendApi].
 *
 * The session comes from the same [SupabaseClient] that `SupabaseAuthRepository` signs into, so
 * every call below is authorised as the signed-in user with no token handling here at all — which
 * is the whole argument for this transport, recorded on [BackendApi].
 *
 * There is no user-id filter anywhere in this file, and that is not an oversight. Row-Level
 * Security scopes every one of these queries server-side. A `user_id` filter in the client would
 * be a *second* place the ownership rule lives, free to drift from the policy that actually
 * enforces it — and it would read as though it were doing the protecting, which it would not be.
 */
class SupabaseBackendApi(
    private val client: SupabaseClient,
    private val ioDispatcher: CoroutineDispatcher,
) : BackendApi {

    // The full show in one round trip. PostgREST embeds through foreign keys, which is why this
    // reads from the `events` table rather than the event_summaries view — a view's joins are
    // opaque to the embedding syntax.
    private val eventDetailColumns = Columns.raw(
        "id, event_date, title, notes, rating, " +
            "venues(*, cities(*)), " +
            "event_artists(billing, artists(*)), " +
            "event_media(*)",
    )

    override suspend fun cityPins(): List<CityPinDto> = io {
        client.postgrest.from(CITY_PINS).select().decodeList()
    }

    override suspend fun venuePins(cityId: String?): List<VenuePinDto> = io {
        client.postgrest.from(VENUE_PINS).select {
            if (cityId != null) filter { eq("city_id", cityId) }
        }.decodeList()
    }

    override suspend fun events(
        cityId: String?,
        venueId: String?,
        artistId: String?,
    ): List<EventSummaryDto> = io {
        // artist_event_summaries is event_summaries plus the joining artist_id. Reaching for it
        // only when filtering by artist keeps the common queries off the extra join.
        val table = if (artistId != null) ARTIST_EVENT_SUMMARIES else EVENT_SUMMARIES
        client.postgrest.from(table).select {
            filter {
                artistId?.let { eq("artist_id", it) }
                cityId?.let { eq("city_id", it) }
                venueId?.let { eq("venue_id", it) }
            }
            order("event_date", Order.DESCENDING)
        }.decodeList()
    }

    override suspend fun event(eventId: String): EventDetailDto? = io {
        client.postgrest.from(EVENTS).select(eventDetailColumns) {
            filter { eq("id", eventId) }
        }.decodeSingleOrNull()
    }

    override suspend fun artistSummaries(): List<ArtistSummaryDto> = io {
        client.postgrest.from(ARTIST_SUMMARIES).select().decodeList()
    }

    override suspend fun artist(artistId: String): ArtistDto? = io {
        client.postgrest.from(ARTISTS).select {
            filter { eq("id", artistId) }
        }.decodeSingleOrNull()
    }

    override suspend fun artists(): List<ArtistDto> = io {
        client.postgrest.from(ARTISTS).select { order("name", Order.ASCENDING) }.decodeList()
    }

    override suspend fun artistsWithoutProfile(): List<ArtistDto> = io {
        client.postgrest.from(ARTISTS).select {
            // `is null`, not `= null` — SQL's null never equals anything, so an eq filter here
            // would silently return nothing and the backfill would quietly never run.
            filter { filter("genres", FilterOperator.IS, "null") }
        }.decodeList()
    }

    override suspend fun venues(): List<VenueDto> = io {
        client.postgrest.from(VENUES)
            .select(Columns.raw("*, cities(*)")) { order("name", Order.ASCENDING) }
            .decodeList()
    }

    override suspend fun mediaFor(eventId: String): List<EventMediaDto> = io {
        client.postgrest.from(EVENT_MEDIA).select {
            filter { eq("event_id", eventId) }
            order("sort_index", Order.ASCENDING)
        }.decodeList()
    }

    // ---- Writes -----------------------------------------------------------------------

    override suspend fun saveEvent(params: SaveEventParams): String = io {
        client.postgrest.rpc(SAVE_EVENT, params).decodeAs()
    }

    override suspend fun deleteEvent(eventId: String) = io {
        client.postgrest.rpc(
            DELETE_EVENT,
            buildJsonObject { put("p_event_id", JsonPrimitive(eventId)) },
        )
        Unit
    }

    override suspend fun findOrCreateArtist(name: String): String = io {
        client.postgrest.rpc(
            FIND_OR_CREATE_ARTIST,
            buildJsonObject { put("p_name", JsonPrimitive(name)) },
        ).decodeAs()
    }

    override suspend fun renameArtist(artistId: String, newName: String): String = io {
        client.postgrest.rpc(
            RENAME_ARTIST,
            buildJsonObject {
                put("p_artist_id", JsonPrimitive(artistId))
                put("p_new_name", JsonPrimitive(newName))
            },
        ).decodeAs()
    }

    override suspend fun previewArtistDeletion(artistId: String): ArtistDeletionImpactDto = io {
        // The function `returns table(...)`, so PostgREST hands back an array of one row rather
        // than a bare object.
        client.postgrest.rpc(
            PREVIEW_ARTIST_DELETION,
            buildJsonObject { put("p_artist_id", JsonPrimitive(artistId)) },
        ).decodeList<ArtistDeletionImpactDto>().firstOrNull()
            ?: ArtistDeletionImpactDto(showsAffected = 0, showsDeleted = 0)
    }

    override suspend fun deleteArtist(artistId: String) = io {
        client.postgrest.rpc(
            DELETE_ARTIST,
            buildJsonObject { put("p_artist_id", JsonPrimitive(artistId)) },
        )
        Unit
    }

    override suspend fun importBackup(payload: JsonObject): ImportResultDto = io {
        client.postgrest.rpc(
            IMPORT_BACKUP,
            buildJsonObject { put("payload", payload) },
        ).decodeAs()
    }

    override suspend fun exportBackup(): JsonObject = io {
        client.postgrest.rpc(EXPORT_BACKUP).decodeAs()
    }

    override suspend fun addMedia(rows: List<EventMediaDto>) = io {
        // upsert, not insert. The sync queue re-sends every media row for an event, so rows that
        // already reached the server arrive again on the next photo — a plain insert collides on
        // the primary key, and because the drain stops at the first failure that one conflict
        // blocks the whole outbox permanently. Ignoring duplicates makes the replay idempotent,
        // which is what a queue that can be retried needs.
        client.postgrest.from(EVENT_MEDIA).upsert(rows) { ignoreDuplicates = true }
        Unit
    }

    override suspend fun deleteMedia(mediaId: String) = io {
        client.postgrest.from(EVENT_MEDIA).delete { filter { eq("id", mediaId) } }
        Unit
    }

    override suspend fun setArtistProfile(
        artistId: String,
        imageUrl: String?,
        genres: String,
        spotifyUrl: String?,
    ) = io {
        client.postgrest.rpc(
            SET_ARTIST_PROFILE,
            buildJsonObject {
                put("p_artist_id", JsonPrimitive(artistId))
                put("p_image_url", imageUrl?.let(::JsonPrimitive) ?: JsonNull)
                put("p_genres", JsonPrimitive(genres))
                put("p_spotify_url", spotifyUrl?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        Unit
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private companion object {
        const val CITY_PINS = "city_pins"
        const val VENUE_PINS = "venue_pins"
        const val EVENT_SUMMARIES = "event_summaries"
        const val ARTIST_EVENT_SUMMARIES = "artist_event_summaries"
        const val ARTIST_SUMMARIES = "artist_summaries"
        const val EVENTS = "events"
        const val ARTISTS = "artists"
        const val VENUES = "venues"
        const val EVENT_MEDIA = "event_media"

        const val SAVE_EVENT = "save_event"
        const val DELETE_EVENT = "delete_event"
        const val RENAME_ARTIST = "rename_artist"
        const val PREVIEW_ARTIST_DELETION = "preview_artist_deletion"
        const val DELETE_ARTIST = "delete_artist"
        const val SET_ARTIST_PROFILE = "catalog_set_artist_profile"
        const val FIND_OR_CREATE_ARTIST = "catalog_find_or_create_artist"
        const val IMPORT_BACKUP = "import_backup"
        const val EXPORT_BACKUP = "export_backup"
    }
}
