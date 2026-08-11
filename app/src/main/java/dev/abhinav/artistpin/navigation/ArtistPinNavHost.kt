package dev.abhinav.artistpin.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.abhinav.artistpin.feature.artists.ArtistDetailScreen
import dev.abhinav.artistpin.feature.eventdetail.EventDetailScreen
import dev.abhinav.artistpin.feature.eventedit.EventEditScreen
import dev.abhinav.artistpin.feature.home.HomeScreen
import dev.abhinav.artistpin.feature.home.rememberHomeState

/**
 * No app-level Scaffold and no bottom bar: the home screen carries its own floating dock, and
 * every other destination runs edge to edge and draws its own back control.
 */
@Composable
fun ArtistPinApp(navController: NavHostController = rememberNavController()) {
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
