package dev.abhinav.artistpin.feature.eventdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.abhinav.artistpin.core.designsystem.rememberMediaModel
import dev.abhinav.artistpin.core.model.EventMedia
import kotlinx.coroutines.delay
import java.io.File

private const val REEL_IMAGE_DURATION_MS = 3_000L

@Composable
fun MediaViewerDialog(
    media: List<EventMedia>,
    startIndex: Int,
    isReelPlaying: Boolean,
    onClose: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (media.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, media.lastIndex),
        pageCount = { media.size },
    )

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = media[page]
                if (item.isVideo) {
                    VideoPage(item = item, isActive = page == pagerState.currentPage)
                } else {
                    val model by rememberMediaModel(item)
                    AsyncImage(
                        model = model,
                        contentDescription = "Photo ${page + 1} of ${media.size}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Images hold for a fixed beat; videos advance only when they finish, which is
            // handled by VideoPage signalling through the same pager.
            if (isReelPlaying) {
                LaunchedEffect(pagerState.currentPage, media.size) {
                    val current = media[pagerState.currentPage]
                    if (!current.isVideo) {
                        delay(REEL_IMAGE_DURATION_MS)
                        val next = pagerState.currentPage + 1
                        if (next < media.size) pagerState.animateScrollToPage(next) else onTogglePlayback()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${media.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePlayback) {
                    Icon(
                        imageVector = if (isReelPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isReelPlaying) "Pause reel" else "Play reel",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { onRemove(media[pagerState.currentPage].id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove from reel", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoPage(item: EventMedia, isActive: Boolean) {
    val context = LocalContext.current
    // Resolved the same way photos are. Reading the local file directly meant a video whose bytes
    // live only in object storage — a second device, or a reinstall — handed ExoPlayer a file:// URI
    // for a path that does not exist, and simply failed to play while the photos beside it loaded.
    val source by rememberMediaModel(item)

    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    LaunchedEffect(player, source) {
        val uri = when (val resolved = source) {
            is File -> resolved.toURI().toString()
            is String -> resolved
            // Still resolving, or the video exists nowhere reachable. Nothing to prepare.
            else -> return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(isActive) {
        player.playWhenReady = isActive
        if (!isActive) player.seekTo(0)
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = true } },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
