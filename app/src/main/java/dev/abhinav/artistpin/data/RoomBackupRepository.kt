package dev.abhinav.artistpin.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import dev.abhinav.artistpin.core.database.ArtistDao
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.ArtistEntity
import dev.abhinav.artistpin.core.database.CityEntity
import dev.abhinav.artistpin.core.database.ConcertDao
import dev.abhinav.artistpin.core.database.EventArtistCrossRef
import dev.abhinav.artistpin.core.database.EventEntity
import dev.abhinav.artistpin.core.database.EventMediaEntity
import dev.abhinav.artistpin.core.database.MediaDao
import dev.abhinav.artistpin.core.database.VenueEntity
import dev.abhinav.artistpin.core.model.BackupArtist
import dev.abhinav.artistpin.core.model.BackupCity
import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.BackupEvent
import dev.abhinav.artistpin.core.model.BackupEventArtist
import dev.abhinav.artistpin.core.model.BackupMedia
import dev.abhinav.artistpin.core.model.BackupVenue
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class RoomBackupRepository(
    private val database: ArtistPinDatabase,
    private val concertDao: ConcertDao,
    private val artistDao: ArtistDao,
    private val mediaDao: MediaDao,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryBackup {

    /** Restoring here wipes the local tables first — see [restore]. */
    override val restoreReplaces: Boolean = true

    override suspend fun export(): DataResult<String> = runCatchingBackup {
        val backup = BackupData(
            exportedAtEpochMillis = now(),
            cities = concertDao.allCities().map {
                BackupCity(it.id, it.name, it.country, it.region)
            },
            venues = concertDao.allVenues().map {
                BackupVenue(it.id, it.name, it.cityId, it.latitude, it.longitude, it.address)
            },
            artists = artistDao.allArtists().map {
                BackupArtist(it.id, it.name, it.imageUrl, it.genres, it.spotifyUrl)
            },
            events = concertDao.allEvents().map {
                BackupEvent(it.id, it.venueId, it.dateEpochDay, it.title)
            },
            eventArtists = concertDao.allEventArtists().map {
                BackupEventArtist(it.eventId, it.artistId, it.billing.name)
            },
            media = mediaDao.allMedia().map {
                BackupMedia(
                    it.id, it.eventId, it.localPath, it.originalUri,
                    it.mimeType, it.capturedAt, it.sortIndex, it.remotePath,
                )
            },
        )
        json.encodeToString(BackupData.serializer(), backup)
    }

    /** Parsed separately from restoring so the UI can state what a file holds before replacing anything. */
    override suspend fun parse(contents: String): DataResult<BackupData> = runCatchingBackup {
        val backup = json.decodeFromString(BackupData.serializer(), contents)
        require(backup.version <= BackupData.CURRENT_VERSION) {
            "That backup was made by a newer version of Artist Pin"
        }
        backup
    }

    /**
     * Replaces everything. Deleting the cities cascades through venues, events, media and
     * cross-refs, so the insert order below has to follow the foreign keys back down.
     *
     * One transaction, start to finish.
     *
     * Without it Room's invalidation tracker fires between the wipe and the re-insert, so every
     * open observer briefly sees an empty library. That was tolerable when a restore was a rare,
     * deliberate act — it is not now that Milestone C's sync runs this on every pull, which would
     * mean the map blanking and repopulating each time a show is saved.
     */
    override suspend fun restore(backup: BackupData): DataResult<RestoreSummary> = runCatchingBackup {
        database.withTransaction { restoreWithin(backup) }
    }

    private suspend fun restoreWithin(backup: BackupData): RestoreSummary {
        // Device-local bookkeeping that the server neither knows nor should know: how many times
        // uploading this file has been given up on. The backup format has no field for it, so
        // rebuilding rows from the payload would silently reset it to zero — and a photo that can
        // never upload would be queued again after every refresh, which is exactly the loop the
        // counter exists to stop.
        val uploadAttemptsById = mediaDao.allMedia().associate { it.id to it.uploadAttempts }

        // Artwork this device resolved that the payload may not know about yet.
        //
        // Keyed by name, not id: an artist added here has a local id until the first pull, after
        // which the catalog's id replaces it, so matching on id would miss exactly the rows this
        // protects. Derived data, not the user's — a payload that *has* a portrait still wins
        // below, and this only fills what arrives blank.
        val localArtwork = artistDao.allArtists()
            .filter { it.imageUrl != null || it.genres != null }
            .associateBy { it.name.normalizedArtistKey() }

        concertDao.deleteAllCities()
        artistDao.deleteAllArtists()

        concertDao.insertCities(
            backup.cities.map { CityEntity(it.id, it.name, it.country, it.region) },
        )
        concertDao.insertVenues(
            backup.venues.map {
                VenueEntity(it.id, it.name, it.cityId, it.latitude, it.longitude, it.address)
            },
        )
        artistDao.upsertArtists(
            backup.artists.map {
                val known = localArtwork[it.name.normalizedArtistKey()]
                ArtistEntity(
                    id = it.id,
                    name = it.name,
                    imageUrl = it.imageUrl ?: known?.imageUrl,
                    // Null genres means "never looked up", which is what puts an artist back in
                    // the lookup queue. Carrying the local value over is what stops every pull
                    // from re-running a lookup this device has already done.
                    genres = it.genres ?: known?.genres,
                    spotifyUrl = it.spotifyUrl ?: known?.spotifyUrl,
                )
            },
        )
        concertDao.insertEvents(
            backup.events.map {
                EventEntity(it.id, it.venueId, it.dateEpochDay, it.title)
            },
        )
        concertDao.insertEventArtists(
            backup.eventArtists.map {
                EventArtistCrossRef(
                    eventId = it.eventId,
                    artistId = it.artistId,
                    billing = runCatching { Billing.valueOf(it.billing) }
                        .getOrDefault(Billing.HEADLINER),
                )
            },
        )
        // A row is worth keeping if its bytes are reachable from *somewhere*: the file on this
        // device, or object storage. Before Milestone D only the first existed, so a restore onto a
        // fresh install dropped every photo rather than leaving broken thumbnails; now a row with a
        // storage path is recoverable on any device and must survive.
        mediaDao.upsertMedia(
            backup.media
                .filter { File(it.localPath).exists() || it.storagePath != null }
                .map {
                    EventMediaEntity(
                        it.id, it.eventId, it.localPath, it.originalUri,
                        it.mimeType, it.capturedAt, it.sortIndex, it.storagePath,
                        uploadAttempts = uploadAttemptsById[it.id] ?: 0,
                    )
                },
        )
        // Nothing is skipped here: this implementation wipes and rewrites, so every event in the
        // file is applied by definition.
        return RestoreSummary(applied = backup.events.size, skipped = 0)
    }

    private suspend fun <T> runCatchingBackup(block: suspend () -> T): DataResult<T> =
        withContext(ioDispatcher) {
            try {
                DataResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                DataResult.Failure(DataError.Validation(e.message ?: "That file isn't a valid backup"))
            } catch (e: SQLiteException) {
                DataResult.Failure(DataError.Storage)
            } catch (e: Exception) {
                DataResult.Failure(DataError.Validation("That file isn't a valid Artist Pin backup"))
            }
        }
}
