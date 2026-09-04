package dev.abhinav.artistpin.data.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the Supabase tables, views and functions in `/supabase`.
 *
 * Deliberately separate types from the domain models in `core/model` rather than serializing those
 * directly. The domain models carry `LocalDate` and split genres into a `List<String>`; the wire
 * carries ISO strings and the delimited genre column exactly as stored. Mapping between them is
 * `BackendMappers`, and keeping the two apart means a column rename is a one-line change here
 * instead of a change that reaches every screen.
 *
 * Every name here is snake_case because that is what PostgREST returns. They are spelled out with
 * @SerialName rather than configured globally, so a mismatch is a compile-time-visible typo in one
 * place rather than a silently-null field.
 */

@Serializable
data class CityDto(
    val id: String,
    val name: String,
    val country: String,
    val region: String? = null,
)

@Serializable
data class VenueDto(
    val id: String,
    val name: String,
    @SerialName("city_id") val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    /** Present only when the query embeds the parent city. */
    @SerialName("cities") val city: CityDto? = null,
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    /**
     * The delimited string, carried through unchanged. Its null / "" / values tri-state is what
     * drives the artwork backfill — see the column comment in 0001_schema.sql.
     */
    val genres: String? = null,
    @SerialName("spotify_url") val spotifyUrl: String? = null,
)

@Serializable
data class CityPinDto(
    val id: String,
    val name: String,
    val country: String,
    val region: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("event_count") val eventCount: Int,
    @SerialName("venue_count") val venueCount: Int,
    @SerialName("last_event_date") val lastEventDate: String? = null,
)

@Serializable
data class VenuePinDto(
    val id: String,
    val name: String,
    @SerialName("city_id") val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    @SerialName("event_count") val eventCount: Int,
    @SerialName("last_event_date") val lastEventDate: String? = null,
    @SerialName("headliner_name") val headlinerName: String? = null,
    @SerialName("headliner_image_url") val headlinerImageUrl: String? = null,
)

@Serializable
data class EventSummaryDto(
    val id: String,
    @SerialName("event_date") val eventDate: String,
    val title: String? = null,
    val rating: Int? = null,
    @SerialName("venue_id") val venueId: String,
    @SerialName("venue_name") val venueName: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("city_name") val cityName: String,
    /** Both are '||'-delimited, matching the string_agg separator in 0005_views.sql. */
    @SerialName("headliner_names") val headlinerNames: String? = null,
    @SerialName("support_names") val supportNames: String? = null,
    @SerialName("media_count") val mediaCount: Int = 0,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class EventMediaDto(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("local_path") val localPath: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
    @SerialName("original_uri") val originalUri: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("captured_at") val capturedAt: String? = null,
    @SerialName("sort_index") val sortIndex: Int = 0,
    /** Where the bytes live in the bucket. Null until the upload has happened. */
    @SerialName("storage_path") val storagePath: String? = null,
)

/** One row of the `event_artists` join, with the artist embedded. */
@Serializable
data class EventArtistDto(
    val billing: String,
    @SerialName("artists") val artist: ArtistDto,
)

/**
 * The full show. Read from the `events` table rather than a view, because PostgREST can embed
 * related rows through foreign keys but not through a view's joins.
 */
@Serializable
data class EventDetailDto(
    val id: String,
    @SerialName("event_date") val eventDate: String,
    val title: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
    @SerialName("venues") val venue: VenueDto,
    @SerialName("event_artists") val artists: List<EventArtistDto> = emptyList(),
    @SerialName("event_media") val media: List<EventMediaDto> = emptyList(),
)

@Serializable
data class ArtistSummaryDto(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val genres: String? = null,
    @SerialName("spotify_url") val spotifyUrl: String? = null,
    @SerialName("times_seen") val timesSeen: Int,
    /** Comma-delimited, from string_agg(distinct …) in artist_summaries. */
    @SerialName("city_names") val cityNames: String? = null,
    @SerialName("first_seen_date") val firstSeenDate: String? = null,
    @SerialName("last_seen_date") val lastSeenDate: String? = null,
)

@Serializable
data class ArtistDeletionImpactDto(
    @SerialName("shows_affected") val showsAffected: Int,
    @SerialName("shows_deleted") val showsDeleted: Int,
)

/** What `import_backup` reports back. Counts, so the UI can say what actually happened. */
@Serializable
data class ImportResultDto(
    @SerialName("events_imported") val eventsImported: Int = 0,
    /** Already present from a previous run — the import is idempotent by event id. */
    @SerialName("events_skipped") val eventsSkipped: Int = 0,
    @SerialName("artists_seen") val artistsSeen: Int = 0,
    @SerialName("media_imported") val mediaImported: Int = 0,
)

/** Arguments for the `save_event` RPC. Field names must match the function's `p_` parameters. */
@Serializable
data class SaveEventParams(
    @SerialName("p_event_id") val eventId: String? = null,
    @SerialName("p_event_date") val eventDate: String,
    @SerialName("p_city_name") val cityName: String,
    @SerialName("p_country") val country: String,
    @SerialName("p_region") val region: String? = null,
    @SerialName("p_venue_name") val venueName: String,
    @SerialName("p_latitude") val latitude: Double,
    @SerialName("p_longitude") val longitude: Double,
    @SerialName("p_address") val address: String? = null,
    @SerialName("p_artist_names") val artistNames: List<String>,
    @SerialName("p_support_names") val supportNames: List<String> = emptyList(),
    @SerialName("p_title") val title: String? = null,
    @SerialName("p_notes") val notes: String? = null,
    @SerialName("p_rating") val rating: Int? = null,
)
