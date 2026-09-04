package dev.abhinav.artistpin.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import dev.abhinav.artistpin.core.media.MediaUrlResolver
import dev.abhinav.artistpin.core.model.EventMedia
import org.koin.compose.koinInject

/**
 * What to hand Coil or ExoPlayer for one media item — a `File` when the bytes are on this device, a
 * signed URL string when they are only in the cloud, and null while that is still being decided or
 * when neither exists.
 *
 * Resolved in a composable rather than folded into the model because signing a URL is a suspending
 * network call, and a list of twenty photos must not block laying itself out on twenty round trips.
 * Local files resolve on the first frame with no suspension at all, which is the overwhelmingly
 * common case.
 */
@Composable
fun rememberMediaModel(item: EventMedia): State<Any?> {
    val resolver: MediaUrlResolver = koinInject()
    // Starts null and resolves off the composition. Checking the file here instead would put a
    // stat() syscall in the layout pass, once per item per recomposition — unbounded disk work on
    // the UI thread while the grid scrolls. The cost is one frame of placeholder.
    return produceState<Any?>(initialValue = null, item.id, item.localPath, item.remotePath) {
        value = resolver.resolve(item)
    }
}
