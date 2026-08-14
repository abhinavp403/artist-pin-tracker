package dev.abhinav.artistpin.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.CredentialError
import dev.abhinav.artistpin.core.auth.CredentialFailure
import dev.abhinav.artistpin.core.auth.GoogleCredentialClient
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val isBusy: Boolean = false,
    val error: String? = null,
    /** False when GOOGLE_WEB_CLIENT_ID is missing, which is a build problem, not a user problem. */
    val isConfigured: Boolean = true,
)

class SignInViewModel(
    private val auth: AuthRepository,
    private val google: GoogleCredentialClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState(isConfigured = google.isConfigured))
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    /**
     * [activityContext] is passed in per call rather than held: Credential Manager needs an
     * Activity to host its sheet, and a ViewModel outlives Activities by design.
     */
    fun onSignInClicked(activityContext: Context) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null) }

        viewModelScope.launch {
            val token = google.requestIdToken(activityContext).getOrElse { cause ->
                val failure = (cause as? CredentialError)?.failure
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        // Dismissing the account sheet is a decision, not a failure. Showing an
                        // error for it would blame the user for changing their mind.
                        error = when (failure) {
                            CredentialFailure.Cancelled -> null
                            CredentialFailure.NoAccount ->
                                "No Google account on this device. Add one in Settings, then try again."
                            is CredentialFailure.Unavailable ->
                                "Google sign-in isn't available right now. Try again in a moment."
                            null -> "Something went wrong signing in."
                        },
                    )
                }
                return@launch
            }

            when (val result = auth.signInWithGoogle(token.idToken, token.rawNonce)) {
                is DataResult.Success ->
                    // No navigation here. The gate observes AuthState and swaps the screen out
                    // when the session lands, so success is simply this screen ceasing to exist.
                    _uiState.update { it.copy(isBusy = false) }

                is DataResult.Failure -> _uiState.update {
                    it.copy(isBusy = false, error = messageFor(result.error))
                }
            }
        }
    }

    fun onErrorDismissed() = _uiState.update { it.copy(error = null) }

    private fun messageFor(error: DataError): String = when (error) {
        is DataError.Network -> "Couldn't reach the server. Check your connection and try again."
        else -> "Couldn't sign you in. Try again."
    }
}
