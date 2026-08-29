package dev.abhinav.artistpin.data.backend

import kotlinx.serialization.json.JsonObject

/**
 * Everything the app needs from the backend, as one interface.
 *
 * Kept as an interface for the same reason `VenueSearchService` and `ArtistImageSource` are: B6
 * swaps `ConcertRepository`'s guts to call this, and a fake implementation of this file is what
 * makes that testable without a network or a project.
 *
 * ---
 *
 * **Why this is not Retrofit, though the plan said Retrofit.**
 *
 * The plan's phrasing was "a `BackendApi` Retrofit interface … plus a bearer-token interceptor that
 * attaches the signed-in user's session token". The first half is fine; the second half is a trap.
 *
 * An OkHttp `Interceptor` is synchronous. Getting a valid token is not — an access token expires
 * roughly hourly and has to be refreshed over the network before the call it is meant to authorise.
 * A hand-written interceptor therefore has to either `runBlocking` on a refresh from inside
 * OkHttp's thread pool, or attach a stale token and handle the 401 on the way back out. The first
 * deadlocks under load; the second means every screen has to tolerate a spurious auth failure an
 * hour into a session.
 *
 * supabase-kt already solves this correctly, and its session — the same `SupabaseClient` that B2's
 * `AuthRepository` signs into — is the thing that holds the token. Routing the data calls through
 * a second HTTP stack would mean two components owning one session's lifetime.
 *
 * The tradeoff accepted: a second networking idiom next to Retrofit. Retrofit still owns the three
 * third-party APIs (Spotify-via-proxy, Deezer, MusicBrainz), which are unauthenticated and stay
 * exactly as they are. Nothing about them changes.
 *
 * ---
 *
 * **These are suspend functions, not Flows** — and that is the one thing B6 will have to solve
 * rather than inherit. `ConcertRepository`'s surface is `observeCityPins()`, `observeAllEvents()`
 * and so on, all `Flow`. PostgREST is request/response and has nothing to emit into them. So B6 is
 * not the pure swap the plan describes: it needs a refresh trigger (a `MutableSharedFlow` the write
 * methods poke, with reads re-running on each emission) or Supabase Realtime subscriptions. The
 * refresh-trigger version is the smaller change and is what Milestone C's Room cache would replace
 * anyway, since at that point Room is the thing emitting and the network only fills it.
 */
interface BackendApi {

    // ---- Reads ------------------------------------------------------------------------

    suspend fun cityPins(): List<CityPinDto>

    /** Every venue when [cityId] is null — the world map's zoomed-in artist pins need all of them. */
    suspend fun venuePins(cityId: String? = null): List<VenuePinDto>

    /**
     * At most one of [cityId], [venueId] and [artistId] is applied. The first two are column
     * filters on `event_summaries`; [artistId] goes to `artist_event_summaries`, which exists
     * precisely because that one cannot be expressed as a column filter.
     */
    suspend fun events(
        cityId: String? = null,
        venueId: String? = null,
        artistId: String? = null,
    ): List<EventSummaryDto>

    suspend fun event(eventId: String): EventDetailDto?

    suspend fun artistSummaries(): List<ArtistSummaryDto>

    suspend fun artist(artistId: String): ArtistDto?

    suspend fun artists(): List<ArtistDto>

    /** Artists whose profile has never been looked up, driving the artwork backfill. */
    suspend fun artistsWithoutProfile(): List<ArtistDto>

    suspend fun venues(): List<VenueDto>

    /** Media rows for one show — the backend's answer to `MediaDao.getMediaForEvent`. */
    suspend fun mediaFor(eventId: String): List<EventMediaDto>

    // ---- Writes -----------------------------------------------------------------------

    /** Returns the event id, whether it was created or updated. */
    suspend fun saveEvent(params: SaveEventParams): String

    suspend fun deleteEvent(eventId: String)

    /**
     * The catalog id for a name, creating the row if nobody has used that spelling yet.
     *
     * Needed by the sync replay: a queued rename or delete carries the artist's *name*, because the
     * local Room id of an artist created offline means nothing to a catalog that deduplicates by
     * name. This is how that name becomes an id the server recognises.
     */
    suspend fun findOrCreateArtist(name: String): String

    /** Returns the id of the artist the caller's shows now point at, which may be a different row. */
    suspend fun renameArtist(artistId: String, newName: String): String

    suspend fun previewArtistDeletion(artistId: String): ArtistDeletionImpactDto

    suspend fun deleteArtist(artistId: String)

    /**
     * Replays a device's whole local library into the account, once. Idempotent — events keep
     * their original ids, so a second run skips rather than duplicates.
     */
    suspend fun importBackup(payload: JsonObject): ImportResultDto

    /** The caller's whole library in the app's backup format — the mirror of [importBackup]. */
    suspend fun exportBackup(): JsonObject

    suspend fun addMedia(rows: List<EventMediaDto>)

    suspend fun deleteMedia(mediaId: String)

    /**
     * Fills in blanks on a shared catalog row; cannot overwrite. See the coalesce note on
     * `catalog_set_artist_profile` in 0003_catalog_functions.sql.
     */
    suspend fun setArtistProfile(
        artistId: String,
        imageUrl: String?,
        genres: String,
        spotifyUrl: String?,
    )
}
