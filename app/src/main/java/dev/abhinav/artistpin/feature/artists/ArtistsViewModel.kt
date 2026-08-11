package dev.abhinav.artistpin.feature.artists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.abhinav.artistpin.core.designsystem.toUserMessage
import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.preferences.SettingsStore
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.getOrNull
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.navigation.ArtistDetailRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class ArtistSort(val label: String) {
    MOST_SEEN("Most seen"),
    NAME("Name (A\u2013Z)"),
    RECENTLY_SEEN("Recently seen"),
    FIRST_SEEN("First seen"),
    CITY("City"),
}

data class ArtistsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val artists: List<ArtistSummary> = emptyList(),
    val sort: ArtistSort = ArtistSort.MOST_SEEN,
    val showSortMenu: Boolean = false,
) {
    val visibleArtists: List<ArtistSummary>
        get() = artists
            .filter { query.isBlank() || it.artist.name.contains(query, ignoreCase = true) }
            .sortedWith(sort.comparator)

    /**
     * The visible artists cut into labelled runs, one heading per ordering's own key — the year,
     * the city, the band of show counts, the initial.
     */
    val sections: List<ArtistSection>
        get() {
            val visible = visibleArtists
            val key: (ArtistSummary) -> String = when (sort) {
                ArtistSort.MOST_SEEN -> { it -> showBand(it.timesSeen) }
                ArtistSort.NAME -> { it -> it.artist.name.initialGroup() }
                ArtistSort.RECENTLY_SEEN -> { it -> it.lastSeen?.year?.toString() ?: UNDATED }
                ArtistSort.FIRST_SEEN -> { it -> it.firstSeen?.year?.toString() ?: UNDATED }
                ArtistSort.CITY -> { it -> it.cityNames.firstOrNull() ?: UNKNOWN_CITY }
            }
            // groupBy keeps insertion order and the list is already sorted, so the runs come out
            // in the order the comparator put them in.
            return visible.groupBy(key).map { (title, artists) -> ArtistSection(title, artists) }
        }

    val isEmpty: Boolean get() = !isLoading && artists.isEmpty()
}

/** Nulls sort last on every date ordering, so an artist with no dates never leads the list. */
private val ArtistSort.comparator: Comparator<ArtistSummary>
    get() = when (this) {
        ArtistSort.MOST_SEEN -> compareByDescending<ArtistSummary> { it.timesSeen }
            .thenBy { it.artist.name.lowercase() }
        ArtistSort.NAME -> compareBy { it.artist.name.lowercase() }
        ArtistSort.RECENTLY_SEEN -> compareByDescending<ArtistSummary> { it.lastSeen ?: LocalDate.MIN }
            .thenBy { it.artist.name.lowercase() }
        ArtistSort.FIRST_SEEN -> compareBy<ArtistSummary> { it.firstSeen ?: LocalDate.MAX }
            .thenBy { it.artist.name.lowercase() }
        ArtistSort.CITY -> compareBy<ArtistSummary> { it.cityNames.firstOrNull()?.lowercase() ?: "\uffff" }
            .thenBy { it.artist.name.lowercase() }
    }

/** A run of artists under one heading. A null [title] means the ordering needs no headings. */
data class ArtistSection(val title: String?, val artists: List<ArtistSummary>)

/** Bands rather than exact counts: one heading per number would be a heading per row. */
private fun showBand(timesSeen: Int): String = when {
    timesSeen >= 10 -> "10+ shows"
    timesSeen >= 5 -> "5-9 shows"
    timesSeen >= 2 -> "2-4 shows"
    else -> "Seen once"
}

/** Digits and symbols share one bucket; nobody scans for "4" in an alphabetical list. */
private fun String.initialGroup(): String =
    firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"

private const val UNDATED = "No dates yet"
private const val UNKNOWN_CITY = "Unknown city"

sealed interface ArtistsEffect {
    data class ShowMessage(val message: String) : ArtistsEffect

    /** The artist this screen was about is gone, so the screen has to go too. */
    data object Deleted : ArtistsEffect
}

class ArtistsViewModel(
    private val repository: ConcertRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistsUiState())
    val uiState: StateFlow<ArtistsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeArtistSummaries().collect { artists ->
                _uiState.update { it.copy(isLoading = false, artists = artists) }
            }
        }
        // The ordering you chose last time is the one you meant; a name that no longer maps to a
        // sort — an older build's, or a corrupt file — falls back to the default rather than
        // failing.
        viewModelScope.launch {
            settings.artistSort.collect { stored ->
                val sort = ArtistSort.entries.firstOrNull { it.name == stored } ?: return@collect
                _uiState.update { it.copy(sort = sort) }
            }
        }
    }

    private val _effects = Channel<ArtistsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onSortMenuOpen() = _uiState.update { it.copy(showSortMenu = true) }
    fun onSortMenuDismiss() = _uiState.update { it.copy(showSortMenu = false) }
    fun onSortSelected(sort: ArtistSort) {
        _uiState.update { it.copy(sort = sort, showSortMenu = false) }
        viewModelScope.launch { settings.setArtistSort(sort.name) }
    }

}

data class ArtistDetailUiState(
    val isLoading: Boolean = true,
    val artist: Artist? = null,
    val events: List<EventSummary> = emptyList(),
    /** Non-null while the rename dialog is open; holds what has been typed so far. */
    val renameInput: String? = null,
    /** Non-null while the delete dialog is open; null impact means the count is still loading. */
    val deleteRequested: Boolean = false,
    val deletionImpact: ArtistDeletionImpact? = null,
) {
    val timesSeen: Int get() = events.size
    val canConfirmRename: Boolean
        get() = !renameInput.isNullOrBlank() && renameInput.trim() != artist?.name
}

class ArtistDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConcertRepository,
) : ViewModel() {

    private val artistId = savedStateHandle.toRoute<ArtistDetailRoute>().artistId

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ArtistsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** The impact is fetched before the dialog opens so it can state exactly what will be lost. */
    fun onDeleteRequested() {
        viewModelScope.launch {
            val impact = repository.previewArtistDeletion(artistId).getOrNull()
            _uiState.update { it.copy(deleteRequested = true, deletionImpact = impact) }
        }
    }

    fun onDeleteDismissed() =
        _uiState.update { it.copy(deleteRequested = false, deletionImpact = null) }

    fun onDeleteConfirmed() {
        _uiState.update { it.copy(deleteRequested = false, deletionImpact = null) }
        viewModelScope.launch {
            when (val result = repository.deleteArtist(artistId)) {
                is DataResult.Success -> _effects.trySend(ArtistsEffect.Deleted)
                is DataResult.Failure -> _effects.trySend(
                    ArtistsEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }

    fun onRenameRequested() =
        _uiState.update { it.copy(renameInput = it.artist?.name.orEmpty()) }

    fun onRenameInputChange(value: String) = _uiState.update { it.copy(renameInput = value) }

    fun onRenameDismissed() = _uiState.update { it.copy(renameInput = null) }

    /**
     * Renaming onto a name that already exists merges the two — the point of having this here at
     * all is folding a misspelling into the artist it should have been.
     */
    fun onRenameConfirmed() {
        val state = _uiState.value
        val newName = state.renameInput?.trim()
        if (newName.isNullOrEmpty() || !state.canConfirmRename) return
        viewModelScope.launch {
            _uiState.update { it.copy(renameInput = null) }
            val result = repository.renameArtist(artistId, newName)
            if (result is DataResult.Failure) {
                _effects.trySend(ArtistsEffect.ShowMessage(result.error.toUserMessage()))
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(
                repository.observeArtist(artistId),
                repository.observeEventsForArtist(artistId),
            ) { artist, events -> artist to events }
                .collect { (artist, events) ->
                    _uiState.update {
                        it.copy(isLoading = false, artist = artist, events = events)
                    }
                }
        }
    }
}
