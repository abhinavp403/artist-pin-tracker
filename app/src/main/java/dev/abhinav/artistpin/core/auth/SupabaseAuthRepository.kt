package dev.abhinav.artistpin.core.auth

import android.util.Log
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

class SupabaseAuthRepository(
    private val client: SupabaseClient,
    scope: CoroutineScope,
) : AuthRepository {

    override val state: StateFlow<AuthState> = client.auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Authenticated -> AuthState.SignedIn(
                    userId = status.session.user?.id.orEmpty(),
                    email = status.session.user?.email,
                )

                // Only an explicit sign-out, or a session that has genuinely expired, sends the
                // user back to the gate.
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut

                // Deliberately *not* SignedOut. This means "we could not reach the server to find
                // out", which happens every time the app opens in a venue with no signal.
                // Reporting it as signed out would throw the user to the sign-in screen for being
                // underground — and once Milestone C makes the app offline-first, that would lock
                // them out of data sitting on their own device. It is not Unknown either: Unknown
                // resolves by itself in a moment, this may never resolve, and the two need
                // different screens.
                is SessionStatus.RefreshFailure -> AuthState.Unavailable
                SessionStatus.Initializing -> AuthState.Unknown
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Unknown)

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String): DataResult<Unit> =
        runCatchingAuth {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
                this.nonce = rawNonce
            }
        }

    override suspend fun signOut(): DataResult<Unit> = runCatchingAuth { client.auth.signOut() }

    override suspend fun retryRefresh(): DataResult<Unit> = runCatchingAuth {
        // Success flips sessionStatus back to Authenticated on its own, so the gate recovers
        // without the screen having to navigate anywhere.
        client.auth.refreshCurrentSession()
    }

    override suspend fun accessToken(): String? = client.auth.currentAccessTokenOrNull()

    private suspend fun runCatchingAuth(block: suspend () -> Unit): DataResult<Unit> =
        try {
            block()
            DataResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DataResult.Failure(DataError.Network(e.message))
        } catch (e: Exception) {
            // Logged rather than surfaced verbatim: auth failures carry provider detail that means
            // nothing to the user and occasionally echoes the token back.
            Log.w(TAG, "Sign-in failed", e)
            DataResult.Failure(DataError.Unknown(e.message))
        }

    private companion object {
        const val TAG = "ArtistPinAuth"
    }
}
