package dev.abhinav.artistpin.core.auth

import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Who is signed in, if anyone.
 *
 * [Unknown] is not a nicety — it is the state that keeps the app from lying. A session is restored
 * from disk asynchronously, so for the first frames after launch the answer genuinely is not known
 * yet. Collapsing that into [SignedOut] would flash the sign-in screen at an already-signed-in user
 * on every cold start.
 */
sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState

    /**
     * There is a stored session but it could not be refreshed — offline, or access revoked.
     *
     * Kept separate from [Unknown] because the two need different screens. [Unknown] resolves on
     * its own in a moment and a spinner is the honest thing to show; this one may never resolve,
     * so a spinner would strand the user with no sign-in, no retry and no way out.
     */
    data object Unavailable : AuthState
}

/**
 * The seam the rest of the app sees. Kept as an interface for the same reason
 * `VenueSearchService` and `ArtistImageSource` are: one implementation today, and no screen should
 * have to know that it happens to be Supabase.
 */
interface AuthRepository {

    val state: StateFlow<AuthState>

    /**
     * Completes a Google sign-in that has already produced an id token.
     *
     * [rawNonce] is the *unhashed* nonce. Google was handed its SHA-256; the id token carries that
     * hash, and the backend hashes this value to compare. Sending the hash here instead is the
     * classic way to get an "invalid nonce" that looks like a server fault.
     */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String): DataResult<Unit>

    suspend fun signOut(): DataResult<Unit>

    /** Retries a failed session refresh, so [AuthState.Unavailable] is recoverable in place. */
    suspend fun retryRefresh(): DataResult<Unit>

    /** The bearer token for backend calls. Null when signed out. B5 is the first caller. */
    suspend fun accessToken(): String?
}
