package dev.abhinav.artistpin.feature.eventdetail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.designsystem.toUserMessage
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.navigation.EventDetailRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventDetailUiState(
    val isLoading: Boolean = true,
    val event: ConcertEvent? = null,
    val isImporting: Boolean = false,
    val viewerStartIndex: Int? = null,
    val isReelPlaying: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
) {
    val media get() = event?.media.orEmpty()
    val isViewerOpen: Boolean get() = viewerStartIndex != null
    val hasMedia: Boolean get() = media.isNotEmpty()
}

sealed interface EventDetailEffect {
    data class ShowMessage(val message: String) : EventDetailEffect
    data object NavigateBack : EventDetailEffect

    /** Carries the count rather than a sentence so the UI can pluralize it against resources. */
    data class MediaAdded(val count: Int) : EventDetailEffect
}

class EventDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConcertRepository,
) : ViewModel(), EventDetailActions {

    private val eventId = savedStateHandle.toRoute<EventDetailRoute>().eventId

    private val _uiState = MutableStateFlow(EventDetailUiState())
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    private val _effects = Channel<EventDetailEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeEvent(eventId).collect { event ->
                _uiState.update { it.copy(isLoading = false, event = event) }
            }
        }
    }

    override fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val result = repository.addMedia(eventId, uris)
            _uiState.update { it.copy(isImporting = false) }
            when (result) {
                is DataResult.Success -> _effects.trySend(
                    EventDetailEffect.MediaAdded(result.data),
                )
                is DataResult.Failure -> _effects.trySend(
                    EventDetailEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }

    override fun onRemoveMedia(mediaId: String) {
        val media = _uiState.value.media.firstOrNull { it.id == mediaId } ?: return
        viewModelScope.launch {
            when (val result = repository.removeMedia(media.id, media.localPath)) {
                is DataResult.Success -> _uiState.update { it.copy(viewerStartIndex = null, isReelPlaying = false) }
                is DataResult.Failure -> _effects.trySend(
                    EventDetailEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }

    override fun onOpenViewer(index: Int) {
        _uiState.update { it.copy(viewerStartIndex = index, isReelPlaying = false) }
    }

    override fun onPlayReel() {
        _uiState.update { it.copy(viewerStartIndex = 0, isReelPlaying = true) }
    }

    override fun onCloseViewer() {
        _uiState.update { it.copy(viewerStartIndex = null, isReelPlaying = false) }
    }

    override fun onToggleReelPlayback() {
        _uiState.update { it.copy(isReelPlaying = !it.isReelPlaying) }
    }

    override fun onDeleteRequested() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    override fun onDeleteDismissed() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    override fun onDeleteConfirmed() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            when (val result = repository.deleteEvent(eventId)) {
                is DataResult.Success -> _effects.trySend(EventDetailEffect.NavigateBack)
                is DataResult.Failure -> _effects.trySend(
                    EventDetailEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }
}
