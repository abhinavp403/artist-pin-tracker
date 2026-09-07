package dev.abhinav.artistpin.feature.artists

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.designsystem.rememberThumbnailModel
import dev.abhinav.artistpin.core.designsystem.titleCaseWords
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.model.EventSummary
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Artist detail: a full-bleed hero that melts into the page, a stat strip, and every time you saw
 * them as a timeline rather than a list of rows.
 *
 * The screen is dark regardless of the system theme — the hero and the timeline rail are built on
 * a near-black page, and there is no light counterpart in the design.
 */
@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    viewModel: ArtistDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val artist = uiState.artist
    val events = uiState.events
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ArtistsEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                    // Nothing left to show once the artist is gone.
                    ArtistsEffect.Deleted -> onBack()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Pin.Page)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Hero(
                    name = artist?.name.orEmpty(),
                    imageUrl = artist?.imageUrl,
                    genres = artist?.genres.orEmpty(),
                )
            }

            item {
                StatStrip(
                    shows = uiState.timesSeen,
                    cities = events.map { it.cityName }.distinct().size,
                    firstSeen = events.minOfOrNull { it.date },
                )
            }

            artist?.spotifyUrl?.takeIf { it.isNotBlank() }?.let { url ->
                item { SpotifyButton(url) }
            }

            item { SectionHeader(title = "Your history") }

            item {
                Timeline(events = events, onOpenEvent = onOpenEvent)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        BackCircle(onBack = onBack, modifier = Modifier.align(Alignment.TopStart))

        // Mirrors the back circle so the hero keeps its two corners: leave on the left, act on
        // the right. Rename leads, since fixing a name is the common case and deleting is not.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroCircle(
                icon = Icons.Default.Edit,
                contentDescription = "Rename artist",
                onClick = viewModel::onRenameRequested,
            )
            HeroCircle(
                icon = Icons.Default.Delete,
                contentDescription = "Delete artist",
                onClick = viewModel::onDeleteRequested,
                tint = Pin.Destructive,
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (uiState.deleteRequested) {
        DeleteArtistDialog(
            artistName = artist?.name.orEmpty(),
            impact = uiState.deletionImpact,
            onConfirm = viewModel::onDeleteConfirmed,
            onDismiss = viewModel::onDeleteDismissed,
        )
    }

    uiState.renameInput?.let { typed ->
        RenameArtistDialog(
            value = typed,
            canConfirm = uiState.canConfirmRename,
            onValueChange = viewModel::onRenameInputChange,
            onConfirm = viewModel::onRenameConfirmed,
            onDismiss = viewModel::onRenameDismissed,
        )
    }
}

/** Names the exact damage before it happens: which shows survive, and which go with the artist. */
@Composable
private fun DeleteArtistDialog(
    artistName: String,
    impact: ArtistDeletionImpact?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $artistName?") },
        text = {
            Text(
                text = when {
                    impact == null -> "They'll be removed from your history."
                    impact.showsDeleted == 0 ->
                        "They'll be removed from ${impact.showsAffected} show(s). " +
                            "Those shows stay, since other artists were on the bill."
                    else ->
                        "They'll be removed from ${impact.showsAffected} show(s), and " +
                            "${impact.showsDeleted} show(s) with nobody else on the bill will be " +
                            "deleted along with any photos attached to them. This can't be undone."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun RenameArtistDialog(
    value: String,
    canConfirm: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename artist") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Name") },
                    singleLine = true,
                )
                Text(
                    text = "Renaming onto an artist you already have merges the two, so a " +
                        "misspelling folds into the right one and keeps both sets of shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Hero(name: String, imageUrl: String?, genres: List<String>) {
    Box(Modifier.fillMaxWidth().height(352.dp)) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Pin.Card))
        }

        // The scrim darkens the top for the back button and takes the bottom all the way to the
        // page colour, so the hero has no visible seam where it ends.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Pin.Page.copy(alpha = 0.75f),
                        0.32f to Pin.Page.copy(alpha = 0.05f),
                        0.68f to Pin.Page.copy(alpha = 0.55f),
                        1f to Pin.Page,
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
        ) {
            Text(
                text = name,
                color = Pin.OnPage,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                lineHeight = 36.sp,
            )
            if (genres.isNotEmpty()) {
                FlowRowChips(genres = genres, modifier = Modifier.padding(top = 11.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(genres: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        genres.forEach { genre ->
            Text(
                text = genre.titleCaseWords(),
                color = Pin.OnPageStrong,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Pin.Chip)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BackCircle(onBack: () -> Unit, modifier: Modifier = Modifier) {
    HeroCircle(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        onClick = onBack,
        modifier = modifier.padding(start = 14.dp),
    )
}

@Composable
private fun HeroCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Pin.OnBackCircle,
) {
    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp)
            .size(38.dp)
            .clip(CircleShape)
            .background(Pin.BackCircle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun StatStrip(shows: Int, cities: Int, firstSeen: LocalDate?) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .border(width = 0.dp, color = Color.Transparent),
    ) {
        Column(Modifier.weight(1f)) {
            Divider()
            Stat(value = shows.toString(), label = if (shows == 1) "show" else "shows")
            Divider()
        }
        Column(Modifier.weight(1f)) {
            Divider()
            Stat(
                value = cities.toString(),
                label = if (cities == 1) "city" else "cities",
                leadingRule = true,
            )
            Divider()
        }
        Column(Modifier.weight(1f)) {
            Divider()
            Stat(
                value = firstSeen?.let { "'" + it.format(YEAR_SHORT) } ?: "—",
                label = "first seen",
                leadingRule = true,
            )
            Divider()
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pin.Hairline))
}

@Composable
private fun Stat(value: String, label: String, leadingRule: Boolean = false) {
    Row {
        if (leadingRule) {
            Box(Modifier.width(1.dp).height(64.dp).background(Pin.Hairline))
        }
        Column(Modifier.padding(start = if (leadingRule) 16.dp else 0.dp, top = 14.dp, bottom = 14.dp)) {
            Text(
                text = value,
                color = Pin.OnPage,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = label,
                color = Pin.OnPageTertiary,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SpotifyButton(url: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Pin.RadiusButton))
            .background(Pin.SpotifyGreen)
            .clickable {
                // No Spotify app or browser is possible; a missing handler must not crash.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Open in Spotify",
            color = Pin.OnSpotify,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun SectionHeader(title: String, trailing: String? = null, topPadding: Int = 18) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = topPadding.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = Pin.OnPageTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(text = it, color = Pin.OnPageFaint, fontSize = 12.sp)
        }
    }
}

/**
 * Every show for this artist on one rail, newest first, with a marker whenever a long stretch
 * passed between two of them — the gaps are as much a part of the history as the shows.
 */
@Composable
private fun Timeline(events: List<EventSummary>, onOpenEvent: (String) -> Unit) {
    if (events.isEmpty()) return
    Column(Modifier.padding(horizontal = 20.dp)) {
        events.forEachIndexed { index, event ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Rail(isMostRecent = index == 0)
                Column(Modifier.weight(1f)) {
                    TimelineEntry(event = event, onClick = { onOpenEvent(event.id) })
                    val next = events.getOrNull(index + 1)
                    if (next != null) GapMarker(older = next.date, newer = event.date)
                }
            }
        }
    }
}

/** The gutter: a hairline running the height of the entry, with the entry's dot on top of it. */
@Composable
private fun Rail(isMostRecent: Boolean) {
    Box(Modifier.width(26.dp).fillMaxHeight()) {
        Box(
            Modifier
                .padding(start = 5.dp, top = 8.dp)
                .width(1.5.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(listOf(Pin.BorderStrong, Pin.HairlineSoft)),
                ),
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(if (isMostRecent) Pin.Accent else Pin.BorderStrong),
        )
    }
}

@Composable
private fun TimelineEntry(event: EventSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 18.dp),
    ) {
        Text(
            text = event.date.format(TIMELINE_DATE).uppercase(),
            color = Pin.OnPageTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            text = event.displayTitle,
            color = Pin.OnPage,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${event.venueName} · ${event.cityName}",
            color = Pin.OnPageSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        PhotoStrip(event)
    }
}

@Composable
private fun PhotoStrip(event: EventSummary) {
    if (event.mediaCount == 0) {
        Row(
            modifier = Modifier
                .padding(top = 9.dp)
                .clip(RoundedCornerShape(Pin.RadiusChip))
                .border(1.dp, Pin.BorderSoft, RoundedCornerShape(Pin.RadiusChip))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("＋ add photos from that night", color = Pin.OnPageSecondary, fontSize = 11.5.sp)
        }
        return
    }

    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (event.thumbnailPath != null || event.thumbnailRemotePath != null) {
            val model by rememberThumbnailModel(event.thumbnailPath, event.thumbnailRemotePath)
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 64.dp, height = 48.dp)
                    .clip(RoundedCornerShape(Pin.RadiusThumb)),
            )
        }
        val remaining = event.mediaCount - 1
        if (remaining > 0) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 48.dp)
                    .clip(RoundedCornerShape(Pin.RadiusThumb))
                    .border(1.dp, Pin.BorderStrong, RoundedCornerShape(Pin.RadiusThumb)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$remaining", color = Pin.OnPageTertiary, fontSize = 11.sp)
            }
        }
    }
}

/** Only long silences are worth drawing; anything shorter is just the next show. */
@Composable
private fun GapMarker(older: LocalDate, newer: LocalDate) {
    val gap = Period.between(older, newer)
    val months = gap.years * 12 + gap.months
    if (months < MIN_GAP_MONTHS) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = describeGap(gap.years, gap.months),
            color = Pin.OnPageFaint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Pin.HairlineSoft))
    }
}

internal fun describeGap(years: Int, months: Int): String = buildString {
    if (years > 0) append("$years ").append(if (years == 1) "YEAR" else "YEARS")
    if (months > 0) {
        if (isNotEmpty()) append(" ")
        append("$months ").append(if (months == 1) "MONTH" else "MONTHS")
    }
}

private const val MIN_GAP_MONTHS = 6
private val TIMELINE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · EEE", Locale.US)
private val YEAR_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("yy", Locale.US)
