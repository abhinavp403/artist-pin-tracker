package dev.abhinav.artistpin.feature.worldmap

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import dev.abhinav.artistpin.R
import dev.abhinav.artistpin.core.designsystem.ARTIST_PIN_ZOOM_THRESHOLD
import dev.abhinav.artistpin.core.designsystem.ArtistPin
import dev.abhinav.artistpin.core.designsystem.CountBadge
import dev.abhinav.artistpin.core.designsystem.rememberPinPortrait
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.Coordinates
import dev.abhinav.artistpin.core.model.VenuePin
import kotlinx.coroutines.launch

@Stable
interface WorldMapActions {
    fun onLocationPermissionResult(granted: Boolean)
    fun onCitySelected(cityId: String)
    fun onSelectionDismissed()
    fun onVenueToggled(venueId: String)
    fun onVenuesCollapsed()
    fun onOverflowOpen()
    fun onOverflowDismiss()
    fun onBackUpRequested()
    fun onRestoreRequested()
    fun onBackupDestinationPicked(uri: Uri)
    fun onRestoreSourcePicked(uri: Uri)
    fun onRestoreDismissed()
    fun onRestoreConfirmed()
}

const val CITY_ZOOM = 12f
const val VENUE_ZOOM = 15f

/**
 * Everything about where the map is looking, and whether that has been decided yet.
 *
 * Held above the navigation graph rather than inside the map: leaving for a show and coming back
 * must not re-run the opening camera, or the map resets to a fit-everything view and loses the
 * city you were reading.
 */
@Stable
class WorldMapCameraState(val position: CameraPositionState) {
    /** True once the opening position has been chosen, for the rest of the session. */
    internal var openingSettled by mutableStateOf(false)

    /** The set of cities the camera was last fitted to, so the same set never refits. */
    internal var lastFittedKey by mutableStateOf<String?>(null)
}

@Composable
fun rememberWorldMapCameraState(): WorldMapCameraState {
    val position = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 1f)
    }
    return remember(position) { WorldMapCameraState(position) }
}

/**
 * The map itself, edge to edge, with nothing on top of it. Every piece of chrome the map used to
 * carry — title, counts, callout, navigation — now floats above it from the home screen, which is
 * what makes the map fill the whole window instead of roughly half of it.
 */
@Composable
fun WorldMapCanvas(
    pins: List<CityPin>,
    venuePins: List<VenuePin>,
    startLocation: Coordinates?,
    isLocatingUser: Boolean,
    cameraState: WorldMapCameraState,
    onCitySelected: (String) -> Unit,
    onVenueSelected: (venueId: String, cityId: String) -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState = cameraState.position
    val scope = rememberCoroutineScope()

    /** Tapping a pin flies to it, so a tap always reveals more detail than it replaces. */
    fun zoomTo(target: LatLng, zoom: Float) {
        scope.launch {
            runCatching {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, zoom))
            }
        }
    }

    // Re-fit whenever the set of cities changes, so a newly added show is never off-screen —
    // but only after the opening position has been decided, and never twice for the same set.
    val pinKey = pins.joinToString(",") { it.city.id }

    suspend fun fitToPins() {
        val bounds = LatLngBounds.builder()
            .apply { pins.forEach { include(LatLng(it.latitude, it.longitude)) } }
            .build()
        val update = if (pins.size == 1) {
            CameraUpdateFactory.newLatLngZoom(bounds.center, 9f)
        } else {
            CameraUpdateFactory.newLatLngBounds(bounds, 128)
        }
        runCatching { cameraPositionState.animate(update) }
    }

    LaunchedEffect(pinKey, isLocatingUser) {
        if (pins.isEmpty()) return@LaunchedEffect

        if (!cameraState.openingSettled) {
            // Hold the very first camera move until we know whether there's a location to use,
            // otherwise the world-fit and the home city animate over each other on launch.
            if (isLocatingUser) return@LaunchedEffect
            cameraState.openingSettled = true
            cameraState.lastFittedKey = pinKey
            if (startLocation != null) {
                val here = LatLng(startLocation.latitude, startLocation.longitude)
                runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(here, CITY_ZOOM)) }
                return@LaunchedEffect
            }
            fitToPins()
            return@LaunchedEffect
        }

        if (pinKey == cameraState.lastFittedKey) return@LaunchedEffect
        cameraState.lastFittedKey = pinKey
        fitToPins()
    }

    // Reading zoom through derivedStateOf keeps the marker set from recomposing on every
    // pan frame — only when the threshold is actually crossed.
    val showArtistPins by remember {
        derivedStateOf { cameraPositionState.position.zoom >= ARTIST_PIN_ZOOM_THRESHOLD }
    }

    // The map is the page background in this design, so in dark mode it has to be dark too —
    // otherwise a bright slab of daytime tiles sits behind near-black chrome.
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val mapStyle = remember(isDark) {
        if (isDark) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_night) else null
    }

    GoogleMap(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "World map of cities you've seen concerts in" },
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapType = MapType.NORMAL, mapStyleOptions = mapStyle),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        // Keeps Google's attribution clear of the floating dock, which now sits over the map.
        contentPadding = PaddingValues(bottom = DOCK_RESERVED_HEIGHT, top = 44.dp),
        onMapClick = { onMapClick() },
    ) {
        // Past state-level zoom the city aggregate stops being useful, so each venue shows the
        // headliner you saw there instead.
        if (showArtistPins) {
            venuePins.forEach { pin ->
                // Keyed on the loaded portrait so the marker re-rasterizes once artwork arrives.
                val portrait = rememberPinPortrait(pin.headlinerImageUrl)
                MarkerComposable(
                    keys = arrayOf<Any>(pin.venue.id, pin.eventCount, portrait != null),
                    state = rememberUpdatedMarkerState(LatLng(pin.venue.latitude, pin.venue.longitude)),
                    title = pin.headlinerName ?: pin.venue.name,
                    snippet = pin.venue.name,
                    onClick = {
                        zoomTo(LatLng(pin.venue.latitude, pin.venue.longitude), VENUE_ZOOM)
                        // At this zoom the pin is a specific venue, so the tap should say what
                        // happened there rather than only move the camera.
                        onVenueSelected(pin.venue.id, pin.venue.cityId)
                        true
                    },
                ) {
                    ArtistPin(
                        artistName = pin.headlinerName,
                        portrait = portrait,
                        venueName = pin.venue.name,
                        eventCount = pin.eventCount,
                    )
                }
            }
        } else {
            pins.forEach { pin ->
                MarkerComposable(
                    keys = arrayOf<Any>(pin.city.id, pin.eventCount),
                    state = rememberUpdatedMarkerState(LatLng(pin.latitude, pin.longitude)),
                    title = pin.city.name,
                    onClick = {
                        // Past the artist-pin threshold, so the city's venues appear on arrival.
                        zoomTo(LatLng(pin.latitude, pin.longitude), CITY_ZOOM)
                        onCitySelected(pin.city.id)
                        true
                    },
                ) {
                    CountBadge(count = pin.eventCount, label = pin.city.name)
                }
            }
        }
    }
}

private val DOCK_RESERVED_HEIGHT = 150.dp
