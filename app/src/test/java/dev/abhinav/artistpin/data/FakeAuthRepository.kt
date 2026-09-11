package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.AuthState
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A signed-in session by default, because almost everything under test assumes one. */
class FakeAuthRepository(
    userId: String = "user-1",
) : AuthRepository {

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedIn(userId, "user@example.com"))
    override val state: StateFlow<AuthState> = _state

    fun setState(state: AuthState) {
        _state.value = state
    }

    fun signOutLocally() {
        _state.value = AuthState.SignedOut
    }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String) =
        DataResult.Success(Unit)

    override suspend fun signOut(): DataResult<Unit> {
        signOutLocally()
        return DataResult.Success(Unit)
    }

    override suspend fun retryRefresh() = DataResult.Success(Unit)

    override suspend fun accessToken(): String? = "fake-token"
}
