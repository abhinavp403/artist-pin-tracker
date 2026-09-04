package dev.abhinav.artistpin.data.backend

import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistBilling
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.EventMedia
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenuePin
import dev.abhinav.artistpin.data.GENRE_SEPARATOR
import dev.abhinav.artistpin.data.NAME_SEPARATOR
import java.time.Instant
import java.time.LocalDate

/**
 * Wire shapes to domain models — the twin of `Mappers.kt`, which does the same job for Room.
 *
 * Both files land on the identical `core.model` types, which is what makes B6's swap possible at
 * all: every screen already speaks `CityPin` and `ConcertEvent` and cannot tell which side of this
 * file produced them.
 */

/** Postgres `date` arrives as ISO-8601. Bad or absent dates become null rather than throwing. */
internal fun String?.toLocalDateOrNull(): LocalDate? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun String?.splitNames(separator: String): List<String> =
    this?.split(separator)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

fun CityDto.toModel() = City(id = id, name = name, country = country, region = region)

fun VenueDto.toModel() = Venue(
    id = id,
    name = name,
    cityId = cityId,
    latitude = latitude,
    longitude = longitude,
    address = address,
)

fun ArtistDto.toModel() = Artist(
    id = id,
    name = name,
    imageUrl = imageUrl,
    genres = genres.splitNames(GENRE_SEPARATOR),
    spotifyUrl = spotifyUrl,
)

fun CityPinDto.toModel() = CityPin(
    city = City(id = id, name = name, country = country, region = region),
    latitude = latitude,
    longitude = longitude,
    eventCount = eventCount,
    venueCount = venueCount,
    lastEventDate = lastEventDate.toLocalDateOrNull(),
)

fun VenuePinDto.toModel() = VenuePin(
    venue = Venue(
        id = id,
        name = name,
        cityId = cityId,
        latitude = latitude,
        longitude = longitude,
        address = address,
    ),
    eventCount = eventCount,
    lastEventDate = lastEventDate.toLocalDateOrNull(),
    headlinerName = headlinerName,
    headlinerImageUrl = headlinerImageUrl,
)

fun EventSummaryDto.toModel(): EventSummary {
    val headliners = headlinerNames.splitNames(NAME_SEPARATOR)
    val support = supportNames.splitNames(NAME_SEPARATOR)
    return EventSummary(
        id = id,
        // A summary with no parseable date would break every list that sorts on it, so this is the
        // one field that falls back to a value rather than null.
        date = eventDate.toLocalDateOrNull() ?: LocalDate.EPOCH,
        displayTitle = title?.takeIf { it.isNotBlank() }
            ?: headliners.joinToString(", ").takeIf { it.isNotEmpty() }
            ?: support.joinToString(", ").takeIf { it.isNotEmpty() }
            ?: "Untitled show",
        venueId = venueId,
        venueName = venueName,
        cityName = cityName,
        // Headliners first, matching the Room mapper — the order is what the row renders.
        artistNames = headliners + support,
        mediaCount = mediaCount,
        thumbnailPath = thumbnailUrl,
        rating = rating,
    )
}

fun EventMediaDto.toModel() = EventMedia(
    id = id,
    eventId = eventId,
    // Until Milestone D moves media to object storage, local_path is the only one of these ever
    // populated. Preferring it keeps already-imported photos rendering from disk once remote_url
    // starts appearing alongside it, rather than pulling every thumbnail over the network.
    localPath = localPath ?: remoteUrl.orEmpty(),
    originalUri = originalUri,
    mimeType = mimeType,
    capturedAt = capturedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    sortIndex = sortIndex,
    remotePath = storagePath,
)

fun EventDetailDto.toModel(): ConcertEvent? {
    val city = venue.city ?: return null
    return ConcertEvent(
        id = id,
        date = eventDate.toLocalDateOrNull() ?: return null,
        venue = venue.toModel(),
        city = city.toModel(),
        artists = artists.map {
            ArtistBilling(
                artist = it.artist.toModel(),
                // An unrecognised billing value defaults to headliner rather than dropping the
                // artist off the bill — losing a name is worse than mislabelling one.
                billing = runCatching { Billing.valueOf(it.billing) }.getOrDefault(Billing.HEADLINER),
            )
        },
        media = media.sortedBy { it.sortIndex }.map { it.toModel() },
        title = title,
        notes = notes,
        rating = rating,
    )
}

fun ArtistSummaryDto.toModel() = ArtistSummary(
    artist = Artist(
        id = id,
        name = name,
        imageUrl = imageUrl,
        genres = genres.splitNames(GENRE_SEPARATOR),
        spotifyUrl = spotifyUrl,
    ),
    timesSeen = timesSeen,
    // Comma-delimited here, unlike the '||' everywhere else: this comes from
    // string_agg(distinct …) in artist_summaries, which has no separator argument to override.
    cityNames = cityNames.splitNames(","),
    firstSeen = firstSeenDate.toLocalDateOrNull(),
    lastSeen = lastSeenDate.toLocalDateOrNull(),
)

fun ArtistDeletionImpactDto.toModel() =
    ArtistDeletionImpact(showsAffected = showsAffected, showsDeleted = showsDeleted)
