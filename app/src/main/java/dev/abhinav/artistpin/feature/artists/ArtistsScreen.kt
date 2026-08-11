package dev.abhinav.artistpin.feature.artists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.abhinav.artistpin.R
import dev.abhinav.artistpin.core.designsystem.ArtistAvatar
import dev.abhinav.artistpin.core.designsystem.EmptyState
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.model.ArtistSummary
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.roundToInt

/**
 * The Artists tab, rendered as a sheet over the map rather than as its own screen — the dock at
 * the bottom of the home screen stays put while the body behind it switches.
 *
 * The list is padded clear of the top chrome and the dock, both of which float above it.
 */
@Stable
class ArtistsListState(val list: LazyListState) {
    /**
     * The ordering the list was last scrolled to the top for. Held here rather than derived from
     * an effect: re-entering composition on the way back from an artist is not a new ordering,
     * and treating it as one threw away the position you left.
     */
    var scrolledForSort: ArtistSort? = null
}

@Composable
fun rememberArtistsListState(): ArtistsListState {
    val list = rememberLazyListState()
    return remember(list) { ArtistsListState(list) }
}

@Composable
fun ArtistsSheet(
    viewModel: ArtistsViewModel,
    listState: ArtistsListState,
    onOpenArtist: (String) -> Unit,
    onMessage: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Rename and delete failures have to reach the host screen's snackbar — this sheet has none
    // of its own now that it lives inside the home screen.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ArtistsEffect.ShowMessage -> onMessage(effect.message)
                    // Only the detail screen deletes, and it closes itself when it does.
                    ArtistsEffect.Deleted -> Unit
                }
            }
        }
    }

    Box(modifier.background(Pin.Sheet)) {
        if (uiState.isEmpty) {
            EmptyState(
                icon = Icons.Default.LibraryMusic,
                title = "No artists yet",
                message = "Every DJ and artist from the shows you add will be collected here.",
            )
        } else {
            val list = listState.list
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current

            // The drag, not "is scrolling": Compose scrolls a newly focused field into view, and
            // reacting to that would dismiss the keyboard the tap had just opened.
            LaunchedEffect(list) {
                list.interactionSource.interactions
                    .filterIsInstance<DragInteraction.Start>()
                    .collect {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
            }

            // The search field and the sort label are chrome, not list content: pinning them
            // means the list scrolls under a header that stays put.
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 96.dp)) {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        label = { Text("Search artists") },
                        singleLine = true,
                        trailingIcon = {
                            // Clearing a query by holding backspace is nobody's idea of a good time.
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SortHeader(
                            sort = uiState.sort,
                            menuOpen = uiState.showSortMenu,
                            onOpen = viewModel::onSortMenuOpen,
                            onDismiss = viewModel::onSortMenuDismiss,
                            onSelect = viewModel::onSortSelected,
                            modifier = Modifier.weight(1f),
                        )
                        // While searching this counts what survived the filter, which is the
                        // number you actually want to know.
                        Text(
                            text = pluralStringResource(
                                R.plurals.artist_count,
                                uiState.visibleArtists.size,
                                uiState.visibleArtists.size,
                            ),
                            fontSize = 12.5.sp,
                            color = Pin.OnChromeTertiary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }

                // A new ordering is a new list; carrying the old scroll position into it drops
                // you into the middle of a grouping you haven't seen the top of. Returning from
                // an artist is not a new ordering, so it keeps its place.
                LaunchedEffect(uiState.sort) {
                    if (listState.scrolledForSort != uiState.sort) {
                        if (listState.scrolledForSort != null) list.scrollToItem(0)
                        listState.scrolledForSort = uiState.sort
                    }
                }

                // Fades in while the list moves and away once it settles, so a long library gets
                // a sense of position without a rail parked over the rows the whole time.
                val scrollbarAlpha by animateFloatAsState(
                    targetValue = if (list.isScrollInProgress) 1f else 0f,
                    animationSpec = tween(if (list.isScrollInProgress) 120 else 600),
                    label = "scrollbar",
                )

                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollbar(state = list, alpha = scrollbarAlpha, color = Pin.OnChromeTertiary),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 150.dp),
                ) {
                    uiState.sections.forEach { section ->
                        section.title?.let { title ->
                            stickyHeader(key = "header-$title") { SectionHeading(title) }
                        }
                        items(section.artists, key = { it.artist.id }) { summary ->
                            ArtistRow(
                                summary = summary,
                                onClick = { onOpenArtist(summary.artist.id) },
                            )
                            HorizontalDivider(color = Pin.HairlineChrome)
                        }
                    }
                }
            }
        }
    }

}

/**
 * A thumb down the right edge, sized to the share of the list on screen and positioned by how far
 * through it you are.
 *
 * Measured in items rather than pixels — rows here are a fixed height, and the alternative means
 * measuring every row that hasn't been composed yet. The track stops short of the bottom so the
 * thumb never slides underneath the dock floating over the list.
 */
private fun Modifier.scrollbar(
    state: LazyListState,
    alpha: Float,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    if (alpha <= 0f) return@drawWithContent

    val info = state.layoutInfo
    val total = info.totalItemsCount
    val onScreen = info.visibleItemsInfo.size
    // Nothing to scroll, nothing to say.
    if (total == 0 || onScreen == 0 || onScreen >= total) return@drawWithContent

    val trackHeight = size.height - DOCK_CLEARANCE.toPx()
    if (trackHeight <= 0f) return@drawWithContent

    val thumbHeight = (trackHeight * onScreen / total).coerceAtLeast(MIN_THUMB.toPx())
    val progress = state.firstVisibleItemIndex.toFloat() / (total - onScreen)
    val thumbTop = (trackHeight - thumbHeight) * progress.coerceIn(0f, 1f)
    val width = SCROLLBAR_WIDTH.toPx()

    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(size.width - width, thumbTop),
        size = Size(width, thumbHeight),
        cornerRadius = CornerRadius(width / 2),
    )
}

private val SCROLLBAR_WIDTH = 3.dp
private val MIN_THUMB = 32.dp

/** The dock floats over the last stretch of the list; the thumb stays clear of it. */
private val DOCK_CLEARANCE = 150.dp

/** The heading a run of artists belongs to — a year, or a city. Sticks while its run scrolls. */
@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = Pin.OnChromeSecondary,
        modifier = Modifier
            .fillMaxWidth()
            // Opaque, or the rows scrolling underneath show through the sticky heading.
            .background(Pin.Sheet)
            .padding(top = 10.dp, bottom = 6.dp),
    )
}

/** The sort label doubles as the sort control — the label always names the current order. */
@Composable
private fun SortHeader(
    sort: ArtistSort,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (ArtistSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = sort.label.uppercase(),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = Pin.OnChromeTertiary,
            )
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = "Sort artists",
                tint = Pin.OnChromeTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
            ArtistSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option) },
                    leadingIcon = {
                        if (option == sort) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(summary: ArtistSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Pin.Sheet)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        ArtistAvatar(name = summary.artist.name, imageUrl = summary.artist.imageUrl, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = summary.artist.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Pin.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.meta(),
                fontSize = 12.5.sp,
                color = Pin.OnChromeSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistSummary.meta(): String {
    val shows = pluralStringResource(R.plurals.show_count, timesSeen, timesSeen)
    val where = cityNames.take(2).joinToString(", ")
    return if (where.isBlank()) shows else "$shows · $where"
}


