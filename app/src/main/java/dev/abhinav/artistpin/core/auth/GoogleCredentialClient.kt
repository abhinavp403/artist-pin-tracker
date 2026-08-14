package dev.abhinav.artistpin.core.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/** An id token from Google, paired with the raw nonce the backend needs to verify it. */
data class GoogleIdToken(val idToken: String, val rawNonce: String)

/** Why a credential request produced no token. Separated because one of these is not an error. */
sealed interface CredentialFailure {
    /** The user dismissed the sheet. Expected, and must not be reported as a failure. */
    data object Cancelled : CredentialFailure

    /** No Google account on the device, or none usable. Actionable by the user. */
    data object NoAccount : CredentialFailure

    data class Unavailable(val message: String?) : CredentialFailure
}

/**
 * Wraps Credential Manager, which is the current way to sign in with Google — the old
 * `GoogleSignInClient` and its `startActivityForResult` dance are deprecated.
 */
class GoogleCredentialClient(private val serverClientId: String) {

    val isConfigured: Boolean get() = serverClientId.isNotBlank()

    /**
     * [activityContext] must be an Activity, not the application context: Credential Manager shows
     * a system sheet and needs something to host it. It is passed per call and never held, so this
     * class stays free of a leaked Activity reference.
     */
    suspend fun requestIdToken(activityContext: Context): Result<GoogleIdToken> {
        // Generated per attempt and never reused. The nonce is what stops a token minted for
        // somewhere else being replayed at us.
        val rawNonce = newNonce()

        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            // false so the sheet offers every Google account on the device. Filtering to
            // already-authorised accounts shows nothing at all on a first run, which reads to the
            // user as a broken button.
            .setFilterByAuthorizedAccounts(false)
            // Google receives the *hash*; the raw value goes to Supabase, which hashes it and
            // compares against the claim inside the token. Sending the same form to both is the
            // classic way to produce an "invalid nonce" that looks like a server fault.
            .setNonce(rawNonce.sha256())
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response = CredentialManager.create(activityContext)
                .getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                Result.success(GoogleIdToken(google.idToken, rawNonce))
            } else {
                Result.failure(CredentialError(CredentialFailure.Unavailable("Unexpected credential type")))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(CredentialError(CredentialFailure.Cancelled))
        } catch (e: NoCredentialException) {
            Result.failure(CredentialError(CredentialFailure.NoAccount))
        } catch (e: GetCredentialException) {
            Result.failure(CredentialError(CredentialFailure.Unavailable(e.message)))
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.toHex()
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class CredentialError(val failure: CredentialFailure) : Exception()
