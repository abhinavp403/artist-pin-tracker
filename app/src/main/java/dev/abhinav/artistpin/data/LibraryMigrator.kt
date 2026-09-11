package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.backend.redactedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val media: Int,
) {
    val nothingToDo: Boolean get() = imported == 0 && skipped == 0
}

/**
 * Moves this device's Room library into the signed-in account, once (execution plan B7).
 *
 * The payload is the app's own backup JSON, unchanged — `BackupRepository.export()` already
 * produces every row with its foreign keys intact, which is exactly what an import needs. Reusing
 * it means there is no second serialisation format to keep in step with the schema, and the file
 * you can already write from the overflow menu is the same thing being sent here.
 *
 * **This runs while Room is still the bound repository.** That ordering is the point: the local
 * library has to be readable in order to be uploaded, so the import happens first and the
 * `USE_BACKEND` flag is flipped afterwards. Doing it the other way round would mean flipping to an
 * empty account and then having no way to read the data you were trying to migrate.
 */
class LibraryMigrator(
    private val backupRepository: RoomBackupRepository,
    private val api: BackendApi,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun uploadLocalLibrary(): DataResult<ImportSummary> = withContext(ioDispatcher) {
        try {
            val exported = when (val result = backupRepository.export()) {
                is DataResult.Failure -> return@withContext DataResult.Failure(result.error)
                is DataResult.Success -> result.data
            }

            // Re-parsed into a JsonObject rather than posted as a string: the RPC parameter is
            // jsonb, and handing Postgres a JSON-encoded string would nest the whole payload
            // inside a single scalar instead of an object it can walk.
            val payload = json.parseToJsonElement(exported) as? JsonObject
                ?: return@withContext DataResult.Failure(
                    DataError.Validation("Could not read this device's library"),
                )

            val result = api.importBackup(payload)
            DataResult.Success(
                ImportSummary(
                    imported = result.eventsImported,
                    skipped = result.eventsSkipped,
                    media = result.mediaImported,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DataResult.Failure(DataError.Network(e.message))
        } catch (e: Exception) {
            // Shown in a snackbar, so it must not carry the request's bearer token.
            DataResult.Failure(DataError.Unknown(e.redactedMessage()))
        }
    }

    /** Rows this device holds locally, so the confirmation can say what is about to be sent. */
    suspend fun localSummary(): DataResult<BackupData> = when (val result = backupRepository.export()) {
        is DataResult.Failure -> DataResult.Failure(result.error)
        is DataResult.Success -> backupRepository.parse(result.data)
    }
}
