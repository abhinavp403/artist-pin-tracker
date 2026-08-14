package dev.abhinav.artistpin.data

import android.net.Uri
import android.util.Log
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenuePin
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.backend.EventMediaDto
import dev.abhinav.artistpin.data.backend.SaveEventParams
import dev.abhinav.artistpin.data.backend.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * The Supabase-backed implementation. Same surface as [RoomConcertRepository]; no screen can tell
 * them apart.
 *
 * ---
 *
 * **The one genuinely new problem: where do the Flows come from?**
 *
 * Room returns `Flow` natively — it knows when a table changed and re-runs the query. PostgREST is
 * request/response and knows nothing of the sort, so the emissions have to be manufactured here.
 *
 * The mechanism is [refresh]: a shared flow that every write pokes and every read collects. A read
 * fetches once per emission, so saving a show causes every open observer to re-fetch exactly once.
 * That is a deliberate choice over Supabase Realtime, for two reasons: Realtime is a websocket held
 * open for the entire session to deliver events that, for a single-user library, only ever
 * originate from this device anyway; and Milestone C replaces this whole mechanism regardless, when
 * Room returns as a cache and goes back to being the thing that emits, with the network only
 * filling it.
 *
 * The cost, stated plainly: a change made on another device does not appear until something here
 * triggers a refresh. For an app where the same person owns every write, that is a fair trade —
 * and it is exactly the gap Milestone C's sync closes.
 */
class BackendConcertRepository(
    private val api: BackendApi,
    private val mediaImporter: MediaImporter,
    private val artistImageSource: ArtistImageSource,
    private val ioDispatcher: CoroutineDispatcher,
) : ConcertRepository {

    /**
     * replay = 1 so a collector that subscribes after a write still gets an immediate first value
     * rather than sitting empty until the next one. DROP_OLDEST because these carry no information
     * beyond "something changed" — coalescing a burst of them into one refetch is the correct
     * behaviour, not a lossy approximation of it.
     */
    private val refresh = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    private val attemptedArtistIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * One read, re-run on every refresh.
     *
     * A failed fetch re-emits the previous value instead of propagating. The interface has no error
     * channel on the read side — the Room implementation could not fail — so the only alternatives
     * are cancelling the collector, which kills the screen, or emitting empty, which draws an empty
     * map and reads to the user as "your shows are gone". Holding the last good value means a
     * dropped connection looks like stale data, which is what it is.
     */
    private fun <T> backendFlow(initial: T, fetch: suspend () -> T): Flow<T> = flow {
        var last = initial
        refresh.collect {
            last = try {
                withContext(ioDispatcher) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed, holding previous value", e)
                last
            }
            emit(last)
        }
    }

    /** Called after every write, so open screens re-read. */
    private fun invalidate() {
        refresh.tryEmit(Unit)
    }

    override fun observeCityPins(): Flow<List<CityPin>> =
        backendFlow(emptyList()) { api.cityPins().map { it.toModel() } }

    override fun observeVenuePins(cityId: String): Flow<List<VenuePin>> =
        backendFlow(emptyList()) { api.venuePins(cityId).map { it.toModel() } }

    override fun observeAllVenuePins(): Flow<List<VenuePin>> =
        backendFlow(emptyList()) { api.venuePins().map { it.toModel() } }

    override fun observeEventsInCity(cityId: String): Flow<List<EventSummary>> =
        backendFlow(emptyList()) { api.events(cityId = cityId).map { it.toModel() } }

    override fun observeEventsAtVenue(venueId: String): Flow<List<EventSummary>> =
        backendFlow(emptyList()) { api.events(venueId = venueId).map { it.toModel() } }

    override fun observeAllEvents(): Flow<List<EventSummary>> =
        backendFlow(emptyList()) { api.events().map { it.toModel() } }

    override fun observeEventsForArtist(artistId: String): Flow<List<EventSummary>> =
        backendFlow(emptyList()) { api.events(artistId = artistId).map { it.toModel() } }

    override fun observeArtistSummaries(): Flow<List<ArtistSummary>> =
        backendFlow(emptyList()) { api.artistSummaries().map { it.toModel() } }

    override fun observeArtist(artistId: String): Flow<Artist?> =
        backendFlow(null) { api.artist(artistId)?.toModel() }

    override fun observeKnownArtists(): Flow<List<Artist>> =
        backendFlow(emptyList()) { api.artists().map { it.toModel() } }

    override fun observeKnownVenues(): Flow<List<Pair<Venue, City>>> =
        backendFlow(emptyList()) {
            // Venues whose city failed to embed are dropped rather than shown detached: the add
            // form uses this list to prefill a city, and a venue with no city would prefill blank.
            api.venues().mapNotNull { dto ->
                dto.city?.let { dto.toModel() to it.toModel() }
            }
        }

    override fun observeEvent(eventId: String): Flow<ConcertEvent?> =
        backendFlow(null) { api.event(eventId)?.toModel() }

    /**
     * The backfill, against the shared catalog. Structurally the same as the Room version, but the
     * work is now global rather than per-device: an artist filled in by anyone is filled in for
     * everyone, so this loop gets quieter as the catalog grows.
     */
    override suspend fun keepArtistArtworkFresh() = withContext(ioDispatcher) {
        refresh.collect {
            val pending = runCatching { api.artistsWithoutProfile() }
                .getOrElse {
                    Log.w(TAG, "Could not list artists needing artwork", it)
                    return@collect
                }
                .filter { attemptedArtistIds.add(it.id) }

            if (pending.isEmpty()) return@collect

            Log.d(TAG, "Looking up artwork for ${pending.size}: ${pending.joinToString { it.name }}")
            var wrote = false
            pending.forEach { artist ->
                coroutineContext.ensureActive()
                val profile = artistImageSource.profileFor(artist.name)
                if (profile.lookupFailed) {
                    Log.d(TAG, "Lookup failed for '${artist.name}', will retry next launch")
                    attemptedArtistIds.remove(artist.id)
                    return@forEach
                }
                runCatching {
                    api.setArtistProfile(
                        artistId = artist.id,
                        imageUrl = profile.imageUrl,
                        genres = profile.genres.joinToString(GENRE_SEPARATOR),
                        spotifyUrl = profile.spotifyUrl,
                    )
                    wrote = true
                }.onFailure { Log.w(TAG, "Could not store profile for '${artist.name}'", it) }
            }
            // Only after the batch: invalidating per artist would restart this very collection
            // once per lookup, and each restart re-lists the pending set.
            if (wrote) invalidate()
        }
    }

    override suspend fun saveEvent(draft: EventDraft): DataResult<String> = runCatchingData {
        // Kept identical to the Room implementation. These are not the backend's protection —
        // save_event re-checks them server-side — they are what turns a bad draft into an
        // immediate field error instead of a round trip.
        require(draft.artistNames.any { it.isNotBlank() }) { "At least one artist is required" }
        require(draft.venueName.isNotBlank()) { "A venue name is required" }
        require(draft.cityName.isNotBlank()) { "A city name is required" }
        require(draft.country.isNotBlank()) { "A country is required" }
        require(draft.latitude in VALID_LATITUDE && draft.longitude in VALID_LONGITUDE) {
            "A valid venue location is required"
        }

        // No find-or-create here, and no existingCityId/existingVenueId: resolving natural keys is
        // save_event's job now, inside one transaction against the shared catalog. Doing it from
        // the client would race — two people adding their first Toronto show would both look, both
        // miss, and both insert.
        val eventId = api.saveEvent(
            SaveEventParams(
                eventId = draft.eventId,
                eventDate = draft.date.toString(),
                cityName = draft.cityName.trim(),
                country = draft.country.trim(),
                region = draft.region?.trim()?.takeIf { it.isNotEmpty() },
                venueName = draft.venueName.trim(),
                latitude = draft.latitude,
                longitude = draft.longitude,
                address = draft.address?.trim()?.takeIf { it.isNotEmpty() },
                artistNames = draft.artistNames.map { it.trim() }.filter { it.isNotEmpty() },
                supportNames = draft.supportArtistNames.map { it.trim() }.filter { it.isNotEmpty() },
                title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
                notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                rating = draft.rating,
            ),
        )
        invalidate()
        eventId
    }

    override suspend fun previewArtistDeletion(artistId: String): DataResult<ArtistDeletionImpact> =
        runCatchingData { api.previewArtistDeletion(artistId).toModel() }

    override suspend fun deleteArtist(artistId: String): DataResult<Unit> = runCatchingData {
        // Media has to be collected before the delete: afterwards the shows are gone and there is
        // nothing left to say which files on disk belonged to them.
        //
        // Deliberately not wrapped in runCatching. If this list cannot be fetched we do not know
        // which files the delete is about to orphan, and guessing in either direction is worse
        // than not starting: assuming none leaks every photo of the deleted shows forever, and
        // assuming all deletes photos belonging to shows that survive. Letting it throw aborts
        // before anything is destroyed, and the user can simply try again.
        val affectedEventIds = api.events(artistId = artistId).map { it.id }

        api.deleteArtist(artistId)

        // Deletes only the caller's links and any show left with nobody on the bill. The catalog
        // artist row survives — see the note on delete_artist in 0004_library_functions.sql.
        affectedEventIds.forEach { eventId ->
            // fold, not getOrNull: a failed lookup is not evidence the show is gone. Collapsing
            // the two would delete the photos of a show that still exists, and media never leaves
            // the device until Milestone D, so there is nothing to restore them from.
            val definitelyGone = runCatching { api.event(eventId) }
                .fold(onSuccess = { it == null }, onFailure = { false })
            if (definitelyGone) mediaImporter.deleteAllForEvent(eventId)
        }
        invalidate()
    }

    override suspend fun renameArtist(artistId: String, newName: String): DataResult<Unit> =
        runCatchingData {
            require(newName.isBlank().not()) { "An artist needs a name" }
            // Does not rename the shared catalog row — it repoints this user's shows at the
            // correctly-named artist, creating it if nobody has used that spelling yet.
            api.renameArtist(artistId, newName.trim())
            invalidate()
        }

    override suspend fun deleteEvent(eventId: String): DataResult<Unit> = runCatchingData {
        api.deleteEvent(eventId)
        mediaImporter.deleteAllForEvent(eventId)
        invalidate()
    }

    override suspend fun addMedia(eventId: String, uris: List<Uri>): DataResult<Int> =
        runCatchingData {
            val existing = api.mediaFor(eventId)
            val alreadyImported = existing.map { it.originalUri }.toSet()
            val fresh = uris.filterNot { it.toString() in alreadyImported }
            if (fresh.isEmpty()) return@runCatchingData 0

            val startIndex = (existing.maxOfOrNull { it.sortIndex } ?: -1) + 1
            val imported = mediaImporter.import(eventId, fresh)
            if (imported.isEmpty()) throw IOException("None of the selected items could be read")

            // Files still land on this device; only the row describing them is remote. Milestone D
            // is what moves the bytes, at which point remote_url starts being populated too.
            api.addMedia(
                imported.mapIndexed { index, item ->
                    EventMediaDto(
                        id = UUID.randomUUID().toString(),
                        eventId = eventId,
                        localPath = item.localPath,
                        originalUri = item.originalUri,
                        mimeType = item.mimeType,
                        // Room stores epoch millis; the column is timestamptz, so the EXIF capture
                        // time has to be converted rather than dropped. Passing null here silently
                        // undated every photo added after the cutover.
                        capturedAt = item.capturedAt?.let { Instant.ofEpochMilli(it).toString() },
                        sortIndex = startIndex + index,
                    )
                },
            )
            invalidate()
            imported.size
        }

    override suspend fun removeMedia(mediaId: String, localPath: String): DataResult<Unit> =
        runCatchingData {
            api.deleteMedia(mediaId)
            mediaImporter.delete(localPath)
            invalidate()
        }

    private suspend fun <T> runCatchingData(block: suspend () -> T): DataResult<T> =
        withContext(ioDispatcher) {
            try {
                DataResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                DataResult.Failure(DataError.Validation(e.message ?: "Invalid input"))
            } catch (e: IOException) {
                // Every write here is a network call, so a dropped connection is the common
                // failure — not the storage fault the Room implementation maps this to.
                DataResult.Failure(DataError.Network(e.message))
            } catch (e: Exception) {
                Log.w(TAG, "Backend call failed", e)
                DataResult.Failure(DataError.Unknown(e.message))
            }
        }

    private companion object {
        const val TAG = "ArtistPinBackend"
        val VALID_LATITUDE = -90.0..90.0
        val VALID_LONGITUDE = -180.0..180.0
    }
}
