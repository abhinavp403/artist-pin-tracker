package dev.abhinav.artistpin.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.CameraMoveStartedReason
import dev.abhinav.artistpin.R
import dev.abhinav.artistpin.core.designsystem.EmptyState
import dev.abhinav.artistpin.core.designsystem.PIN_MOTION_MILLIS
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.designsystem.formatFull
import dev.abhinav.artistpin.core.designsystem.formatMonthYear
import dev.abhinav.artistpin.core.model.BackupSummary
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.VenuePin
import dev.abhinav.artistpin.feature.artists.ArtistsListState
import dev.abhinav.artistpin.feature.artists.ArtistsSheet
import dev.abhinav.artistpin.feature.artists.rememberArtistsListState
import dev.abhinav.artistpin.feature.artists.ArtistsViewModel
import dev.abhinav.artistpin.feature.worldmap.WorldMapCameraState
import dev.abhinav.artistpin.feature.worldmap.WorldMapCanvas
import dev.abhinav.artistpin.feature.worldmap.rememberWorldMapCameraState
import dev.abhinav.artistpin.feature.worldmap.WorldMapEffect
import dev.abhinav.artistpin.feature.worldmap.WorldMapViewModel
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlin.math.hypot

enum class HomeTab { MAP, ARTISTS }

/**
 * The home screen's own state, held above the navigation graph.
 *
 * Opening a show and coming back must return you to the map exactly as you left it — same camera,
 * same tab, same expanded city — and state remembered inside the destination doesn't reliably
 * survive that round trip.
 */
@Stable
class HomeState(val camera: WorldMapCameraState, val artistList: ArtistsListState) {
    var tab by mutableStateOf(HomeTab.MAP)
    var venuesExpanded by mutableStateOf(false)
}

@Composable
fun rememberHomeState(): HomeState {
    val camera = rememberWorldMapCameraState()
    val artistList = rememberArtistsListState()
    return remember(camera, artistList) { HomeState(camera, artistList) }
}

/**
 * The map-first home. The map fills the window and everything else floats on top of it: a stat
 * pill and artist strip at the top, and one dock at the bottom that carries the selected city and
 * the navigation the old top bar and bottom bar used to hold separately.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpenArtist: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onAddEvent: () -> Unit,
    onSignOut: () -> Unit,
    mapViewModel: WorldMapViewModel = koinViewModel(),
    artistsViewModel: ArtistsViewModel = koinViewModel(),
) {
    val uiState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHostState = remember { SnackbarHostState() }

    var tab by state::tab
    var sheetExpanded by state::venuesExpanded
    val cameraPositionState = state.camera.position

    val createBackup = rememberLauncherForActivityResult(CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        uri?.let(mapViewModel::onBackupDestinationPicked)
    }
    // Some file providers hand JSON back as octet-stream, so both are accepted.
    val openBackup = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(mapViewModel::onRestoreSourcePicked)
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            mapViewModel.effects.collect { effect ->
                when (effect) {
                    is WorldMapEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                    is WorldMapEffect.CreateBackupFile -> createBackup.launch(effect.suggestedName)
                    WorldMapEffect.OpenBackupFile -> openBackup.launch(BACKUP_OPEN_TYPES)
                }
            }
        }
    }

    // Map and Artists are tabs in one destination, so back has nothing to pop — without this it
    // leaves the app from the artist list rather than returning to the map you started on.
    BackHandler(enabled = tab == HomeTab.ARTISTS) {
        tab = HomeTab.MAP
    }

    val context = LocalContext.current
    val requestLocation = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        mapViewModel.onLocationPermissionResult(grants.values.any { it })
    }
    LaunchedEffect(Unit) {
        // Coarse alone is enough to open on the right city, so a user who granted only the
        // approximate location is never asked again.
        val alreadyGranted = LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            mapViewModel.onLocationPermissionResult(true)
        } else {
            requestLocation.launch(LOCATION_PERMISSIONS)
        }
    }

    // The dock always names a city. Without a tap it names the one you've been to most, which is
    // a more useful default than an empty row.
    // The city row is about wherever you're looking, so it only exists once the camera is close
    // enough for "which city" to mean something. Zoomed out to the whole world it named a city
    // you weren't looking at.
    val dockCity by remember(uiState.pins) {
        derivedStateOf {
            val camera = cameraPositionState.position
            if (camera.zoom < CITY_DOCK_ZOOM) {
                null
            } else {
                // Always whichever city is under the camera — a remembered tap would keep naming
                // the city you left after you zoomed out of it and into another one.
                uiState.pins
                    .minByOrNull { it.distanceTo(camera.target.latitude, camera.target.longitude) }
                    ?.takeIf { it.distanceTo(camera.target.latitude, camera.target.longitude) < NEAR_ENOUGH_DEGREES }
            }
        }
    }
    // Moving the map is a decision to look at it, so the list gets out of the way. Keyed on the
    // gesture specifically: the camera also animates itself after a pin tap, and treating that as
    // movement would close the venue the tap had just opened.
    LaunchedEffect(cameraPositionState) {
        snapshotFlow {
            cameraPositionState.isMoving &&
                cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        }
            .filter { it }
            .collect { sheetExpanded = false }
    }

    // Whatever closes the city list — the chevron, zooming out, leaving for another tab — also
    // closes the venue inside it, so the city always reopens collapsed.
    LaunchedEffect(sheetExpanded) {
        if (!sheetExpanded) mapViewModel.onVenuesCollapsed()
    }

    // Zooming out of a city closes its list for good: reopening it on the way back in would put
    // a list over the map you had just chosen to look at.
    LaunchedEffect(dockCity == null) {
        if (dockCity == null) sheetExpanded = false
    }

    val dockVenues = remember(dockCity, uiState.venuePins) {
        uiState.venuePins
            .filter { it.venue.cityId == dockCity?.city?.id }
            .sortedByDescending { it.eventCount }
    }

    val scope = rememberCoroutineScope()
    fun zoomBy(delta: Float) {
        scope.launch {
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.zoomTo(cameraPositionState.position.zoom + delta),
                )
            }
        }
    }

    Scaffold(
        // No bars of its own: the map runs under the status bar and the gesture area, and the
        // snackbar is the only thing the Scaffold still contributes.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            if (uiState.isEmpty && tab == HomeTab.MAP) {
                // Nothing to draw a map for yet, and the chrome floats over an opaque surface
                // rather than over whatever the window happens to show through.
                Box(Modifier.fillMaxSize().background(Pin.Sheet))
                EmptyState(
                    icon = Icons.Default.Public,
                    title = "No shows pinned yet",
                    message = "Add the first concert you've been to and it'll appear here as a pin on the map.",
                    action = { Button(onClick = onAddEvent) { Text("Add a concert") } },
                )
            } else {
                WorldMapCanvas(
                    pins = uiState.pins,
                    venuePins = uiState.venuePins,
                    startLocation = uiState.startLocation,
                    isLocatingUser = uiState.isLocatingUser,
                    cameraState = state.camera,
                    onCitySelected = mapViewModel::onCitySelected,
                    // Tapping a venue pin unfolds that venue's shows in the dock, which is the
                    // only place on this screen that can hold them.
                    onVenueSelected = { venueId, cityId ->
                        mapViewModel.onCitySelected(cityId)
                        sheetExpanded = true
                        if (uiState.expandedVenueId != venueId) mapViewModel.onVenueToggled(venueId)
                    },
                    onMapClick = mapViewModel::onSelectionDismissed,
                )
            }

            TopScrim()

            if (tab == HomeTab.ARTISTS) {
                ArtistsSheet(
                    viewModel = artistsViewModel,
                    listState = state.artistList,
                    onOpenArtist = onOpenArtist,
                    onMessage = { snackbarHostState.showSnackbar(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            TopChrome(
                // The counts describe the map, so they'd be lying on top of the artist list —
                // only the overflow stays, since backup and restore have no other home.
                showCounts = tab == HomeTab.MAP,
                cityCount = uiState.pins.size,
                showCount = uiState.totalShows,
                isBusy = uiState.isBackupRunning,
                showOverflow = uiState.showOverflowMenu,
                onOverflowOpen = mapViewModel::onOverflowOpen,
                onOverflowDismiss = mapViewModel::onOverflowDismiss,
                onBackUp = mapViewModel::onBackUpRequested,
                onRestore = mapViewModel::onRestoreRequested,
                showUploadAction = uiState.showUploadAction,
                onUploadLibrary = mapViewModel::onUploadLibraryRequested,
                showSyncAction = uiState.showSyncAction,
                pendingSyncCount = uiState.pendingSyncCount,
                isSyncing = uiState.isSyncing,
                onSyncNow = mapViewModel::onSyncNowRequested,
                onSignOut = {
                    mapViewModel.onOverflowDismiss()
                    onSignOut()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (tab == HomeTab.MAP && !uiState.isEmpty) {
                ZoomControls(
                    onZoomIn = { zoomBy(1f) },
                    onZoomOut = { zoomBy(-1f) },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }

            Dock(
                // The Artists tab has its own list; a city row above it belongs to the map.
                city = dockCity.takeIf { tab == HomeTab.MAP },
                venues = dockVenues,
                expanded = sheetExpanded,
                expandedVenueId = uiState.expandedVenueId,
                venueEvents = uiState.venueEvents,
                tab = tab,
                onToggleExpanded = { sheetExpanded = !sheetExpanded },
                onVenueToggled = mapViewModel::onVenueToggled,
                onOpenEvent = onOpenEvent,
                // Anything that takes you off the map closes the list: coming back to a city
                // still unfolded from before hides the map you returned to look at.
                onSelectTab = {
                    sheetExpanded = false
                    tab = it
                },
                onAdd = {
                    sheetExpanded = false
                    onAddEvent()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    uiState.pendingRestore?.let { backup ->
        RestoreConfirmationDialog(
            summary = backup.summary,
            currentShows = uiState.totalShows,
            replaces = uiState.restoreReplaces,
            onConfirm = mapViewModel::onRestoreConfirmed,
            onDismiss = mapViewModel::onRestoreDismissed,
        )
    }
}

/** Keeps the system clock legible over whatever map tile happens to be underneath it. */
@Composable
private fun TopScrim() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Brush.verticalGradient(listOf(Pin.StatusScrimTop, Pin.StatusScrimBottom))),
    )
}

@Composable
private fun TopChrome(
    showCounts: Boolean,
    cityCount: Int,
    showCount: Int,
    isBusy: Boolean,
    showOverflow: Boolean,
    onOverflowOpen: () -> Unit,
    onOverflowDismiss: () -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    showUploadAction: Boolean,
    onUploadLibrary: () -> Unit,
    showSyncAction: Boolean,
    pendingSyncCount: Int,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCounts) StatPill(cityCount = cityCount, showCount = showCount)
        Spacer(Modifier.weight(1f))
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Pin.Chrome)
                    .clickable(onClick = onOverflowOpen),
                contentAlignment = Alignment.Center,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = Pin.OnChrome,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            DropdownMenu(expanded = showOverflow, onDismissRequest = onOverflowDismiss) {
                // Always available. Both go through LibraryBackup, which is bound to whichever
                // store the app is actually reading, so a file written here always describes what
                // you can see — and always restores back into it.
                DropdownMenuItem(
                    text = { Text("Back up to a file") },
                    onClick = onBackUp,
                    leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Restore from a file") },
                    onClick = onRestore,
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                )
                if (showUploadAction) {
                    DropdownMenuItem(
                        text = { Text("Copy my shows to my account") },
                        onClick = onUploadLibrary,
                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    )
                }
                if (showSyncAction) {
                    DropdownMenuItem(
                        // States the backlog rather than a status word. "3 shows waiting to sync"
                        // answers the question someone actually has after adding shows with no
                        // signal; "Synced" or a tick does not.
                        text = {
                            Text(
                                when {
                                    isSyncing -> "Syncing…"
                                    pendingSyncCount == 0 -> "Everything is synced"
                                    pendingSyncCount == 1 -> "1 change waiting to sync"
                                    else -> "$pendingSyncCount changes waiting to sync"
                                },
                            )
                        },
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        leadingIcon = {
                            Icon(
                                if (pendingSyncCount == 0) Icons.Default.CloudDone
                                else Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = if (pendingSyncCount == 0) Pin.OnChromeSecondary else Pin.Accent,
                            )
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    onClick = onSignOut,
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                )
            }
        }
    }
}

/**
 * Sits under the stat pill rather than in the dock: zoom belongs to the map, and the dock is
 * already carrying the city and the navigation.
 */
@Composable
private fun ZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, top = 54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Pin.Chrome),
    ) {
        ZoomButton(Icons.Default.Add, "Zoom in", onZoomIn)
        HorizontalDivider(color = Pin.HairlineChrome, modifier = Modifier.width(36.dp))
        ZoomButton(Icons.Default.Remove, "Zoom out", onZoomOut)
    }
}

@Composable
private fun ZoomButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Pin.OnChrome, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatPill(cityCount: Int, showCount: Int) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Pin.Chrome)
            .padding(start = 10.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Public,
            contentDescription = null,
            tint = Pin.OnChrome,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.city_count, cityCount, cityCount),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Pin.OnChrome,
        )
        Box(Modifier.width(1.dp).height(13.dp).background(Pin.HairlineChrome))
        Text(
            text = pluralStringResource(R.plurals.show_count, showCount, showCount),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Pin.OnChromeSecondary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(
    city: CityPin?,
    venues: List<VenuePin>,
    expanded: Boolean,
    expandedVenueId: String?,
    venueEvents: List<EventSummary>,
    tab: HomeTab,
    onToggleExpanded: () -> Unit,
    onVenueToggled: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(Pin.RadiusDock),
        color = Pin.Dock,
        shadowElevation = 10.dp,
    ) {
        Column {
            AnimatedVisibility(
                visible = city != null && expanded && venues.isNotEmpty(),
                enter = expandVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeIn(),
                exit = shrinkVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeOut(),
            ) {
                Column(
                    Modifier
                        // A city with a lot of venues would otherwise push the tabs off-screen.
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                ) {
                    venues.forEach { venue ->
                        val isExpanded = venue.venue.id == expandedVenueId
                        // The row *and* the shows under it: bringing only the row into view left
                        // the thing the tap actually opened sitting below the fold.
                        val bringIntoView = remember(venue.venue.id) { BringIntoViewRequester() }
                        LaunchedEffect(isExpanded, venueEvents.size) {
                            if (!isExpanded) return@LaunchedEffect
                            bringIntoView.bringIntoView()
                            // Again once the list has finished unfolding — at the first request
                            // the shows are still animating open and barely take up any height.
                            delay(PIN_MOTION_MILLIS.toLong())
                            bringIntoView.bringIntoView()
                        }
                        Column(Modifier.bringIntoViewRequester(bringIntoView)) {
                            VenueRow(
                                venue = venue,
                                expanded = isExpanded,
                                onClick = { onVenueToggled(venue.venue.id) },
                            )
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeIn(),
                                exit = shrinkVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeOut(),
                            ) {
                                Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                                    venueEvents.forEach { event ->
                                        ShowRow(event = event, onClick = { onOpenEvent(event.id) })
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Pin.HairlineChrome)
                    }
                }
            }

            if (city != null) {
                CityRow(city = city, expanded = expanded, onClick = onToggleExpanded)
                HorizontalDivider(color = Pin.HairlineChrome)
            }

            Row(Modifier.padding(6.dp)) {
                DockTab(
                    icon = Icons.Default.Public,
                    label = "Map",
                    selected = tab == HomeTab.MAP,
                    onClick = { onSelectTab(HomeTab.MAP) },
                    modifier = Modifier.weight(1f),
                )
                DockTab(
                    icon = Icons.Default.LibraryMusic,
                    label = "Artists",
                    selected = tab == HomeTab.ARTISTS,
                    onClick = { onSelectTab(HomeTab.ARTISTS) },
                    modifier = Modifier.weight(1f),
                )
                DockTab(
                    icon = Icons.Default.Add,
                    label = "Add",
                    selected = false,
                    alwaysLit = true,
                    onClick = onAdd,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VenueRow(
    venue: VenuePin,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caret by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(PIN_MOTION_MILLIS, easing = PinEasing),
        label = "venueCaret",
    )
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = venue.venue.name,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = Pin.OnChrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = venue.eventCount.toString(),
            fontSize = 12.sp,
            color = Pin.OnChromeSecondary,
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Hide shows" else "Show shows",
            tint = Pin.OnChromeTertiary,
            modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(caret),
        )
    }
}

/** One show, inside the dock. Tapping opens its full page; the map stays where you left it. */
@Composable
private fun ShowRow(event: EventSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Pin.Accent))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.displayTitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Pin.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.date.formatFull(),
                fontSize = 11.5.sp,
                color = Pin.OnChromeSecondary,
            )
        }
        if (event.mediaCount > 0) {
            Text(
                text = event.mediaCount.toString(),
                fontSize = 11.5.sp,
                color = Pin.OnChromeTertiary,
            )
        }
    }
}

@Composable
private fun CityRow(city: CityPin, expanded: Boolean, onClick: () -> Unit) {
    val caret by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(PIN_MOTION_MILLIS, easing = PinEasing),
        label = "caret",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Pin.Accent))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${city.city.name}${city.city.region?.let { ", $it" }.orEmpty()}",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Pin.OnChrome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(city.eventCount).append(if (city.eventCount == 1) " show" else " shows")
                    append(" · ").append(city.venueCount)
                    append(if (city.venueCount == 1) " venue" else " venues")
                    city.lastEventDate?.let { append(" · last ").append(it.formatMonthYear()) }
                },
                fontSize = 12.sp,
                color = Pin.OnChromeSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Hide venues" else "Show venues",
            tint = Pin.OnChromeTertiary,
            modifier = Modifier.size(20.dp).rotate(caret),
        )
    }
}

@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysLit: Boolean = false,
) {
    // Add is an action rather than a place: it never dims like an unselected tab, but it takes
    // the same ink as the selected one instead of standing out in accent colour.
    val tint = if (selected || alwaysLit) Pin.OnChrome else Pin.OnChromeTertiary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Pin.RadiusCard))
            .background(if (selected) Pin.SelectedChrome else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun RestoreConfirmationDialog(
    summary: BackupSummary,
    currentShows: Int,
    replaces: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replaces) "Restore this backup?" else "Restore missing shows?") },
        text = {
            Text(
                text = buildString {
                    append("The file holds ")
                    append(summary.shows)
                    append(" show(s), ")
                    append(summary.artists)
                    append(" artist(s) and ")
                    append(summary.venues)
                    append(" venue(s).\n\n")
                    when {
                        // The two implementations do genuinely different things, so the dialog
                        // says which one is about to happen rather than one reassuring average.
                        !replaces -> append(
                            "Anything in the file that's missing from your account will be added " +
                                "back. Shows you already have are left alone, and nothing is deleted.",
                        )
                        currentShows == 0 ->
                            append("Your library is empty, so nothing will be lost.")
                        else -> {
                            append("This replaces everything currently in the app — ")
                            append(currentShows)
                            append(" show(s) will be deleted. This can't be undone.")
                        }
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (replaces) "Replace" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val PinEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Straight-line distance in degrees. Good enough to answer "which of my cities is on screen" —
 * the candidates are hundreds of kilometres apart, so the latitude distortion never decides it.
 */
private fun CityPin.distanceTo(latitude: Double, longitude: Double): Double =
    hypot(this.latitude - latitude, this.longitude - longitude)

/** Roughly metro level: below this, "the city you're looking at" isn't a real question. */
private const val CITY_DOCK_ZOOM = 8f

/** ~55km at the equator — panned further than this from every city, the row disappears. */
private const val NEAR_ENOUGH_DEGREES = 0.5


private const val BACKUP_MIME_TYPE = "application/json"
private val BACKUP_OPEN_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)
