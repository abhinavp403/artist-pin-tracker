package dev.abhinav.artistpin.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.data.ConcertRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: LibraryStats = LibraryStats(),
)

class StatsViewModel(
    repository: ConcertRepository,
) : ViewModel() {

    // isLoading starts true and is only cleared by a real emission, so an empty library reads as
    // "you have no shows yet" rather than flashing zeroes at someone who has fifty.
    val uiState: StateFlow<StatsUiState> = repository.observeStats()
        .map { StatsUiState(isLoading = false, stats = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
