package dev.abhinav.artistpin.data.sync

import android.util.Log
import dev.abhinav.artistpin.core.database.SyncOutboxDao
import dev.abhinav.artistpin.core.database.SyncOutboxEntity
import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.RoomBackupRepository
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.backend.EventMediaDto
import dev.abhinav.artistpin.data.backend.SaveEventParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant

data class SyncResult(
    val pushed: Int,
    val stillPending: Int,
    val pulled: Boolean,
    /** Entries abandoned after [LibrarySync.MAX_ATTEMPTS] failures. Non-zero means data was lost. */
    val dropped: Int = 0,
) {
    val isFullySynced: Boolean get() = stillPending == 0
}

/**
 * Pushes the outbox, then pulls the library back down.
 *
 * **Push** replays each queued change through the same RPCs the online build used. **Pull** is a
 * whole-library refresh — `export_backup` into the existing restore path — rather than an
 * incremental merge.
 *
 * That choice is the reason this milestone is tractable. An incremental pull would have to
 * reconcile identities: a venue created offline has a Room id the shared catalog has never seen,
 * so merging server rows into local ones needs an id-mapping table, tombstones for deletions, and
 * per-row merge rules. A full refresh sidesteps all of it — after the pull, Room holds the
 * *server's* ids for every catalog row, so local and remote agree by construction. It costs a
 * whole-library download, which at this size is a fraction of a second, and would need revisiting
 * somewhere in the thousands of shows.
 *
 * **A pull only ever runs with an empty outbox.** Refreshing while changes are queued would
 * overwrite them with the server's older copy — the local rows would be replaced by rows that do
 * not yet reflect the queued writes, and the outbox would then replay against a library that had
 * silently moved. Push first, always.
 */
class LibrarySync(
    private val outbox: SyncOutboxDao,
    private val api: BackendApi,
    private val localBackup: RoomBackupRepository,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** How many changes are waiting, for the indicator in the dock (plan item C5). */
    val pendingCount: Flow<Int> = outbox.observePendingCount()

    suspend fun syncNow(): DataResult<SyncResult> = withContext(ioDispatcher) {
        try {
            val (pushed, dropped) = drain()
            val remaining = outbox.pendingCount()

            // Only with nothing queued. See the class comment: pulling over unsent work loses it.
            val pulled = if (remaining == 0) {
                refreshFromServer()
                true
            } else {
                Log.d(TAG, "Skipping pull, $remaining change(s) still queued")
                false
            }

            DataResult.Success(SyncResult(pushed, remaining, pulled, dropped))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DataResult.Failure(DataError.Network(e.message))
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            DataResult.Failure(DataError.Unknown(e.message))
        }
    }

    /**
     * Sends queued changes oldest first, stopping at the first failure.
     *
     * Stopping rather than skipping is deliberate: the queue is ordered, and later entries assume
     * earlier ones landed. Pushing an edit to a show whose creation has not been sent would be
     * rejected, and pushing a delete past a failed create would leave a row on the server that
     * nothing locally remembers.
     */
    private suspend fun drain(): Pair<Int, Int> {
        var pushed = 0
        var dropped = 0
        for (entry in outbox.pending()) {
            val sent = runCatching { send(entry) }
            if (sent.isSuccess) {
                outbox.remove(entry.id)
                pushed++
                continue
            }

            val cause = sent.exceptionOrNull()
            if (cause is CancellationException) throw cause
            Log.w(TAG, "Could not push ${entry.operation} for ${entry.entityId}", cause)
            outbox.recordFailure(entry.id, cause?.message)

            // The escape hatch. Without it, one entry that can never succeed — a delete the server
            // rejects, a payload a newer build wrote — blocks every later change forever, while the
            // indicator keeps counting up and nothing ever moves. Being offline is not this: those
            // attempts fail before a request is made and the entry is retried indefinitely, which
            // is the point of a queue. This only fires for something that has genuinely been tried
            // and refused, repeatedly.
            if (entry.attempts + 1 >= MAX_ATTEMPTS) {
                Log.w(
                    TAG,
                    "Abandoning ${entry.operation} for ${entry.entityId} after " +
                        "${entry.attempts + 1} attempts: ${cause?.message}",
                )
                outbox.remove(entry.id)
                dropped++
                // Deliberately continues rather than breaking: this entry is gone, so the ordering
                // it was holding up no longer exists, and the changes behind it deserve their turn.
                continue
            }

            // Ordinary failure — stop here. The queue is ordered and later entries assume earlier
            // ones landed, so skipping past a still-retryable entry would apply changes out of
            // sequence.
            break
        }
        return pushed to dropped
    }

    private suspend fun send(entry: SyncOutboxEntity) {
        when (SyncOperation.valueOf(entry.operation)) {
            SyncOperation.SAVE_EVENT -> {
                val p = json.decodeFromString(SaveEventPayload.serializer(), entry.payloadJson)
                api.saveEvent(
                    SaveEventParams(
                        eventId = p.eventId,
                        eventDate = p.eventDate,
                        cityName = p.cityName,
                        country = p.country,
                        region = p.region,
                        venueName = p.venueName,
                        latitude = p.latitude,
                        longitude = p.longitude,
                        address = p.address,
                        artistNames = p.artistNames,
                        supportNames = p.supportNames,
                        title = p.title,
                        notes = p.notes,
                        rating = p.rating,
                    ),
                )
            }

            SyncOperation.DELETE_EVENT -> {
                val p = json.decodeFromString(DeleteEventPayload.serializer(), entry.payloadJson)
                api.deleteEvent(p.eventId)
            }

            SyncOperation.RENAME_ARTIST -> {
                val p = json.decodeFromString(RenameArtistPayload.serializer(), entry.payloadJson)
                // find-or-create resolves the old spelling to the id the catalog knows; the local
                // id in the outbox row means nothing here.
                val serverId = api.findOrCreateArtist(p.previousName)
                api.renameArtist(serverId, p.newName)
            }

            SyncOperation.DELETE_ARTIST -> {
                val p = json.decodeFromString(DeleteArtistPayload.serializer(), entry.payloadJson)
                api.deleteArtist(api.findOrCreateArtist(p.artistName))
            }

            SyncOperation.ADD_MEDIA -> {
                val p = json.decodeFromString(AddMediaPayload.serializer(), entry.payloadJson)
                api.addMedia(
                    p.rows.map {
                        EventMediaDto(
                            id = it.id,
                            eventId = it.eventId,
                            localPath = it.localPath,
                            originalUri = it.originalUri,
                            mimeType = it.mimeType,
                            capturedAt = it.capturedAtEpochMillis
                                ?.let { millis -> Instant.ofEpochMilli(millis).toString() },
                            sortIndex = it.sortIndex,
                        )
                    },
                )
            }

            SyncOperation.REMOVE_MEDIA -> {
                val p = json.decodeFromString(RemoveMediaPayload.serializer(), entry.payloadJson)
                api.deleteMedia(p.mediaId)
            }
        }
    }

    /**
     * Replaces the local library with the server's.
     *
     * Safe only because the outbox is empty by the time this runs, so there is nothing local that
     * the server does not already know. Photo *files* are untouched — restore drops media rows
     * whose file is missing, so a refresh on a phone that never held them silently discards those
     * rows rather than leaving broken thumbnails.
     */
    private suspend fun refreshFromServer() {
        val payload = api.exportBackup()
        val backup = json.decodeFromJsonElement(BackupData.serializer(), payload)
        when (val restored = localBackup.restore(backup)) {
            is DataResult.Failure -> throw IllegalStateException("Refresh failed: ${restored.error}")
            is DataResult.Success -> Log.d(TAG, "Pulled ${restored.data.applied} shows")
        }
    }

    companion object {
        private const val TAG = "ArtistPinSync"

        /**
         * How many refusals before an entry is abandoned. Generous, because the cost of dropping a
         * real change is losing the user's data, whereas the cost of retrying a doomed one is a few
         * more failed requests.
         */
        const val MAX_ATTEMPTS = 5
    }
}
