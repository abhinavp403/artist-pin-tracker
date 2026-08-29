package dev.abhinav.artistpin.feature.worldmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import dev.abhinav.artistpin.core.designsystem.toUserMessage
import dev.abhinav.artistpin.core.location.DeviceLocationProvider
import dev.abhinav.artistpin.core.media.BackupFileStore
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.BuildConfig
import dev.abhinav.artistpin.data.LibraryBackup
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.LibraryMigrator
import dev.abhinav.artistpin.data.sync.LibrarySync
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WorldMapViewModel(
    private val repository: ConcertRepository,
    private val backupRepository: LibraryBackup,
    private val backupFileStore: BackupFileStore,
    private val locationProvider: DeviceLocationProvider,
    private val libraryMigrator: LibraryMigrator,
    private val librarySync: LibrarySync,
) : ViewModel(), WorldMapActions {

    private val _uiState = MutableStateFlow(
        WorldMapUiState(
            // Backup works either way now — it goes through LibraryBackup, which is bound to
            // whichever store the app is reading. The migration is the only genuinely one-way
            // action: it exists to move Room's copy up, so it disappears once that has happened.
            showUploadAction = !BuildConfig.USE_BACKEND,
            showSyncAction = BuildConfig.USE_BACKEND,
            restoreReplaces = backupRepository.restoreReplaces,
        ),
    )
    val uiState: StateFlow<WorldMapUiState> = _uiState.asStateFlow()

    private val _effects = Channel<WorldMapEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Which venue row in the dock is open. Null closes the inline show list entirely. */
    private val expandedVenueId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeExpandedVenue() = viewModelScope.launch {
        expandedVenueId
            .flatMapLatest { venueId ->
                if (venueId == null) flowOf(emptyList()) else repository.observeEventsAtVenue(venueId)
            }
            .collect { events -> _uiState.update { it.copy(venueEvents = events) } }
    }

    init {
        observeExpandedVenue()
        viewModelScope.launch {
            repository.observeAllVenuePins().collect { venuePins ->
                _uiState.update { it.copy(venuePins = venuePins) }
            }
        }
        // Runs for as long as the map is alive, so shows added later get artwork too.
        viewModelScope.launch { repository.keepArtistArtworkFresh() }
        viewModelScope.launch {
            librarySync.pendingCount.collect { count ->
                _uiState.update { it.copy(pendingSyncCount = count) }
            }
        }
        viewModelScope.launch {
            repository.observeCityPins().collect { pins ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        pins = pins,
                        selectedCityId = state.selectedCityId?.takeIf { id ->
                            pins.any { it.city.id == id }
                        },
                    )
                }
            }
        }
    }

    /**
     * Called once the screen knows whether it has the location permission. A denial resolves the
     * question just as firmly as a fix does — either way the map stops waiting and draws.
     */
    override fun onLocationPermissionResult(granted: Boolean) {
        if (!_uiState.value.isLocatingUser) return
        if (!granted) {
            _uiState.update { it.copy(isLocatingUser = false) }
            return
        }
        viewModelScope.launch {
            val location = locationProvider.currentLocation()
            _uiState.update { it.copy(startLocation = location, isLocatingUser = false) }
        }
    }

    /** Closing the city closes what was open inside it, so it reopens clean next time. */
    override fun onVenuesCollapsed() {
        if (_uiState.value.expandedVenueId == null) return
        expandedVenueId.value = null
        _uiState.update { it.copy(expandedVenueId = null, venueEvents = emptyList()) }
    }

    override fun onCitySelected(cityId: String) {
        _uiState.update { it.copy(selectedCityId = cityId) }
    }

    override fun onSelectionDismissed() {
        _uiState.update { it.copy(selectedCityId = null) }
    }

    /**
     * Opening a venue used to leave the map for the city screen, which lost your place for the
     * sake of a list. The shows now unfold inside the dock instead.
     */
    override fun onVenueToggled(venueId: String) {
        val next = if (_uiState.value.expandedVenueId == venueId) null else venueId
        expandedVenueId.value = next
        // Clearing the events with the id avoids a frame of the previous venue's shows under the
        // newly opened one.
        _uiState.update { it.copy(expandedVenueId = next, venueEvents = emptyList()) }
    }

    override fun onOverflowOpen() = _uiState.update { it.copy(showOverflowMenu = true) }
    override fun onOverflowDismiss() = _uiState.update { it.copy(showOverflowMenu = false) }

    override fun onBackUpRequested() {
        _uiState.update { it.copy(showOverflowMenu = false) }
        _effects.trySend(WorldMapEffect.CreateBackupFile("artistpin-backup-${LocalDate.now()}.json"))
    }

    override fun onRestoreRequested() {
        _uiState.update { it.copy(showOverflowMenu = false) }
        _effects.trySend(WorldMapEffect.OpenBackupFile)
    }

    override fun onBackupDestinationPicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupRunning = true) }
            val message = when (val contents = backupRepository.export()) {
                is DataResult.Failure -> contents.error.toUserMessage()
                is DataResult.Success -> when (val written = backupFileStore.write(uri, contents.data)) {
                    is DataResult.Failure -> written.error.toUserMessage()
                    is DataResult.Success -> "Backed up ${_uiState.value.totalShows} shows"
                }
            }
            _uiState.update { it.copy(isBackupRunning = false) }
            _effects.trySend(WorldMapEffect.ShowMessage(message))
        }
    }

    /** Parses first and only stores the result — nothing is replaced until the user confirms. */
    override fun onRestoreSourcePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupRunning = true) }
            val result = when (val contents = backupFileStore.read(uri)) {
                is DataResult.Failure -> DataResult.Failure(contents.error)
                is DataResult.Success -> backupRepository.parse(contents.data)
            }
            _uiState.update { it.copy(isBackupRunning = false) }
            when (result) {
                is DataResult.Success -> _uiState.update { it.copy(pendingRestore = result.data) }
                is DataResult.Failure -> _effects.trySend(
                    WorldMapEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }

    /**
     * The one-time migration onto the account. Safe to press twice: the import keys on the
     * original event ids, so a second run reports everything as already there rather than
     * duplicating the library. Nothing local is deleted or changed.
     */
    override fun onUploadLibraryRequested() {
        viewModelScope.launch {
            _uiState.update { it.copy(showOverflowMenu = false, isBackupRunning = true) }
            val message = when (val result = libraryMigrator.uploadLocalLibrary()) {
                is DataResult.Failure -> result.error.toUserMessage()
                is DataResult.Success -> with(result.data) {
                    when {
                        nothingToDo -> "Nothing to upload yet"
                        imported == 0 -> "Already up to date — $skipped shows are in your account"
                        skipped == 0 -> "Uploaded $imported shows"
                        else -> "Uploaded $imported shows, $skipped were already there"
                    }
                }
            }
            _uiState.update { it.copy(isBackupRunning = false) }
            _effects.trySend(WorldMapEffect.ShowMessage(message))
        }
    }

    /**
     * Sync on demand. The worker already runs when connectivity returns, so this exists for the
     * case the worker cannot help with: standing somewhere with signal, wanting to know *now*
     * whether the shows made it, rather than trusting that they will.
     */
    override fun onSyncNowRequested() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(showOverflowMenu = false, isSyncing = true) }
            val message = when (val result = librarySync.syncNow()) {
                is DataResult.Failure -> result.error.toUserMessage()
                is DataResult.Success -> with(result.data) {
                    when {
                        !isFullySynced -> "$stillPending change(s) still waiting — will retry"
                        pushed > 0 -> "Synced $pushed change(s)"
                        else -> "Everything is up to date"
                    }
                }
            }
            _uiState.update { it.copy(isSyncing = false) }
            _effects.trySend(WorldMapEffect.ShowMessage(message))
        }
    }

    override fun onRestoreDismissed() = _uiState.update { it.copy(pendingRestore = null) }

    override fun onRestoreConfirmed() {
        val backup = _uiState.value.pendingRestore ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRestore = null, isBackupRunning = true) }
            val message = when (val result = backupRepository.restore(backup)) {
                // Reports what the restore did, not what the file held. On the merging
                // implementation those differ constantly — restoring a backup of a library that is
                // already intact applies nothing, and claiming otherwise would make the fail-safe
                // untrustworthy at exactly the moment someone is checking whether it works.
                is DataResult.Success -> with(result.data) {
                    when {
                        applied == 0 && skipped > 0 ->
                            "Nothing was missing — all $skipped shows are already here"
                        applied == 0 -> "Nothing to restore"
                        skipped == 0 -> "Restored $applied shows"
                        else -> "Restored $applied shows, $skipped were already here"
                    }
                }
                is DataResult.Failure -> result.error.toUserMessage()
            }
            _uiState.update { it.copy(isBackupRunning = false) }
            _effects.trySend(WorldMapEffect.ShowMessage(message))
        }
    }
}
