package dev.abhinav.artistpin.data

import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import dev.abhinav.artistpin.core.database.ArtistDao
import dev.abhinav.artistpin.core.database.ArtistEntity
import dev.abhinav.artistpin.core.database.CityEntity
import dev.abhinav.artistpin.core.database.ConcertDao
import dev.abhinav.artistpin.core.database.EventArtistCrossRef
import dev.abhinav.artistpin.core.database.EventEntity
import dev.abhinav.artistpin.core.database.EventMediaEntity
import dev.abhinav.artistpin.core.database.MediaDao
import dev.abhinav.artistpin.core.database.VenueEntity
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenuePin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * The device-local implementation — everything in Room, no account, no network beyond artwork
 * lookups. This is the original `ConcertRepository`, unchanged apart from now naming the interface
 * it always implicitly satisfied.
 */
class RoomConcertRepository(
    private val concertDao: ConcertDao,
    private val artistDao: ArtistDao,
    private val mediaDao: MediaDao,
    private val mediaImporter: MediaImporter,
    private val artistImageSource: ArtistImageSource,
    private val ioDispatcher: CoroutineDispatcher,
) : ConcertRepository {

    /** Artists already looked up this session, so a null result isn't retried in a loop. */
    private val attemptedArtistIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun observeCityPins(): Flow<List<CityPin>> =
        concertDao.observeCityPins().map { rows -> rows.map { it.toModel() } }

    override fun observeVenuePins(cityId: String): Flow<List<VenuePin>> =
        concertDao.observeVenuePins(cityId).map { rows -> rows.map { it.toModel() } }

    override fun observeAllVenuePins(): Flow<List<VenuePin>> =
        concertDao.observeAllVenuePins().map { rows -> rows.map { it.toModel() } }

    /**
     * Watches for artists with no artwork and fills them in as they appear. This has to be a
     * running collection rather than a one-shot call: the map is the app's start destination, so
     * a one-shot fires before the user has added anything and never sees artists saved later.
     *
     * Best-effort — a lookup that fails leaves imageUrl null and the pin falls back to initials.
     */
    override suspend fun keepArtistArtworkFresh() = withContext(ioDispatcher) {
        artistDao.observeArtistsWithoutProfile().collect { artists ->
            // Artists with no portrait anywhere stay in this query forever, so without the
            // attempted guard every emission would re-query them in a loop.
            val pending = artists.filter { attemptedArtistIds.add(it.id) }
            if (pending.isEmpty()) return@collect

            Log.d(TAG, "Looking up artwork for ${pending.size}: ${pending.joinToString { it.name }}")
            pending.forEach { artist ->
                coroutineContext.ensureActive()
                val profile = artistImageSource.profileFor(artist.name)
                if (profile.lookupFailed) {
                    // Leaving genres null keeps this artist in the query, so the next launch
                    // tries again instead of caching an outage as "this artist has no genres".
                    Log.d(TAG, "Lookup failed for '${artist.name}', will retry next launch")
                    attemptedArtistIds.remove(artist.id)
                    return@forEach
                }
                if (profile.isEmpty) Log.d(TAG, "Nothing found for '${artist.name}'")
                // Written even when empty: the non-null genres column is what marks this artist
                // as already looked up, so a genuine blank is never re-queried.
                runCatching {
                    artistDao.setProfile(
                        artistId = artist.id,
                        imageUrl = profile.imageUrl,
                        genres = profile.genres.joinToString(GENRE_SEPARATOR),
                        spotifyUrl = profile.spotifyUrl,
                    )
                }.onFailure { Log.w(TAG, "Could not store profile for '${artist.name}'", it) }
            }
        }
    }

    override fun observeEventsInCity(cityId: String): Flow<List<EventSummary>> =
        concertDao.observeEventsInCity(cityId).map { rows -> rows.map { it.toModel() } }

    override fun observeEventsAtVenue(venueId: String): Flow<List<EventSummary>> =
        concertDao.observeEventsAtVenue(venueId).map { rows -> rows.map { it.toModel() } }

    override fun observeAllEvents(): Flow<List<EventSummary>> =
        concertDao.observeAllEvents().map { rows -> rows.map { it.toModel() } }

    override fun observeEventsForArtist(artistId: String): Flow<List<EventSummary>> =
        concertDao.observeEventsForArtist(artistId).map { rows -> rows.map { it.toModel() } }

    override fun observeArtistSummaries(): Flow<List<ArtistSummary>> =
        artistDao.observeArtistSummaries().map { rows -> rows.map { it.toModel() } }

    override fun observeArtist(artistId: String): Flow<Artist?> =
        artistDao.observeArtist(artistId).map { it?.toModel() }

    override fun observeKnownArtists(): Flow<List<Artist>> =
        artistDao.observeAllArtists().map { list -> list.map { it.toModel() } }

    override fun observeKnownVenues(): Flow<List<Pair<Venue, City>>> =
        concertDao.observeVenuesWithCity().map { list ->
            list.map { it.venue.toModel() to it.city.toModel() }
        }

    override fun observeEvent(eventId: String): Flow<ConcertEvent?> =
        concertDao.observeEventDetails(eventId).map { details ->
            details ?: return@map null
            val billing = concertDao.getEventArtistRefs(eventId).associate { it.artistId to it.billing }
            details.toModel(billing)
        }

    override suspend fun saveEvent(draft: EventDraft): DataResult<String> = runCatchingData {
        // Mirrors EventEditUiState.missingFields — the screen blocks these first, but the
        // repository is the boundary that has to hold for any other caller too.
        require(draft.artistNames.any { it.isNotBlank() }) { "At least one artist is required" }
        require(draft.venueName.isNotBlank()) { "A venue name is required" }
        require(draft.cityName.isNotBlank()) { "A city name is required" }
        require(draft.country.isNotBlank()) { "A country is required" }
        require(draft.latitude in VALID_LATITUDE && draft.longitude in VALID_LONGITUDE) {
            "A valid venue location is required"
        }

        // Typing "Toronto" for a second show must land on the existing Toronto row: the unique
        // index would make a fresh insert a silent no-op and orphan the venue's foreign key.
        val cityName = draft.cityName.trim()
        val country = draft.country.trim()
        val venueName = draft.venueName.trim()

        val cityId = draft.existingCityId
            ?: concertDao.findCity(cityName, country)?.id
            ?: newId()
        val venueId = draft.existingVenueId
            ?: concertDao.findVenue(venueName, cityId)?.id
            ?: newId()
        val eventId = draft.eventId ?: newId()

        val headliners = resolveArtists(draft.artistNames)
        val support = resolveArtists(draft.supportArtistNames)
        artistDao.upsertArtists(headliners + support)

        concertDao.saveEvent(
            city = CityEntity(
                id = cityId,
                name = cityName,
                country = country,
                region = draft.region?.trim()?.takeIf { it.isNotEmpty() },
            ),
            venue = VenueEntity(
                id = venueId,
                name = venueName,
                cityId = cityId,
                latitude = draft.latitude,
                longitude = draft.longitude,
                address = draft.address?.trim()?.takeIf { it.isNotEmpty() },
            ),
            event = EventEntity(
                id = eventId,
                venueId = venueId,
                dateEpochDay = draft.date.toEpochDay(),
                title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
                notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                rating = draft.rating,
            ),
            artistRefs = headliners.map { EventArtistCrossRef(eventId, it.id, Billing.HEADLINER) } +
                support.map { EventArtistCrossRef(eventId, it.id, Billing.SUPPORT) },
        )
        artistDao.deleteOrphanArtists()
        eventId
    }

    /** Counted before the dialog opens, so it can state exactly what will be lost. */
    override suspend fun previewArtistDeletion(artistId: String): DataResult<ArtistDeletionImpact> =
        runCatchingData {
            ArtistDeletionImpact(
                showsAffected = artistDao.eventCountFor(artistId),
                showsDeleted = artistDao.eventsLeftEmptyBy(artistId),
            )
        }

    /**
     * Removes the artist from every show. Shows left with nobody on the bill go too, along with
     * any venue and city that existed only for them.
     */
    override suspend fun deleteArtist(artistId: String): DataResult<Unit> = runCatchingData {
        val emptiedEventIds = concertDao.observeEventsForArtist(artistId).firstOrNull()
            .orEmpty()
            .map { it.id }
        artistDao.deleteArtist(artistId)
        artistDao.deleteEventsWithoutArtists()
        concertDao.deleteOrphanVenues()
        concertDao.deleteOrphanCities()
        // Media lives on disk, so it has to be cleaned up outside the cascade.
        emptiedEventIds.forEach { eventId ->
            if (concertDao.getEvent(eventId) == null) mediaImporter.deleteAllForEvent(eventId)
        }
    }

    /**
     * Renaming onto a name that already exists merges the two rather than failing the unique
     * index — "deadmau5" typed twice should never become two artists.
     */
    override suspend fun renameArtist(artistId: String, newName: String): DataResult<Unit> =
        runCatchingData {
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { "An artist needs a name" }

            val existing = artistDao.findByName(trimmed)
            if (existing != null && existing.id != artistId) {
                artistDao.reassignEvents(sourceId = artistId, targetId = existing.id)
                artistDao.deleteArtist(artistId)
            } else {
                artistDao.rename(artistId, trimmed)
            }
        }

    override suspend fun deleteEvent(eventId: String): DataResult<Unit> = runCatchingData {
        concertDao.deleteEventAndPrune(eventId)
        artistDao.deleteOrphanArtists()
        mediaImporter.deleteAllForEvent(eventId)
    }

    override suspend fun addMedia(eventId: String, uris: List<Uri>): DataResult<Int> =
        runCatchingData {
            val alreadyImported = mediaDao.originalUris(eventId).toSet()
            val fresh = uris.filterNot { it.toString() in alreadyImported }
            if (fresh.isEmpty()) return@runCatchingData 0

            val startIndex = mediaDao.maxSortIndex(eventId) + 1
            val imported = mediaImporter.import(eventId, fresh)
            if (imported.isEmpty()) throw IOException("None of the selected items could be read")

            mediaDao.upsertMedia(
                imported.mapIndexed { index, item ->
                    EventMediaEntity(
                        id = newId(),
                        eventId = eventId,
                        localPath = item.localPath,
                        originalUri = item.originalUri,
                        mimeType = item.mimeType,
                        capturedAt = item.capturedAt,
                        sortIndex = startIndex + index,
                    )
                },
            )
            imported.size
        }

    override suspend fun removeMedia(mediaId: String, localPath: String): DataResult<Unit> =
        runCatchingData {
            mediaDao.deleteMedia(mediaId)
            mediaImporter.delete(localPath)
        }

    /** Reuses an existing artist row when the name matches case-insensitively, so "deadmau5" and "Deadmau5" stay one artist. */
    private suspend fun resolveArtists(names: List<String>): List<ArtistEntity> =
        names.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .map { name -> artistDao.findByName(name) ?: ArtistEntity(id = newId(), name = name) }

    private fun newId() = UUID.randomUUID().toString()

    private suspend fun <T> runCatchingData(block: suspend () -> T): DataResult<T> =
        withContext(ioDispatcher) {
            try {
                DataResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                DataResult.Failure(DataError.Validation(e.message ?: "Invalid input"))
            } catch (e: SQLiteException) {
                DataResult.Failure(DataError.Storage)
            } catch (e: IOException) {
                DataResult.Failure(DataError.MediaUnavailable)
            } catch (e: Exception) {
                DataResult.Failure(DataError.Unknown(e.message))
            }
        }

    private companion object {
        const val TAG = "ArtistImages"

        /** Guards against NaN and out-of-range values, which SQLite stores happily but no map can render. */
        val VALID_LATITUDE = -90.0..90.0
        val VALID_LONGITUDE = -180.0..180.0
    }
}
