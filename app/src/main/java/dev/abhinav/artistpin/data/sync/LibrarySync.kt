package dev.abhinav.artistpin.data.sync

import android.util.Log
import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.AuthState
import dev.abhinav.artistpin.core.database.MediaDao
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
import java.io.File
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
    private val mediaDao: MediaDao,
    private val auth: AuthRepository,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * Asked to try again whenever a run ends incomplete. Defaults to a no-op so tests and the
     * device-only build need not care.
     */
    private val scheduler: SyncScheduler = NoSyncScheduler,
) {

    /** How many changes are waiting, for the indicator in the dock (plan item C5). */
    val pendingCount: Flow<Int> = outbox.observePendingCount()

    suspend fun syncNow(): DataResult<SyncResult> = withContext(ioDispatcher) {
        try {
            queuePendingUploads()
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

            // Anything still queued deserves another attempt on a connection we do not have yet.
            if (remaining > 0) scheduler.requestSync()

            DataResult.Success(SyncResult(pushed, remaining, pulled, dropped))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The case this exists for: the very first sync after signing in on a new phone. That
            // run is a pure pull with nothing queued, so no write ever asks the scheduler for it —
            // and if it fails on a flaky connection the app simply sits empty until the user
            // happens to reopen it. Asking here means WorkManager waits for real connectivity and
            // finishes the job on its own.
            scheduler.requestSync()
            DataResult.Failure(DataError.Network(e.message))
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.redacted()}")
            scheduler.requestSync()
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
            // Redacted, not raw. supabase-kt puts the full request headers into its exception
            // message, which includes the session's bearer token — logging the exception verbatim
            // writes a live credential to logcat, and recordFailure would store it in SQLite.
            val reason = cause.redacted()
            Log.w(TAG, "Could not push ${entry.operation} for ${entry.entityId}: $reason")
            outbox.recordFailure(entry.id, reason)

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
                        "${entry.attempts + 1} attempts: $reason",
                )
                // An abandoned upload has to be recorded on the media row as well. Without it the
                // backfill regenerates this exact entry on the next run, and the escape hatch above
                // never actually escapes.
                if (entry.operation == SyncOperation.UPLOAD_MEDIA.name) {
                    mediaDao.recordUploadFailure(entry.entityId)
                }
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

            SyncOperation.UPLOAD_MEDIA -> {
                val p = json.decodeFromString(UploadMediaPayload.serializer(), entry.payloadJson)
                uploadOne(p)
            }

            SyncOperation.DELETE_STORED_MEDIA -> {
                val p = json.decodeFromString(
                    DeleteStoredMediaPayload.serializer(),
                    entry.payloadJson,
                )
                api.deleteStoredMedia(p.paths)
            }
        }
    }

    /**
     * Sends one photo's bytes, then records where they landed — locally and on the server.
     *
     * Order matters. The upload happens first, and only a successful upload writes the path, so an
     * interrupted attempt leaves the row exactly as it was and the queue tries again. Writing the
     * path first would mark a photo as backed up when it is not, which is the one lie this feature
     * cannot afford to tell.
     */
    private suspend fun uploadOne(payload: UploadMediaPayload) {
        val file = File(payload.localPath)
        if (!file.exists()) {
            // The file is gone — deleted outside the app, or cleared with the app's storage. There
            // is nothing to upload and never will be, so the row is retired from the queue rather
            // than merely skipped: leaving it un-uploaded would have the backfill queue it again on
            // the very next sync, and every sync after that, forever.
            Log.w(TAG, "Nothing to upload for ${payload.mediaId}: ${payload.localPath} is gone")
            mediaDao.abandonUpload(payload.mediaId, MAX_UPLOAD_ATTEMPTS)
            return
        }

        if (payload.mimeType.startsWith("video/")) {
            // Videos are never uploaded. The backfill query already excludes them, so this only
            // catches entries queued before that rule existed — retiring them rather than letting
            // them fail against the size limit fifteen times over.
            Log.d(TAG, "Videos stay on the device: ${payload.mediaId}")
            mediaDao.abandonUpload(payload.mediaId, MAX_UPLOAD_ATTEMPTS)
            return
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            // The bucket caps objects at 50 MB, which is also the ceiling on Supabase's free plan,
            // so this cannot be raised — a longer video simply has nowhere to go. Retiring it here
            // rather than letting the server refuse it means one clear decision instead of fifteen
            // failed round trips with the file's bytes sent each time.
            Log.w(
                TAG,
                "Too large to upload (${file.length() / 1_048_576} MB): ${payload.mediaId}",
            )
            mediaDao.abandonUpload(payload.mediaId, MAX_UPLOAD_ATTEMPTS)
            return
        }

        val userId = (auth.state.value as? AuthState.SignedIn)?.userId
            ?: throw IllegalStateException("Not signed in; cannot upload media")

        // The first segment is what the storage policies read ownership from, so it is not
        // cosmetic — see 0011_media_storage.sql.
        val path = "$userId/${payload.eventId}/${payload.mediaId}${file.extension.dotted()}"

        api.uploadMedia(path, file.readBytes(), payload.mimeType)
        api.setMediaStoragePath(payload.mediaId, path)
        mediaDao.setRemotePath(payload.mediaId, path)
    }

    private fun String.dotted(): String = if (isBlank()) "" else ".$this"

    /**
     * A loggable one-liner with credentials stripped.
     *
     * supabase-kt's REST exceptions carry the whole request — including `Authorization: Bearer …` —
     * in their message. That message reaches logcat and the outbox's lastError column, so it has to
     * be cleaned before either.
     */
    private fun Throwable?.redacted(): String {
        val raw = this?.message ?: return this?.javaClass?.simpleName ?: "unknown"
        return raw.substringBefore("Headers:")
            .replace(Regex("(?i)(bearer|apikey=?\\[?)\\s*[A-Za-z0-9._\\-]+"), "$1 <redacted>")
            .trim()
            .take(300)
    }

    /**
     * Queues uploads for photos whose bytes have never left the phone (plan item D4).
     *
     * Deliberately not a one-off migration. Expressing the backfill as "anything without a storage
     * path" means the same code covers the photos imported before Milestone D, a photo added while
     * offline, and an upload that failed three weeks ago — rather than three mechanisms that each
     * have to be remembered.
     */
    private suspend fun queuePendingUploads() {
        val pending = mediaDao.awaitingUpload(UPLOAD_BATCH, MAX_UPLOAD_ATTEMPTS)
        for (media in pending) {
            if (outbox.countFor(media.id, SyncOperation.UPLOAD_MEDIA.name) > 0) continue
            outbox.enqueue(
                SyncOutboxEntity(
                    operation = SyncOperation.UPLOAD_MEDIA.name,
                    entityId = media.id,
                    payloadJson = json.encodeToString(
                        UploadMediaPayload.serializer(),
                        UploadMediaPayload(
                            mediaId = media.id,
                            eventId = media.eventId,
                            localPath = media.localPath,
                            mimeType = media.mimeType,
                        ),
                    ),
                    queuedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        if (pending.isNotEmpty()) Log.d(TAG, "Queued ${pending.size} photo(s) for upload")
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

        /**
         * Photos queued per sync run. A library migrated from years of shows can hold hundreds, and
         * queueing them all at once would make one run responsible for the whole upload — slow,
         * failure-prone, and invisible while it happens. A batch per run drains steadily instead.
         */
        const val UPLOAD_BATCH = 20

        /**
         * Failed uploads before a photo stops being queued. Separate from [MAX_ATTEMPTS], which
         * counts one queue entry's failures — this counts how many times the photo has been given
         * up on across runs, which is the number the backfill has to respect.
         */
        const val MAX_UPLOAD_ATTEMPTS = 3

        /** Matches the bucket's file_size_limit in 0011, which is also the free plan's ceiling. */
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
    }
}
