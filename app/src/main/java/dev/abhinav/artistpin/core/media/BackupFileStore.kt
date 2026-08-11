package dev.abhinav.artistpin.core.media

import android.content.Context
import android.net.Uri
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/** Reads and writes the backup file the user picked through the system document picker. */
class BackupFileStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun write(uri: Uri, content: String): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingFile {
            // "wt" truncates: overwriting a longer previous backup would otherwise leave a tail
            // of old JSON behind and produce an unparseable file.
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(content.toByteArray())
            } ?: throw IOException("Could not open $uri for writing")
        }
    }

    suspend fun read(uri: Uri): DataResult<String> = withContext(ioDispatcher) {
        runCatchingFile {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().decodeToString()
            } ?: throw IOException("Could not open $uri for reading")
        }
    }

    private inline fun <T> runCatchingFile(block: () -> T): DataResult<T> = try {
        DataResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DataResult.Failure(DataError.MediaUnavailable)
    }
}
