package dev.abhinav.artistpin.data

import android.net.Uri
import dev.abhinav.artistpin.core.database.MediaDao
import dev.abhinav.artistpin.core.database.SyncOutboxDao
import dev.abhinav.artistpin.core.database.SyncOutboxEntity
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
import dev.abhinav.artistpin.core.model.onSuccess
import dev.abhinav.artistpin.data.sync.AddMediaPayload
import dev.abhinav.artistpin.data.sync.DeleteArtistPayload
import dev.abhinav.artistpin.data.sync.DeleteEventPayload
import dev.abhinav.artistpin.data.sync.MediaRowPayload
import dev.abhinav.artistpin.data.sync.NoSyncScheduler
import dev.abhinav.artistpin.data.sync.SyncScheduler
import dev.abhinav.artistpin.data.sync.RemoveMediaPayload
import dev.abhinav.artistpin.data.sync.RenameArtistPayload
import dev.abhinav.artistpin.data.sync.SaveEventPayload
import dev.abhinav.artistpin.data.sync.SyncOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Room in front, the backend behind (execution plan C1–C2).
 *
 * Every read comes from Room, which means the Flows are real again — SQLite tells us when a table
 * changed, so the refresh-trigger machinery `BackendConcertRepository` had to invent is gone, and
 * the map no longer goes blank when the phone has no signal.
 *
 * Every write lands in Room and queues a row in the outbox, then returns. The network is not on the
 * save's critical path at all: adding a show in a basement venue behaves exactly as it did when the
 * app was device-only, which is the entire point of this milestone.
 *
 * Local mutation is delegated to [RoomConcertRepository] rather than reimplemented. That code
 * already handles the natural-key reuse, the orphan pruning and the media cleanup, and it is the
 * best-tested thing in the app; this class only adds the queueing around it.
 *
 * **Conflict policy: last write wins, per event** (plan item C4). Concerts are not collaboratively
 * edited — one person owns every write — so the machinery for anything cleverer would be answering
 * a question nobody is asking. Two devices editing the same show while both offline is the only
 * losing case, and the later sync simply wins.
 */
class OfflineFirstConcertRepository(
    private val local: RoomConcertRepository,
    private val outbox: SyncOutboxDao,
    private val mediaDao: MediaDao,
    private val json: Json,
    private val scheduler: SyncScheduler = NoSyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : ConcertRepository {

    // ---- Reads: straight from Room ----------------------------------------------------

    override fun observeCityPins(): Flow<List<CityPin>> = local.observeCityPins()

    override fun observeVenuePins(cityId: String): Flow<List<VenuePin>> =
        local.observeVenuePins(cityId)

    override fun observeAllVenuePins(): Flow<List<VenuePin>> = local.observeAllVenuePins()

    override fun observeEventsInCity(cityId: String): Flow<List<EventSummary>> =
        local.observeEventsInCity(cityId)

    override fun observeEventsAtVenue(venueId: String): Flow<List<EventSummary>> =
        local.observeEventsAtVenue(venueId)

    override fun observeAllEvents(): Flow<List<EventSummary>> = local.observeAllEvents()

    override fun observeEventsForArtist(artistId: String): Flow<List<EventSummary>> =
        local.observeEventsForArtist(artistId)

    override fun observeArtistSummaries(): Flow<List<ArtistSummary>> = local.observeArtistSummaries()

    override fun observeArtist(artistId: String): Flow<Artist?> = local.observeArtist(artistId)

    override fun observeKnownArtists(): Flow<List<Artist>> = local.observeKnownArtists()

    override fun observeKnownVenues(): Flow<List<Pair<Venue, City>>> = local.observeKnownVenues()

    override fun observeEvent(eventId: String): Flow<ConcertEvent?> = local.observeEvent(eventId)

    /**
     * Artwork lookups stay local and unqueued. They are a cache of somebody else's data, cheap to
     * repeat, and queueing them would fill the outbox with work that has no user-visible effect —
     * the sync's job is the user's own library, not a mirror of MusicBrainz.
     */
    override suspend fun keepArtistArtworkFresh() = local.keepArtistArtworkFresh()

    override suspend fun previewArtistDeletion(artistId: String): DataResult<ArtistDeletionImpact> =
        local.previewArtistDeletion(artistId)

    // ---- Writes: Room now, network later ----------------------------------------------

    override suspend fun saveEvent(draft: EventDraft): DataResult<String> =
        local.saveEvent(draft).onSuccess { eventId ->
            // Queued with the id Room just assigned, so replaying inserts this exact show rather
            // than a second copy of it.
            enqueue(
                operation = SyncOperation.SAVE_EVENT,
                entityId = eventId,
                payload = json.encodeToString(
                    SaveEventPayload.serializer(),
                    SaveEventPayload(
                        eventId = eventId,
                        eventDate = draft.date.toString(),
                        cityName = draft.cityName.trim(),
                        country = draft.country.trim(),
                        region = draft.region?.trim()?.takeIf { it.isNotEmpty() },
                        venueName = draft.venueName.trim(),
                        latitude = draft.latitude,
                        longitude = draft.longitude,
                        address = draft.address?.trim()?.takeIf { it.isNotEmpty() },
                        artistNames = draft.artistNames.map { it.trim() }.filter { it.isNotEmpty() },
                        supportNames = draft.supportArtistNames.map { it.trim() }
                            .filter { it.isNotEmpty() },
                        title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
                        notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                        rating = draft.rating,
                    ),
                ),
                supersedePrevious = true,
            )
        }

    override suspend fun deleteEvent(eventId: String): DataResult<Unit> =
        local.deleteEvent(eventId).onSuccess {
            // Everything queued about this show is now pointless — including, possibly, its own
            // creation. Dropping those first keeps the queue from replaying a show only to delete
            // it a moment later.
            outbox.removeAllFor(eventId)
            enqueue(
                operation = SyncOperation.DELETE_EVENT,
                entityId = eventId,
                payload = json.encodeToString(
                    DeleteEventPayload.serializer(),
                    DeleteEventPayload(eventId),
                ),
            )
        }

    override suspend fun renameArtist(artistId: String, newName: String): DataResult<Unit> {
        // Read before the rename, because afterwards the old spelling is gone and the server has
        // no other way to find the artist being renamed.
        //
        // Failing here rather than renaming anyway is the point. Without the old name there is
        // nothing to queue, so the rename would apply locally, never reach the server, and then be
        // silently undone by the next refresh — which reads as the app forgetting an edit. A
        // refused rename is recoverable; one that quietly reverts an hour later is not.
        val previousName = local.observeArtist(artistId).first()?.name
            ?: return DataResult.Failure(DataError.Validation("That artist no longer exists"))

        return local.renameArtist(artistId, newName).onSuccess {
            enqueue(
                operation = SyncOperation.RENAME_ARTIST,
                entityId = artistId,
                payload = json.encodeToString(
                    RenameArtistPayload.serializer(),
                    RenameArtistPayload(previousName, newName.trim()),
                ),
            )
        }
    }

    override suspend fun deleteArtist(artistId: String): DataResult<Unit> {
        val name = local.observeArtist(artistId).first()?.name
            ?: return DataResult.Failure(DataError.Validation("That artist no longer exists"))

        return local.deleteArtist(artistId).onSuccess {
            enqueue(
                operation = SyncOperation.DELETE_ARTIST,
                entityId = artistId,
                payload = json.encodeToString(
                    DeleteArtistPayload.serializer(),
                    DeleteArtistPayload(name),
                ),
            )
        }
    }

    override suspend fun addMedia(eventId: String, uris: List<Uri>): DataResult<Int> =
        local.addMedia(eventId, uris).onSuccess { added ->
            if (added == 0) return@onSuccess
            // Re-read rather than reconstruct: RoomConcertRepository decided the ids, sort indices
            // and on-disk paths, and the queued rows have to match what Room actually holds or a
            // later refresh would disagree with the files.
            val rows = mediaDao.getMediaForEvent(eventId).map {
                MediaRowPayload(
                    id = it.id,
                    eventId = it.eventId,
                    localPath = it.localPath,
                    originalUri = it.originalUri,
                    mimeType = it.mimeType,
                    capturedAtEpochMillis = it.capturedAt,
                    sortIndex = it.sortIndex,
                )
            }
            enqueue(
                operation = SyncOperation.ADD_MEDIA,
                entityId = eventId,
                payload = json.encodeToString(
                    AddMediaPayload.serializer(),
                    AddMediaPayload(eventId, rows),
                ),
                supersedePrevious = true,
            )
        }

    override suspend fun removeMedia(mediaId: String, localPath: String): DataResult<Unit> =
        local.removeMedia(mediaId, localPath).onSuccess {
            enqueue(
                operation = SyncOperation.REMOVE_MEDIA,
                entityId = mediaId,
                payload = json.encodeToString(
                    RemoveMediaPayload.serializer(),
                    RemoveMediaPayload(mediaId),
                ),
            )
        }

    private suspend fun enqueue(
        operation: SyncOperation,
        entityId: String,
        payload: String,
        supersedePrevious: Boolean = false,
    ) {
        // Each payload is the complete current state, so an earlier entry for the same thing
        // carries nothing the new one doesn't. Editing a show five times offline sends one save.
        if (supersedePrevious) outbox.removeSuperseded(entityId, operation.name)
        outbox.enqueue(
            SyncOutboxEntity(
                operation = operation.name,
                entityId = entityId,
                payloadJson = payload,
                queuedAtEpochMillis = now(),
            ),
        )
        // Asks; does not wait. The request carries a connectivity constraint, so this is a no-op
        // right now when there is no signal and fires by itself the moment there is.
        scheduler.requestSync()
    }
}
