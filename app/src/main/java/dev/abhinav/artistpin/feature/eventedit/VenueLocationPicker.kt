package dev.abhinav.artistpin.feature.eventedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/**
 * Tap-to-place venue location. Deliberately offline — no geocoding, no Places billing; the
 * user knows where the venue is and the map is the fastest way to say so.
 */
@Composable
fun VenueLocationPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    venueName: String,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit,
) {
    var picked by remember {
        mutableStateOf(
            if (initialLatitude != null && initialLongitude != null) {
                LatLng(initialLatitude, initialLongitude)
            } else {
                null
            },
        )
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            picked ?: LatLng(40.7128, -74.0060),
            if (picked != null) 15f else 3f,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = venueName.ifBlank { "Venue location" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Tap the map where the venue is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GoogleMap(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Map for choosing the venue location" },
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
                        onMapClick = { latLng -> picked = latLng },
                    ) {
                        picked?.let { position ->
                            Marker(
                                state = rememberUpdatedMarkerState(position),
                                title = venueName.ifBlank { "Venue" },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { picked?.let { onConfirm(it.latitude, it.longitude) } },
                        enabled = picked != null,
                    ) { Text("Use this spot") }
                }
            }
        }
    }
}
