package dev.abhinav.artistpin.core.model

import java.time.LocalDate

enum class Billing { HEADLINER, SUPPORT }

/** A plain lat/lng pair, so layers below the UI never have to depend on the Maps SDK. */
data class Coordinates(val latitude: Double, val longitude: Double)

data class City(
    val id: String,
    val name: String,
    val country: String,
    val region: String? = null,
) {
    val displayName: String get() = listOfNotNull(name, region, country).joinToString(", ")
}

data class Venue(
    val id: String,
    val name: String,
    val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val spotifyUrl: String? = null,
)

data class ArtistBilling(
    val artist: Artist,
    val billing: Billing,
)

data class EventMedia(
    val id: String,
    val eventId: String,
    val localPath: String,
    val originalUri: String,
    val mimeType: String,
    val capturedAt: Long?,
    val sortIndex: Int,
    /** Set once the file has reached object storage; null while it is still only on this device. */
    val remotePath: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** False while the bytes exist nowhere but this phone — the state Milestone D exists to end. */
    val isBackedUp: Boolean get() = remotePath != null
}

/** One night at one venue — the unit the whole app is organized around. */
data class ConcertEvent(
    val id: String,
    val date: LocalDate,
    val venue: Venue,
    val city: City,
    val artists: List<ArtistBilling>,
    val media: List<EventMedia>,
    val title: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
) {
    val headliners: List<Artist> get() = artists.filter { it.billing == Billing.HEADLINER }.map { it.artist }

    /** Falls back to the headliner names when the user didn't name the night themselves. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: headliners.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
            ?: artists.joinToString(", ") { it.artist.name }.takeIf { it.isNotBlank() }
            ?: "Untitled show"
}

/** A city marker on the world map. Position is the centroid of the venues visited there. */
data class CityPin(
    val city: City,
    val latitude: Double,
    val longitude: Double,
    val eventCount: Int,
    val venueCount: Int,
    val lastEventDate: LocalDate?,
)

/** A venue marker on a city map. */
data class VenuePin(
    val venue: Venue,
    val eventCount: Int,
    val lastEventDate: LocalDate?,
    val headlinerName: String?,
    val headlinerImageUrl: String?,
)

/** Row shown in lists — enough to render without loading every media item. */
data class EventSummary(
    val id: String,
    val date: LocalDate,
    val displayTitle: String,
    val venueId: String,
    val venueName: String,
    val cityName: String,
    val artistNames: List<String>,
    val mediaCount: Int,
    val thumbnailPath: String?,
    val rating: Int?,
)

data class ArtistDeletionImpact(
    val showsAffected: Int,
    val showsDeleted: Int,
)

data class ArtistSummary(
    val artist: Artist,
    val timesSeen: Int,
    val cityNames: List<String>,
    val firstSeen: LocalDate?,
    val lastSeen: LocalDate?,
)
