package dev.abhinav.artistpin.core.media

import android.util.Log
import dev.abhinav.artistpin.core.model.EventMedia
import dev.abhinav.artistpin.data.backend.BackendApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a media row into something Coil or ExoPlayer can load.
 *
 * **Local first, always.** The file on this phone is free, instant and works with no signal, so a
 * photo taken on this device never becomes a network request just because a copy also exists in the
 * cloud. The remote copy is what makes the photo survive the phone — not what serves it day to day.
 *
 * A signed URL is minted only when the local file is missing: a fresh install, a second device, or
 * a library restored from a backup that carried the rows but not the bytes.
 */
class MediaUrlResolver(
    private val api: BackendApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class SignedUrl(val url: String, val expiresAtMillis: Long)

    private val cache = mutableMapOf<String, SignedUrl>()
    private val lock = Mutex()

    /**
     * Returns a `File` when the bytes are on this device, a URL string when they are not, and null
     * when neither is available — a photo added on another phone and not yet uploaded, which the UI
     * renders as a placeholder rather than a broken image.
     */
    suspend fun resolve(media: EventMedia): Any? = withContext(ioDispatcher) {
        // exists() is a disk hit, so it belongs off the caller's thread — this is reached from
        // composition, once per media item.
        val local = File(media.localPath)
        if (media.localPath.isNotBlank() && local.exists()) return@withContext local

        val path = media.remotePath ?: return@withContext null
        signedUrl(path)
    }

    /** [resolve] for a list thumbnail, which has paths rather than a whole media row. */
    suspend fun resolveThumbnail(localPath: String?, remotePath: String?): Any? =
        withContext(ioDispatcher) {
            val local = localPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (local != null && local.exists()) return@withContext local
            remotePath?.let { signedUrl(it) }
        }

    suspend fun signedUrl(path: String): String? = withContext(ioDispatcher) {
        cached(path)?.let { return@withContext it }

        // Signed *outside* the lock. Holding it across the round trip serialised every distinct
        // path behind the one in flight, so a grid of twenty uncached photos was twenty sequential
        // requests filling in one cell at a time. Two callers racing the same path both sign it,
        // which costs one redundant request and is far cheaper than the queue it replaces.
        val signed = runCatching { api.signedMediaUrl(path, EXPIRY_SECONDS) }
            .onFailure { Log.w(TAG, "Could not sign $path", it) }
            .getOrNull()
            ?: return@withContext null

        lock.withLock { cache[path] = SignedUrl(signed, now() + EXPIRY_SECONDS * 1000) }
        signed
    }

    /** Re-used until close to expiry, so scrolling a grid is not one round trip per cell. */
    private suspend fun cached(path: String): String? = lock.withLock {
        cache[path]?.takeIf { it.expiresAtMillis > now() + REFRESH_MARGIN_MILLIS }?.url
    }

    private companion object {
        const val TAG = "ArtistPinMedia"

        /**
         * An hour. Long enough that browsing a show never re-signs, short enough that a URL which
         * escapes — into a log, a screenshot, a crash report — stops working the same afternoon.
         * That expiry is the entire reason the bucket is private rather than public.
         */
        const val EXPIRY_SECONDS = 3_600L

        /** Re-signs a few minutes early, so a URL cannot expire midway through loading. */
        const val REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}
