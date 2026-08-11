package dev.abhinav.artistpin.core.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ImportedMedia(
    val localPath: String,
    val originalUri: String,
    val mimeType: String,
    val capturedAt: Long?,
)

/**
 * Copies picked media into app-private storage.
 *
 * The Photo Picker hands back a read grant that expires (it does not survive a device restart,
 * and `takePersistableUriPermission` does not apply to picker URIs). Keeping only the URI would
 * give the user an event whose highlight reel silently breaks weeks later, so each item is
 * copied into a directory this app owns and the original URI is kept for reference only.
 */
class MediaImporter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun import(eventId: String, uris: List<Uri>): List<ImportedMedia> =
        withContext(ioDispatcher) {
            val directory = mediaDirectory(eventId).apply { mkdirs() }
            uris.mapNotNull { uri ->
                coroutineContext.ensureActive()
                runCatching { copyOne(uri, directory) }
                    .onFailure { Log.w(TAG, "Skipping un-importable media $uri", it) }
                    .getOrNull()
            }
        }

    suspend fun delete(localPath: String) = withContext(ioDispatcher) {
        runCatching { File(localPath).delete() }
        Unit
    }

    suspend fun deleteAllForEvent(eventId: String) = withContext(ioDispatcher) {
        runCatching { mediaDirectory(eventId).deleteRecursively() }
        Unit
    }

    private fun copyOne(uri: Uri, directory: File): ImportedMedia {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: DEFAULT_MIME_TYPE
        val destination = File(directory, "${UUID.randomUUID()}.${mimeType.toExtension()}")

        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open $uri")

        return ImportedMedia(
            localPath = destination.absolutePath,
            originalUri = uri.toString(),
            mimeType = mimeType,
            capturedAt = readCapturedAt(uri),
        )
    }

    /** Lets the reel be ordered by when the moment happened rather than when it was picked. */
    private fun readCapturedAt(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATE_TAKEN),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                cursor.getLong(column)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun mediaDirectory(eventId: String) = File(File(context.filesDir, MEDIA_DIR), eventId)

    private fun String.toExtension(): String = substringAfterLast('/', "bin")
        .substringBefore(';')
        .filter { it.isLetterOrDigit() }
        .ifEmpty { "bin" }

    private companion object {
        const val TAG = "MediaImporter"
        const val MEDIA_DIR = "media"
        const val DEFAULT_MIME_TYPE = "image/jpeg"
    }
}
