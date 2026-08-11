package dev.abhinav.artistpin.feature.worldmap

import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Coordinates
import dev.abhinav.artistpin.core.model.VenuePin

data class WorldMapUiState(
    val isLoading: Boolean = true,
    val pins: List<CityPin> = emptyList(),
    val venuePins: List<VenuePin> = emptyList(),
    val selectedCityId: String? = null,
    /** The venue whose shows are unfolded inside the dock, if any. */
    val expandedVenueId: String? = null,
    val venueEvents: List<EventSummary> = emptyList(),
    val showOverflowMenu: Boolean = false,
    val isBackupRunning: Boolean = false,
    /** Set once a restore file is parsed, so the dialog can state what will replace what. */
    val pendingRestore: BackupData? = null,
    /** Where to open the camera. Null once [isLocatingUser] is false means "we never got one". */
    val startLocation: Coordinates? = null,
    /**
     * True until the location question is settled either way. The map holds off on fitting the
     * camera to the pins while this is set, so the two don't animate over each other on launch.
     */
    val isLocatingUser: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && pins.isEmpty()
    val totalShows: Int get() = pins.sumOf { it.eventCount }
    val countryCount: Int get() = pins.map { it.city.country }.distinct().size
    val selectedPin: CityPin? get() = pins.firstOrNull { it.city.id == selectedCityId }
}

sealed interface WorldMapEffect {
    data class ShowMessage(val message: String) : WorldMapEffect

    /** Asks the screen to open the system document picker with a dated default filename. */
    data class CreateBackupFile(val suggestedName: String) : WorldMapEffect
    data object OpenBackupFile : WorldMapEffect
}
