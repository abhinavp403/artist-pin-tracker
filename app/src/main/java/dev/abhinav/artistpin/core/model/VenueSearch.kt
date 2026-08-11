package dev.abhinav.artistpin.core.model

/** A venue lookup hit, before its coordinates have been fetched. */
data class VenueSuggestion(
    val placeId: String,
    val name: String,
    val address: String,
)

/** A venue lookup hit with everything the Where section needs, resolved in one tap. */
data class ResolvedVenue(
    val name: String,
    val address: String?,
    val city: String,
    val region: String?,
    val country: String,
    val latitude: Double,
    val longitude: Double,
)
