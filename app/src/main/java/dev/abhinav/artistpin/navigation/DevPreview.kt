package dev.abhinav.artistpin.navigation

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.RoomBackupRepository
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

private const val TAG = "ArtistPinDev"
private const val SEED_ASSET = "dev-library.json"

/**
 * The app with the sign-in gate removed and a library already in it.
 *
 * Exists so the emulator can show real screens without an account: the gate sits in front of
 * everything, and the map is empty behind it on a fresh install, which makes any screen built on
 * the library impossible to look at. Reachable only from a debug build with DEV_SKIP_AUTH in
 * local.properties — the release build compiles the flag as a literal false, so this whole branch
 * is stripped from anything shippable.
 *
 * The seed is restored through [RoomBackupRepository] rather than through the bound [LibraryBackup],
 * which on a backend build would send it to the server. This is local, and it stays local.
 */
@Composable
fun DevPreviewApp() {
    val context = LocalContext.current
    val repository: ConcertRepository = koinInject()
    val localBackup: RoomBackupRepository = koinInject()
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Only when there is nothing to look at, so a device that already has shows — including
        // anything added while poking around — is never overwritten.
        if (repository.observeAllEvents().first().isEmpty()) {
            seedFrom(context, localBackup)
        }
        seeded = true
    }

    if (!seeded) {
        Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    ArtistPinApp(onSignOut = {})
}

private suspend fun seedFrom(context: Context, backup: RoomBackupRepository) {
    val json = runCatching { context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() } }
        .getOrElse {
            Log.w(TAG, "No $SEED_ASSET in debug assets; starting empty")
            return
        }
    when (val parsed = backup.parse(json)) {
        is DataResult.Success -> {
            backup.restore(parsed.data)
            Log.d(TAG, "Seeded ${parsed.data.summary.shows} shows from $SEED_ASSET")
        }

        is DataResult.Failure -> Log.w(TAG, "Could not parse $SEED_ASSET: ${parsed.error}")
    }
}
