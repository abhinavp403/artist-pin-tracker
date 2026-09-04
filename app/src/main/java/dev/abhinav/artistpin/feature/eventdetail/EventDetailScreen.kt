package dev.abhinav.artistpin.feature.eventdetail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import dev.abhinav.artistpin.R
import dev.abhinav.artistpin.core.designsystem.ArtistAvatar
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.designsystem.rememberMediaModel
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.EventMedia
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.random.Random
import java.util.Locale

@Stable
interface EventDetailActions {
    fun onMediaPicked(uris: List<Uri>)
    fun onRemoveMedia(mediaId: String)
    fun onOpenViewer(index: Int)
    fun onPlayReel()
    fun onCloseViewer()
    fun onToggleReelPlayback()
    fun onDeleteRequested()
    fun onDeleteDismissed()
    fun onDeleteConfirmed()
}

@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    viewModel: EventDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EventDetailEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                    is EventDetailEffect.MediaAdded -> snackbarHostState.showSnackbar(
                        if (effect.count == 0) {
                            context.getString(R.string.media_already_in_reel)
                        } else {
                            context.resources.getQuantityString(
                                R.plurals.media_added,
                                effect.count,
                                effect.count,
                            )
                        },
                    )
                    EventDetailEffect.NavigateBack -> onBack()
                }
            }
        }
    }

    EventDetailContent(
        uiState = uiState,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onEdit = onEdit,
        onOpenArtist = onOpenArtist,
    )
}

/**
 * The show as a ticket stub: a big date block, a perforated card, the lineup as faces, and the
 * photo grid as the invitation it always should have been.
 *
 * Dark-only, like the artist detail screen — the stub reads as a physical object against the
 * near-black page and has no light counterpart in the design.
 */
@Composable
fun EventDetailContent(
    uiState: EventDetailUiState,
    actions: EventDetailActions,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = uiState.event
    val pickMedia = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_PICK)) { uris ->
        actions.onMediaPicked(uris)
    }

    Box(modifier.fillMaxSize().background(Pin.Page)) {
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Pin.Accent)
            }

            event == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This show is no longer here.", color = Pin.OnPage)
                    TextButton(onClick = onBack) { Text("Go back", color = Pin.Accent) }
                }
            }

            else -> EventBody(
                event = event,
                uiState = uiState,
                actions = actions,
                onBack = onBack,
                onEdit = { onEdit(event.id) },
                onOpenArtist = onOpenArtist,
                onAddMedia = {
                    pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                },
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (uiState.isViewerOpen) {
        MediaViewerDialog(
            media = uiState.media,
            startIndex = uiState.viewerStartIndex ?: 0,
            isReelPlaying = uiState.isReelPlaying,
            onClose = actions::onCloseViewer,
            onTogglePlayback = actions::onToggleReelPlayback,
            onRemove = actions::onRemoveMedia,
        )
    }

    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = actions::onDeleteDismissed,
            title = { Text("Delete this show?") },
            text = { Text("The show and every photo and video you attached to it will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = actions::onDeleteConfirmed) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = actions::onDeleteDismissed) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun EventBody(
    event: ConcertEvent,
    uiState: EventDetailUiState,
    actions: EventDetailActions,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onAddMedia: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(onBack = onBack)
        TicketStub(event = event)

        SectionHeader(
            title = "Lineup",
            trailing = "${event.artists.size} ${if (event.artists.size == 1) "artist" else "artists"}",
            // The stub is shifted up to overlap the header, which leaves that much slack
            // underneath it — this section absorbs it rather than adding to it.
            topPadding = 8,
        )
        Lineup(event = event, onOpenArtist = onOpenArtist)

        YourNight(
            uiState = uiState,
            actions = actions,
            onAddMedia = onAddMedia,
        )

        FooterActions(onEdit = onEdit, onDelete = actions::onDeleteRequested)
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    // Just the back button now: the stub below carries the show's name, and printing it twice
    // made the screen open on a title with a title under it.
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 14.dp, top = 12.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Pin.BackCircle)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Pin.OnBackCircle,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(26.dp))
    }
}

/**
 * The show as a paper stub: printed on kraft in the dark, on white in the light, and torn along
 * the bottom edge. It is the one object on this screen that isn't part of the app's surface —
 * everything around it is dark chrome, and the ticket is a thing you kept.
 */
@Composable
private fun TicketStub(event: ConcertEvent) {
    val dark = isSystemInDarkTheme()
    val paper = if (dark) Color(0xFFCBC0A9) else Color.White
    val ink = if (dark) Color(0xFF241F18) else Color(0xFF17181C)
    // The cutouts are holes, so they have to be filled with whatever the page behind is.
    val page = Pin.Page

    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .offset(y = (-14).dp)
            .shadow(
                elevation = if (dark) 14.dp else 8.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(paper)
            .then(
                // White paper on a light page needs an edge, or it dissolves into the background.
                if (dark) Modifier else Modifier.border(1.dp, Color(0x14000000), RoundedCornerShape(12.dp)),
            ),
    ) {
        HeaderBand(event)
        DateBand(event = event, ink = ink)
        Perforation(ink = ink)
        AdmissionBand(event = event, ink = ink)
        TearEdge(page = page)
    }
}

@Composable
private fun HeaderBand(event: ConcertEvent) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TicketBandBlue)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = event.eyebrow().uppercase(),
                color = TicketOnBand.copy(alpha = 0.62f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.displayTitle,
                color = TicketOnBand,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (event.date.isAfter(LocalDate.now())) "UPCOMING" else "ATTENDED",
            color = TicketOnBand,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x2EFFFFFF))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/**
 * The eyebrow is the line above the show's name, so it has to say something the name doesn't.
 * A festival night gets its bill; a night named after the bill gets the city instead.
 */
private fun ConcertEvent.eyebrow(): String {
    val bill = headliners.joinToString(" · ") { it.name }
    return bill.takeIf { it.isNotBlank() && it != displayTitle } ?: city.displayName
}

@Composable
private fun DateBand(event: ConcertEvent, ink: Color) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = event.date.format(TICKET_WEEKDAY).uppercase(),
                color = ink.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = event.date.dayOfMonth.toString(),
                color = ink,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 40.sp,
                letterSpacing = (-1.8).sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(
                text = event.date.format(TICKET_MONTH_YEAR).uppercase(),
                color = ink.copy(alpha = 0.63f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
        }

        // The dashed rule between the date and the fields, as on a printed stub.
        Canvas(Modifier.width(1.5.dp).fillMaxHeight()) {
            var y = 0f
            val dash = 4.dp.toPx()
            while (y < size.height) {
                drawRect(
                    color = ink.copy(alpha = 0.28f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, minOf(dash, size.height - y)),
                )
                y += dash * 2
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TicketField(label = "Venue", ink = ink) {
                Text(
                    text = event.venue.name,
                    color = ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TicketField(label = "City", ink = ink) {
                Text(
                    text = event.city.displayName,
                    color = ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TicketField(label: String, ink: Color, value: @Composable () -> Unit) {
    Column {
        Text(
            text = label.uppercase(),
            color = ink.copy(alpha = 0.47f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(2.dp))
        value()
    }
}

/**
 * The tear line, with a bite taken out of each edge. The notches are painted in the header
 * band's blue rather than the page colour: sitting over the card's own shadow, page-coloured
 * cutouts came out a muddy grey that read as a rendering fault rather than a punched hole.
 */
@Composable
private fun Perforation(ink: Color) {
    Box(Modifier.fillMaxWidth().height(20.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            drawLine(
                color = ink.copy(alpha = 0.32f),
                start = Offset(13.dp.toPx(), y),
                end = Offset(size.width - 13.dp.toPx(), y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-10).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(TicketBandBlue),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 10.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(TicketBandBlue),
        )
    }
}

@Composable
private fun AdmissionBand(event: ConcertEvent, ink: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TicketField(label = "Admission", ink = ink) {
            Text(
                text = "GA · NO ${event.serial()}",
                color = ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
        Barcode(seed = event.id, ink = ink)
    }
}

/** Stable per show, so the same ticket always prints the same number. */
private fun ConcertEvent.serial(): String =
    (abs(id.hashCode()) % 10_000).toString().padStart(4, '0')

/**
 * Bars derived from the show's id: every ticket gets its own barcode, and it is the same barcode
 * every time you open it.
 */
@Composable
private fun Barcode(seed: String, ink: Color) {
    val bars = remember(seed) {
        val random = Random(seed.hashCode())
        List(BARCODE_BARS) { 1 + random.nextInt(5) }
    }
    Canvas(
        Modifier
            .height(30.dp)
            .width((bars.sum() + (bars.size - 1) * 1.5f).dp),
    ) {
        var x = 0f
        bars.forEachIndexed { index, barWidth ->
            drawRect(
                color = if (index % 2 == 0) ink else ink.copy(alpha = 0.44f),
                topLeft = Offset(x, 0f),
                size = Size(barWidth.dp.toPx(), size.height),
            )
            x += barWidth.dp.toPx() + 1.5.dp.toPx()
        }
    }
}

/** Semicircles of the page bitten out of the paper's bottom edge. */
@Composable
private fun TearEdge(page: Color) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val step = 10.dp.toPx()
        var centerX = 5.dp.toPx()
        while (centerX - step < size.width) {
            drawCircle(
                color = page,
                radius = 4.5.dp.toPx(),
                center = Offset(centerX, size.height),
            )
            centerX += step
        }
    }
}

private val TicketBandBlue = Color(0xFF173F6B)
private val TicketOnBand = Color(0xFFF0EBE0)
private const val BARCODE_BARS = 19
private val TICKET_WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val TICKET_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yy", Locale.US)

@Composable
private fun SectionHeader(title: String, trailing: String? = null, topPadding: Int = 22) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = topPadding.dp, bottom = 6.dp),
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
        trailing?.let { Text(it, color = Pin.OnPageFaint, fontSize = 12.sp) }
    }
}

/**
 * Faces rather than chips. FlowRow, not Row: a six-name festival bill would otherwise run off the
 * screen edge and take the artists past the third one with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lineup(event: ConcertEvent, onOpenArtist: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = LINEUP_COLUMNS,
    ) {
        event.artists.forEach { billing ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Pin.RadiusCard))
                    .background(Pin.Fill)
                    .clickable { onOpenArtist(billing.artist.id) }
                    .padding(horizontal = 6.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArtistAvatar(
                    name = billing.artist.name,
                    imageUrl = billing.artist.imageUrl,
                    size = 52.dp,
                )
                Text(
                    text = billing.artist.name,
                    color = Pin.OnPage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A trailing row of two would otherwise stretch its cards to half the screen each.
        repeat(paddingCells(event.artists.size)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun paddingCells(count: Int): Int {
    val remainder = count % LINEUP_COLUMNS
    return if (remainder == 0) 0 else LINEUP_COLUMNS - remainder
}

@Composable
private fun YourNight(
    uiState: EventDetailUiState,
    actions: EventDetailActions,
    onAddMedia: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "YOUR NIGHT",
            color = Pin.OnPageTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
        )
        if (uiState.hasMedia) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = actions::onPlayReel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Pin.OnPage,
                    modifier = Modifier.size(16.dp),
                )
                Text("Play reel", color = Pin.OnPage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (uiState.isImporting) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Pin.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "＋ Add photos",
            // The pill is accent-filled in both modes, so its label stays white in both.
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .background(Pin.Accent)
                .clickable(enabled = !uiState.isImporting, onClick = onAddMedia)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    if (uiState.hasMedia) {
        PhotoGrid(media = uiState.media, onOpen = actions::onOpenViewer)
    } else {
        EmptyPhotoGrid()
        Text(
            text = "Drop in the photos you took and they'll live here as this show's highlight reel.",
            color = Pin.OnPageTertiary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
    }
}

/** Three tiles fading to the right, so the row reads as an invitation rather than a void. */
@Composable
private fun EmptyPhotoGrid() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The row fades to the right, so it reads as one invitation rather than three empty slots.
        val edge = Pin.BorderSoft
        listOf(1f, 0.72f, 0.5f).forEach { fade ->
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Pin.RadiusTile))
                    .background(Pin.TileFill)
                    .border(1.dp, edge.copy(alpha = edge.alpha * fade), RoundedCornerShape(Pin.RadiusTile)),
            )
        }
    }
}

@Composable
private fun PhotoGrid(media: List<EventMedia>, onOpen: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        media.chunked(PHOTO_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    val index = media.indexOf(item)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(Pin.RadiusTile))
                            .clickable { onOpen(index) },
                    ) {
                        val model by rememberMediaModel(item)
                        AsyncImage(
                            model = model,
                            contentDescription = if (item.isVideo) {
                                "Video from this show"
                            } else {
                                "Photo from this show"
                            },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.isVideo) {
                            Icon(
                                Icons.Default.PlayCircleFilled,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(32.dp),
                            )
                        }
                    }
                }
                repeat(PHOTO_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FooterActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Edit show",
            color = Pin.OnPageStrong,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Pin.RadiusTile))
                .border(1.dp, Pin.BorderSoft, RoundedCornerShape(Pin.RadiusTile))
                .clickable(onClick = onEdit)
                .padding(vertical = 11.dp),
        )
        Text(
            text = "Delete",
            color = Pin.Destructive,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(Pin.RadiusTile))
                .border(1.dp, Pin.DestructiveBorder, RoundedCornerShape(Pin.RadiusTile))
                .clickable(onClick = onDelete)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

private const val MAX_PICK = 50
private const val LINEUP_COLUMNS = 3
private const val PHOTO_COLUMNS = 3
private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
private val WEEKDAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, yyyy", Locale.US)
