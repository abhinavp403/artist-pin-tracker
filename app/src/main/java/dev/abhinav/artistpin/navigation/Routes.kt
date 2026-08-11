package dev.abhinav.artistpin.navigation

import kotlinx.serialization.Serializable

/** The map-first home. Map and Artists are tabs within it, not separate destinations. */
@Serializable
data object HomeRoute

@Serializable
data class EventDetailRoute(val eventId: String)

@Serializable
data class ArtistDetailRoute(val artistId: String)

/** [eventId] null means "new show"; otherwise the screen edits that event. */
@Serializable
data class EventEditRoute(val eventId: String? = null)
