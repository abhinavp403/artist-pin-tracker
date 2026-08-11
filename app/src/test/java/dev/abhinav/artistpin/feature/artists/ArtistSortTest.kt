package dev.abhinav.artistpin.feature.artists

import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ArtistSortTest {

    private fun summary(
        name: String,
        timesSeen: Int = 1,
        first: LocalDate? = null,
        last: LocalDate? = null,
        cities: List<String> = emptyList(),
    ) = ArtistSummary(
        artist = Artist(id = name, name = name),
        timesSeen = timesSeen,
        cityNames = cities,
        firstSeen = first,
        lastSeen = last,
    )

    private val artists = listOf(
        summary(
            "Skrillex",
            timesSeen = 1,
            first = LocalDate.of(2020, 1, 1),
            last = LocalDate.of(2020, 1, 1),
            cities = listOf("Toronto"),
        ),
        summary(
            "fred again..",
            timesSeen = 5,
            first = LocalDate.of(2023, 6, 1),
            last = LocalDate.of(2026, 1, 1),
            cities = listOf("Berlin"),
        ),
        summary(
            "The Weeknd",
            timesSeen = 3,
            first = LocalDate.of(2018, 3, 1),
            last = LocalDate.of(2024, 5, 1),
            cities = listOf("Amsterdam"),
        ),
    )

    private fun sortedBy(sort: ArtistSort) =
        ArtistsUiState(artists = artists, sort = sort).visibleArtists.map { it.artist.name }

    @Test
    fun `most seen orders by count`() {
        assertEquals(listOf("fred again..", "The Weeknd", "Skrillex"), sortedBy(ArtistSort.MOST_SEEN))
    }

    /** Lowercase artist names must not sort after every capitalised one. */
    @Test
    fun `name sorting ignores case`() {
        assertEquals(listOf("fred again..", "Skrillex", "The Weeknd"), sortedBy(ArtistSort.NAME))
    }

    @Test
    fun `recently seen puts the newest first and first seen the oldest`() {
        assertEquals(listOf("fred again..", "The Weeknd", "Skrillex"), sortedBy(ArtistSort.RECENTLY_SEEN))
        assertEquals(listOf("The Weeknd", "Skrillex", "fred again.."), sortedBy(ArtistSort.FIRST_SEEN))
    }

    @Test
    fun `city sorting is alphabetical by first city`() {
        assertEquals(listOf("The Weeknd", "fred again..", "Skrillex"), sortedBy(ArtistSort.CITY))
    }

    /** An artist with no dates should never lead a date-ordered list. */
    @Test
    fun `artists without dates sort last on every date ordering`() {
        val withUnknown = artists + summary("Unknown Act", timesSeen = 2)

        assertEquals(
            "Unknown Act",
            ArtistsUiState(artists = withUnknown, sort = ArtistSort.RECENTLY_SEEN)
                .visibleArtists.last().artist.name,
        )
        assertEquals(
            "Unknown Act",
            ArtistsUiState(artists = withUnknown, sort = ArtistSort.FIRST_SEEN)
                .visibleArtists.last().artist.name,
        )
    }

    @Test
    fun `search filters before sorting and keeps the chosen order`() {
        // "d" matches "fred again.." and "The Weeknd" but not "Skrillex".
        val state = ArtistsUiState(artists = artists, sort = ArtistSort.NAME, query = "d")

        assertEquals(listOf("fred again..", "The Weeknd"), state.visibleArtists.map { it.artist.name })
    }

    private fun sectionsFor(sort: ArtistSort) =
        ArtistsUiState(artists = artists, sort = sort).sections

    /** Bands, not exact counts — one heading per number would be a heading per row. */
    @Test
    fun `most seen groups into bands of show counts`() {
        val sections = sectionsFor(ArtistSort.MOST_SEEN)

        assertEquals(listOf("5-9 shows", "2-4 shows", "Seen once"), sections.map { it.title })
        assertEquals(listOf("fred again.."), sections.first().artists.map { it.artist.name })
    }

    @Test
    fun `name groups by initial, ignoring case`() {
        val sections = sectionsFor(ArtistSort.NAME)

        assertEquals(listOf("F", "S", "T"), sections.map { it.title })
        assertEquals(listOf("fred again.."), sections.first().artists.map { it.artist.name })
    }

    /** A name starting with a digit or symbol still needs a bucket to live in. */
    @Test
    fun `names that do not start with a letter share one heading`() {
        val state = ArtistsUiState(artists = listOf(summary("2manydjs")), sort = ArtistSort.NAME)

        assertEquals(listOf("#"), state.sections.map { it.title })
    }

    @Test
    fun `recently seen groups by the year last seen, newest first`() {
        val sections = sectionsFor(ArtistSort.RECENTLY_SEEN)

        assertEquals(listOf("2026", "2024", "2020"), sections.map { it.title })
        assertEquals(listOf("fred again.."), sections.first().artists.map { it.artist.name })
    }

    @Test
    fun `first seen groups by the year first seen, oldest first`() {
        assertEquals(listOf("2018", "2020", "2023"), sectionsFor(ArtistSort.FIRST_SEEN).map { it.title })
    }

    @Test
    fun `city groups by the first city, alphabetically`() {
        val sections = sectionsFor(ArtistSort.CITY)

        assertEquals(listOf("Amsterdam", "Berlin", "Toronto"), sections.map { it.title })
        assertEquals(listOf("The Weeknd"), sections.first().artists.map { it.artist.name })
    }

    /** An artist with no shows yet still has to land somewhere rather than vanish from the list. */
    @Test
    fun `artists without dates or cities get their own heading`() {
        val state = ArtistsUiState(
            artists = artists + summary("Kinahau"),
            sort = ArtistSort.RECENTLY_SEEN,
        )

        val undated = state.sections.last()
        assertEquals("No dates yet", undated.title)
        assertEquals(listOf("Kinahau"), undated.artists.map { it.artist.name })
        assertEquals(4, state.sections.sumOf { it.artists.size })
    }

    /** Every artist belongs to exactly one run, whatever the ordering. */
    @Test
    fun `sections partition the visible artists`() {
        ArtistSort.entries.forEach { sort ->
            val state = ArtistsUiState(artists = artists, sort = sort)
            assertEquals(
                state.visibleArtists.map { it.artist.name },
                state.sections.flatMap { section -> section.artists.map { it.artist.name } },
            )
        }
    }
}
