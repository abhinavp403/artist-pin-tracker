package dev.abhinav.artistpin.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.AuthState
import dev.abhinav.artistpin.feature.artists.ArtistDetailScreen
import dev.abhinav.artistpin.feature.auth.AuthLoadingScreen
import dev.abhinav.artistpin.feature.auth.AuthUnavailableScreen
import dev.abhinav.artistpin.feature.auth.SignInScreen
import dev.abhinav.artistpin.feature.eventdetail.EventDetailScreen
import dev.abhinav.artistpin.feature.eventedit.EventEditScreen
import dev.abhinav.artistpin.feature.home.HomeScreen
import dev.abhinav.artistpin.feature.home.rememberHomeState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The session gate.
 *
 * Written as a branch *around* the NavHost rather than as a sign-in destination inside it. A route
 * would leave the graph — and every screen's state, including a half-filled Add-a-show form —
 * alive underneath the gate after a sign-out, reachable with the back gesture and holding data
 * belonging to the account that just left. Branching here means signing out disposes the entire
 * graph, so there is no back stack to get wrong and no per-screen cleanup to remember.
 */
@Composable
fun ArtistPinRoot() {
    val auth: AuthRepository = koinInject()
    val state by auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var isRetrying by remember { mutableStateOf(false) }

    when (state) {
        // Not the sign-in screen: the stored session is still loading, and showing the gate here
        // would flash it at an already-signed-in user on every cold start.
        AuthState.Unknown -> AuthLoadingScreen()
        AuthState.SignedOut -> SignInScreen()

        // Signed in, but the session could not be refreshed. Both actions are exits: retrying
        // recovers in place when the connection comes back, signing out abandons the session and
        // returns to the gate. A spinner here would be a dead end.
        AuthState.Unavailable -> AuthUnavailableScreen(
            isRetrying = isRetrying,
            onRetry = {
                scope.launch {
                    isRetrying = true
                    auth.retryRefresh()
                    isRetrying = false
                }
            },
            onSignOut = { scope.launch { auth.signOut() } },
        )

        is AuthState.SignedIn -> ArtistPinApp(
            onSignOut = { scope.launch { auth.signOut() } },
        )
    }
}

/**
 * No app-level Scaffold and no bottom bar: the home screen carries its own floating dock, and
 * every other destination runs edge to edge and draws its own back control.
 */
@Composable
fun ArtistPinApp(
    onSignOut: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Held here, outside the graph, so a trip to a show and back finds the map where it was.
    val homeState = rememberHomeState()

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<HomeRoute> {
            HomeScreen(
                state = homeState,
                onOpenArtist = { navController.navigate(ArtistDetailRoute(it)) },
                onOpenEvent = { navController.navigate(EventDetailRoute(it)) },
                onAddEvent = { navController.navigate(EventEditRoute()) },
                onSignOut = onSignOut,
            )
        }

        composable<EventDetailRoute> {
            EventDetailScreen(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(EventEditRoute(it)) },
                onOpenArtist = { navController.navigate(ArtistDetailRoute(it)) },
            )
        }

        composable<ArtistDetailRoute> {
            ArtistDetailScreen(
                onBack = navController::popBackStack,
                onOpenEvent = { navController.navigate(EventDetailRoute(it)) },
            )
        }

        composable<EventEditRoute> {
            EventEditScreen(
                onBack = navController::popBackStack,
                onSaved = { eventId ->
                    navController.navigate(EventDetailRoute(eventId)) {
                        popUpTo(EventEditRoute::class) { inclusive = true }
                    }
                },
            )
        }
    }
}
