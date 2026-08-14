package dev.abhinav.artistpin.data.backend

import dev.abhinav.artistpin.core.model.Billing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The mappers are where the backend's string-packed columns become domain types, and almost every
 * field here is delimited, nullable, or both. These are the cases that would otherwise be found by
 * a screen rendering something wrong.
 */
class BackendMappersTest {

    private fun artistDto(genres: String?) =
        ArtistDto(id = "a1", name = "Bedouin", genres = genres)

    @Test
    fun `null genres is not the same as empty genres, but both map to no genres`() {
        // The distinction is load-bearing in the database — null means "never looked up" and is
        // what queues the backfill — but by the time it reaches a screen both are simply "none".
        assertTrue(artistDto(null).toModel().genres.isEmpty())
        assertTrue(artistDto("").toModel().genres.isEmpty())
    }

    @Test
    fun `genres split on the pipe separator, not on commas`() {
        // "drum and bass, uk" is one genre. Splitting on commas would silently make it two.
        val genres = artistDto("melodic house||drum and bass, uk").toModel().genres
        assertEquals(listOf("melodic house", "drum and bass, uk"), genres)
    }

    private fun summaryDto(
        title: String? = null,
        headliners: String? = null,
        support: String? = null,
    ) = EventSummaryDto(
        id = "e1",
        eventDate = "2025-06-14",
        title = title,
        venueId = "v1",
        venueName = "Knockdown Center",
        cityId = "c1",
        cityName = "Queens",
        headlinerNames = headliners,
        supportNames = support,
    )

    @Test
    fun `headliners come before support in the artist list`() {
        val summary = summaryDto(headliners = "Dixon", support = "Bedouin||Adriatique").toModel()
        assertEquals(listOf("Dixon", "Bedouin", "Adriatique"), summary.artistNames)
    }

    @Test
    fun `display title falls back through headliners then support`() {
        assertEquals("Sunset Set", summaryDto(title = "Sunset Set", headliners = "Dixon").toModel().displayTitle)
        assertEquals("Dixon", summaryDto(headliners = "Dixon").toModel().displayTitle)
        assertEquals("Bedouin", summaryDto(support = "Bedouin").toModel().displayTitle)
        assertEquals("Untitled show", summaryDto().toModel().displayTitle)
    }

    @Test
    fun `a blank title does not win over the headliner`() {
        // Postgres nullifies empty strings on write, but a whitespace-only title survives it.
        assertEquals("Dixon", summaryDto(title = "   ", headliners = "Dixon").toModel().displayTitle)
    }

    @Test
    fun `dates parse from ISO-8601`() {
        assertEquals(LocalDate.of(2025, 6, 14), summaryDto().toModel().date)
    }

    @Test
    fun `an unparseable date does not drop the row out of a list`() {
        // Summaries are sorted by date, so null is not an option here — falling back keeps the
        // show visible and merely misplaced, which beats it vanishing.
        val broken = summaryDto().copy(eventDate = "not-a-date").toModel()
        assertEquals(LocalDate.EPOCH, broken.date)
    }

    private val venue = VenueDto(
        id = "v1",
        name = "Knockdown Center",
        cityId = "c1",
        latitude = 40.71,
        longitude = -73.92,
        city = CityDto(id = "c1", name = "Queens", country = "United States"),
    )

    private fun detailDto(
        venue: VenueDto = this.venue,
        artists: List<EventArtistDto> = emptyList(),
        media: List<EventMediaDto> = emptyList(),
    ) = EventDetailDto(
        id = "e1",
        eventDate = "2025-06-14",
        venue = venue,
        artists = artists,
        media = media,
    )

    @Test
    fun `a detail row without its embedded city maps to null rather than a half-built show`() {
        assertNull(detailDto(venue = venue.copy(city = null)).toModel())
    }

    @Test
    fun `an unrecognised billing value keeps the artist on the bill`() {
        // Losing a name is worse than mislabelling one, so this defaults rather than filtering.
        val event = detailDto(
            artists = listOf(EventArtistDto(billing = "SOMETHING_NEW", artist = artistDto(null))),
        ).toModel()

        assertEquals(1, event?.artists?.size)
        assertEquals(Billing.HEADLINER, event?.artists?.first()?.billing)
    }

    @Test
    fun `media is ordered by sort index regardless of the order it arrives in`() {
        val event = detailDto(
            media = listOf(mediaDto("m2", sortIndex = 2), mediaDto("m1", sortIndex = 1)),
        ).toModel()

        assertEquals(listOf("m1", "m2"), event?.media?.map { it.id })
    }

    @Test
    fun `local media is preferred over a remote copy`() {
        // Once Milestone D populates remote_url alongside local_path, already-imported photos
        // should keep rendering from disk instead of being pulled back over the network.
        val both = mediaDto("m1", localPath = "/data/photo.jpg", remoteUrl = "https://cdn/photo.jpg")
        assertEquals("/data/photo.jpg", both.toModel().localPath)

        val remoteOnly = mediaDto("m2", localPath = null, remoteUrl = "https://cdn/photo.jpg")
        assertEquals("https://cdn/photo.jpg", remoteOnly.toModel().localPath)
    }

    private fun mediaDto(
        id: String,
        sortIndex: Int = 0,
        localPath: String? = "/data/$id.jpg",
        remoteUrl: String? = null,
    ) = EventMediaDto(
        id = id,
        eventId = "e1",
        localPath = localPath,
        remoteUrl = remoteUrl,
        originalUri = "content://media/$id",
        mimeType = "image/jpeg",
        sortIndex = sortIndex,
    )

    @Test
    fun `artist summary cities split on commas, not pipes`() {
        // This one column really is comma-delimited: it comes from string_agg(distinct …), which
        // takes no separator argument, unlike every other aggregate in the views.
        val summary = ArtistSummaryDto(
            id = "a1",
            name = "Dixon",
            timesSeen = 3,
            cityNames = "Brooklyn,Queens,Miami Beach",
            firstSeenDate = "2019-08-02",
            lastSeenDate = "2025-06-14",
        ).toModel()

        assertEquals(listOf("Brooklyn", "Queens", "Miami Beach"), summary.cityNames)
        assertEquals(LocalDate.of(2019, 8, 2), summary.firstSeen)
        assertEquals(LocalDate.of(2025, 6, 14), summary.lastSeen)
    }

    @Test
    fun `an artist never seen has no first or last date`() {
        val summary = ArtistSummaryDto(id = "a1", name = "Dixon", timesSeen = 0).toModel()
        assertNull(summary.firstSeen)
        assertNull(summary.lastSeen)
        assertTrue(summary.cityNames.isEmpty())
    }
}
