package dev.abhinav.artistpin.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult

/** Zoom at which the world map swaps city bubbles for individual artist pins (roughly state level). */
const val ARTIST_PIN_ZOOM_THRESHOLD = 7f

/**
 * Loads a pin portrait up front.
 *
 * MarkerComposable rasterizes its content to a bitmap once, and an async image has not resolved
 * by then — the marker would capture the initials placeholder forever. Fetching here means the
 * marker can draw an already-decoded bitmap synchronously, and callers key the marker on the
 * result so it re-rasterizes the moment the portrait arrives.
 */
@Composable
fun rememberPinPortrait(imageUrl: String?): ImageBitmap? {
    val context = LocalPlatformContext.current
    var portrait by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(imageUrl) {
        val url = imageUrl ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(PIN_PORTRAIT_PX)
            // MarkerComposable rasterizes onto a software canvas, which cannot draw a hardware
            // bitmap — Coil's default — and throws rather than degrading. This is mandatory.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        portrait = (result as? SuccessResult)?.image
            ?.let { it as? BitmapImage }
            ?.bitmap
            ?.asImageBitmap()
    }
    return portrait
}

private const val PIN_PORTRAIT_PX = 144

/**
 * A circular artist portrait used as a map marker, with the venue name beneath it. Falls back to
 * the artist's initials when no artwork resolved, so a pin is never blank.
 */
@Composable
fun ArtistPin(
    artistName: String?,
    portrait: ImageBitmap?,
    venueName: String,
    eventCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            val outline = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            if (portrait == null) {
                InitialsCircle(name = artistName, modifier = outline)
            } else {
                Image(
                    bitmap = portrait,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = outline,
                )
            }

            if (eventCount > 1) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = eventCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = venueName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Circular artist portrait. Initials show while loading and if the fetch fails, so a broken or
 * unreachable URL degrades to something readable rather than a blank hole.
 */
@Composable
fun ArtistAvatar(
    name: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val portrait = modifier.size(size).clip(CircleShape)
    if (imageUrl == null) {
        InitialsCircle(name = name, modifier = portrait)
        return
    }
    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = { InitialsCircle(name = name, modifier = Modifier.fillMaxSize()) },
        error = { InitialsCircle(name = name, modifier = Modifier.fillMaxSize()) },
        modifier = portrait,
    )
}

@Composable
fun InitialsCircle(name: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.initials(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun String?.initials(): String {
    if (this.isNullOrBlank()) return "?"
    return trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
}
