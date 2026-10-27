package dev.abhinav.artistpin.navigation

import kotlinx.serialization.Serializable

/**
 * The map-first home. Map and Artists are tabs within it, not separate destinations.
 *
 * The two arguments are a request to arrive looking at something, which is how the stats screen
 * hands off: a venue box opens that venue's shows in the dock, a passport stamp frames that
 * country. Both default to null, so ordinary navigation here is unchanged and the start
 * destination stays `HomeRoute()`.
 */
@Serializable
data class HomeRoute(
    val focusVenueId: String? = null,
    val focusCountry: String? = null,
)

@Serializable
data class EventDetailRoute(val eventId: String)

@Serializable
data class ArtistDetailRoute(val artistId: String)

/** [eventId] null means "new show"; otherwise the screen edits that event. */
@Serializable
data class EventEditRoute(val eventId: String? = null)

/** Counts and streaks over the whole library. Takes no arguments: it is always everything. */
@Serializable
data object StatsRoute
