package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.backend.BackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Backup to and from a file, against whichever store is bound.
 *
 * Extracted so the escape hatch survives the cutover. Losing file backup at the exact moment the
 * data stops living on your own device is backwards — that is when an off-device copy is worth
 * most, not least.
 *
 * One file format either way. A file written by the Room build restores into the backend, and one
 * written by the backend build restores into Room, because `export_backup()` in
 * `0008_export.sql` emits the same JSON `RoomBackupRepository` does.
 */
/**
 * What a restore actually did — as opposed to what the file contained.
 *
 * The distinction is not pedantic: the merging implementation routinely applies none of a file
 * because everything in it is already present, and reporting the file's contents in that case tells
 * the user a restore happened when nothing changed.
 */
data class RestoreSummary(val applied: Int, val skipped: Int)

interface LibraryBackup {

    suspend fun export(): DataResult<String>

    /** Parsed separately from restoring, so the UI can state what a file holds before acting. */
    suspend fun parse(contents: String): DataResult<BackupData>

    suspend fun restore(backup: BackupData): DataResult<RestoreSummary>

    /**
     * Whether [restore] replaces the library or merges into it. The two implementations genuinely
     * differ, and the confirmation dialog has to say which one is about to happen — "this will
     * replace your 53 shows" is a lie if it merges, and a restore that silently wipes when the
     * user expected a repair is worse.
     */
    val restoreReplaces: Boolean
}

/**
 * Backend-backed backup.
 *
 * Restore **merges** rather than replaces, and that is a deliberate difference from the Room
 * implementation rather than an incomplete port. Events keep their ids through both export and
 * import, so restoring a file taken from this same account is a repair: rows that went missing
 * come back, rows still present are skipped, and nothing is destroyed to achieve it. Implementing
 * replace would mean deleting the account's shows first — turning the fail-safe into the most
 * dangerous button in the app, and the one most likely to be pressed in a panic.
 */
class BackendBackupRepository(
    private val api: BackendApi,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryBackup {

    override val restoreReplaces: Boolean = false

    override suspend fun export(): DataResult<String> = runCatchingBackup {
        json.encodeToString(JsonObject.serializer(), api.exportBackup())
    }

    override suspend fun parse(contents: String): DataResult<BackupData> = runCatchingBackup {
        val backup = json.decodeFromString(BackupData.serializer(), contents)
        require(backup.version <= BackupData.CURRENT_VERSION) {
            "That backup was made by a newer version of Artist Pin"
        }
        backup
    }

    override suspend fun restore(backup: BackupData): DataResult<RestoreSummary> =
        runCatchingBackup {
            // Re-encoded to a JsonObject rather than passed as a string: the RPC parameter is
            // jsonb, and a JSON-encoded string would nest the whole library inside one scalar.
            val payload = json.encodeToJsonElement(BackupData.serializer(), backup) as JsonObject
            val result = api.importBackup(payload)
            // The server's counts, not the file's. Restoring a backup of a library that is already
            // intact applies nothing, and saying so is the honest answer.
            RestoreSummary(applied = result.eventsImported, skipped = result.eventsSkipped)
        }

    private suspend fun <T> runCatchingBackup(block: suspend () -> T): DataResult<T> =
        withContext(ioDispatcher) {
            try {
                DataResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                DataResult.Failure(DataError.Validation(e.message ?: "That file isn't a valid backup"))
            } catch (e: IOException) {
                DataResult.Failure(DataError.Network(e.message))
            } catch (e: Exception) {
                DataResult.Failure(DataError.Validation("That file isn't a valid Artist Pin backup"))
            }
        }
}
