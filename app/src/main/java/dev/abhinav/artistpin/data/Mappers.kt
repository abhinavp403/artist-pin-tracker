package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.database.ArtistEntity
import dev.abhinav.artistpin.core.database.ArtistSummaryRow
import dev.abhinav.artistpin.core.database.CityEntity
import dev.abhinav.artistpin.core.database.CityPinRow
import dev.abhinav.artistpin.core.database.EventMediaEntity
import dev.abhinav.artistpin.core.database.EventSummaryRow
import dev.abhinav.artistpin.core.database.EventWithDetails
import dev.abhinav.artistpin.core.database.VenueEntity
import dev.abhinav.artistpin.core.database.VenuePinRow
import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistBilling
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.EventMedia
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenuePin
import java.time.LocalDate

/** GROUP_CONCAT separator — venue and artist names routinely contain commas. */
const val NAME_SEPARATOR = "||"

/** Genres are stored in one column; a genre can itself contain a comma ("drum and bass, uk"). */
const val GENRE_SEPARATOR = "||"

fun Long?.toLocalDateOrNull(): LocalDate? = this?.let(LocalDate::ofEpochDay)

fun CityEntity.toModel() = City(id = id, name = name, country = country, region = region)

fun VenueEntity.toModel() = Venue(
    id = id,
    name = name,
    cityId = cityId,
    latitude = latitude,
    longitude = longitude,
    address = address,
)

fun ArtistEntity.toModel() = Artist(
    id = id,
    name = name,
    imageUrl = imageUrl,
    genres = genres?.split(GENRE_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty(),
    spotifyUrl = spotifyUrl,
)

fun EventMediaEntity.toModel() = EventMedia(
    id = id,
    eventId = eventId,
    localPath = localPath,
    originalUri = originalUri,
    mimeType = mimeType,
    capturedAt = capturedAt,
    sortIndex = sortIndex,
    remotePath = remotePath,
)

fun CityPinRow.toModel() = CityPin(
    city = city.toModel(),
    latitude = latitude,
    longitude = longitude,
    eventCount = eventCount,
    venueCount = venueCount,
    lastEventDate = lastEventEpochDay.toLocalDateOrNull(),
)

fun VenuePinRow.toModel() = VenuePin(
    venue = venue.toModel(),
    eventCount = eventCount,
    lastEventDate = lastEventEpochDay.toLocalDateOrNull(),
    headlinerName = headlinerName,
    headlinerImageUrl = headlinerImageUrl,
)

fun EventSummaryRow.toModel(): EventSummary {
    fun String?.toNames() = this?.split(NAME_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    val headliners = headlinerNames.toNames()
    val names = headliners + supportNames.toNames()
    return EventSummary(
        id = id,
        date = LocalDate.ofEpochDay(dateEpochDay),
        // Headliners name the night; support acts are listed but never in the title.
        displayTitle = title?.takeIf { it.isNotBlank() }
            ?: headliners.joinToString(", ").takeIf { it.isNotBlank() }
            ?: names.joinToString(", ").takeIf { it.isNotBlank() }
            ?: "Untitled show",
        venueId = venueId,
        venueName = venueName,
        cityName = cityName,
        artistNames = names,
        mediaCount = mediaCount,
        thumbnailPath = thumbnailPath,
        thumbnailRemotePath = thumbnailRemotePath,
        rating = rating,
    )
}

fun ArtistSummaryRow.toModel() = ArtistSummary(
    artist = artist.toModel(),
    timesSeen = timesSeen,
    cityNames = cityNames?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
    firstSeen = firstSeenEpochDay.toLocalDateOrNull(),
    lastSeen = lastSeenEpochDay.toLocalDateOrNull(),
)

fun EventWithDetails.toModel(billingByArtistId: Map<String, Billing>) = ConcertEvent(
    id = event.id,
    date = LocalDate.ofEpochDay(event.dateEpochDay),
    venue = venue.venue.toModel(),
    city = venue.city.toModel(),
    // Room's @Relation returns rows in whatever order SQLite hands back, so billing order
    // has to be imposed here rather than assumed.
    artists = artists.map { entity ->
        ArtistBilling(
            artist = entity.toModel(),
            billing = billingByArtistId[entity.id] ?: Billing.HEADLINER,
        )
    }.sortedWith(compareBy({ it.billing != Billing.HEADLINER }, { it.artist.name })),
    media = media.sortedBy { it.sortIndex }.map { it.toModel() },
    title = event.title,
    notes = event.notes,
    rating = event.rating,
)
