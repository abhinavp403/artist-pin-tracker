package dev.abhinav.artistpin.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhinav.artistpin.R
import dev.abhinav.artistpin.core.designsystem.Pin
import org.koin.androidx.compose.koinViewModel

/**
 * The gate. Deliberately plain — one action, no tour, nothing to read. A sign-in screen is a toll
 * on the way to the thing the user opened the app for, so the only design goal is to be short.
 */
@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Pin.Page)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Pin.Fill),
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "ArtistPin",
                color = Pin.OnPage,
                fontSize = 30.sp,
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Every show you've been to, pinned to the place it happened.",
                color = Pin.OnPageSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(40.dp))

            if (uiState.isConfigured) {
                Button(
                    onClick = { context.findActivity()?.let(viewModel::onSignInClicked) },
                    enabled = !uiState.isBusy,
                    shape = RoundedCornerShape(Pin.RadiusButton),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Pin.OnPage,
                        contentColor = Pin.Page,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Pin.Page,
                        )
                    } else {
                        Text("Continue with Google", fontSize = 15.sp)
                    }
                }
            } else {
                // A build without GOOGLE_WEB_CLIENT_ID cannot sign anyone in. Saying so beats a
                // button that fails every time it is pressed.
                Text(
                    text = "Sign-in isn't configured in this build. " +
                        "Add GOOGLE_WEB_CLIENT_ID to local.properties.",
                    color = Pin.Destructive,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            uiState.error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = Pin.Destructive,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Credential Manager needs an Activity to host its sheet, and LocalContext is not guaranteed to be
 * one — under a dialog or in a preview it is a wrapper. Unwrapping is cheap; assuming is a crash.
 */
private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Shown when a stored session exists but cannot be refreshed — no connection, or access revoked.
 *
 * The screen exists because the alternative was a bare spinner: [AuthState.Unavailable] may never
 * resolve on its own, and a user in a basement venue would have been stranded with no sign-in, no
 * retry and no way out short of clearing app data. Both buttons here are exits — one recovers in
 * place, the other abandons the session and returns to the gate.
 */
@Composable
fun AuthUnavailableScreen(
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    isRetrying: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pin.Page)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Can't reach your account",
                color = Pin.OnPage,
                fontSize = 20.sp,
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "You're signed in, but we couldn't refresh your session. " +
                    "Check your connection and try again.",
                color = Pin.OnPageSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onRetry,
                enabled = !isRetrying,
                shape = RoundedCornerShape(Pin.RadiusButton),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Pin.OnPage,
                    contentColor = Pin.Page,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Pin.Page,
                    )
                } else {
                    Text("Try again", fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out", color = Pin.OnPageSecondary, fontSize = 14.sp)
            }
        }
    }
}

/** Shown while the stored session is being restored, so a cold start doesn't flash the gate. */
@Composable
fun AuthLoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pin.Page),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Pin.OnPageSecondary, strokeWidth = 2.dp)
    }
}
