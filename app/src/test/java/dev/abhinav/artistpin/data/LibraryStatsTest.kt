package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.database.EventStatsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month

/**
 * The stats screen is arithmetic over the library, and arithmetic is where a screen quietly lies:
 * a gap measured against an unsorted list, a month counted per-year instead of across years, a
 * country counted once per show. Each case below is one of those.
 */
class LibraryStatsTest {

    private var seq = 0

    private fun show(
        date: String,
        city: String = "queens",
        country: String = "United States",
        venue: String = "knockdown-${seq++}",
    ) = EventStatsRow(
        dateEpochDay = LocalDate.parse(date).toEpochDay(),
        cityId = city,
        country = country,
        venueId = venue,
    )

    @Test
    fun `an empty library reports nothing rather than zeroes it cannot justify`() {
        val stats = emptyList<EventStatsRow>().toLibraryStats()

        assertTrue(stats.isEmpty)
        assertNull(stats.busiestMonth)
        assertNull(stats.longestGap)
        assertNull(stats.firstShow)
        assertEquals(emptyList<Any>(), stats.countries)
    }

    @Test
    fun `shows per year counts each year and lists the most recent first`() {
        val stats = listOf(
            show("2023-05-01"),
            show("2025-02-11"),
            show("2023-11-30"),
            show("2024-07-04"),
            show("2023-01-02"),
        ).toLibraryStats()

        assertEquals(listOf(2025, 2024, 2023), stats.showsPerYear.map { it.year })
        assertEquals(listOf(1, 1, 3), stats.showsPerYear.map { it.shows })
        assertEquals(5, stats.totalShows)
    }

    @Test
    fun `the busiest month sums the same month across different years`() {
        val stats = listOf(
            show("2023-06-10"),
            show("2024-06-14"),
            show("2025-06-21"),
            // August leads in any single year but loses once June is added up.
            show("2024-08-01"),
            show("2024-08-09"),
        ).toLibraryStats()

        assertEquals(Month.JUNE, stats.busiestMonth?.month)
        assertEquals(3, stats.busiestMonth?.shows)
    }

    @Test
    fun `a tie for busiest month resolves to the earlier month every time`() {
        val stats = listOf(
            show("2024-09-05"),
            show("2024-03-05"),
        ).toLibraryStats()

        // Without an explicit order this depends on hash iteration order, which is stable enough
        // to pass by luck and change under you later.
        assertEquals(Month.MARCH, stats.busiestMonth?.month)
    }

    @Test
    fun `the longest gap is between consecutive shows, not between the first and last`() {
        val stats = listOf(
            show("2022-01-01"),
            show("2022-01-20"),
            // The long silence.
            show("2024-03-01"),
            show("2024-03-10"),
        ).toLibraryStats()

        val gap = requireNotNull(stats.longestGap)
        assertEquals(LocalDate.parse("2022-01-20"), gap.from)
        assertEquals(LocalDate.parse("2024-03-01"), gap.to)
        assertEquals(771, gap.days)
    }

    @Test
    fun `the gap is found even when the rows arrive out of order`() {
        val ordered = listOf(show("2022-01-01"), show("2023-06-01"), show("2023-06-15"))
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])

        assertEquals(ordered.toLibraryStats().longestGap, shuffled.toLibraryStats().longestGap)
    }

    @Test
    fun `one show has no gap after it`() {
        val stats = listOf(show("2024-04-04")).toLibraryStats()

        assertNull(stats.longestGap)
        assertEquals(1, stats.totalShows)
        assertEquals(LocalDate.parse("2024-04-04"), stats.firstShow)
        assertEquals(stats.firstShow, stats.lastShow)
    }

    @Test
    fun `the passport counts places once, however many shows happened there`() {
        val stats = listOf(
            show("2024-01-01", city = "queens", country = "United States", venue = "knockdown"),
            show("2024-02-01", city = "queens", country = "United States", venue = "knockdown"),
            show("2024-03-01", city = "queens", country = "United States", venue = "brooklyn-mirage"),
            show("2024-04-01", city = "berlin", country = "Germany", venue = "berghain"),
        ).toLibraryStats()

        assertEquals(4, stats.totalShows)
        assertEquals(3, stats.venueCount)
        assertEquals(2, stats.cityCount)
        assertEquals(2, stats.countryCount)
    }

    @Test
    fun `each country carries its own shows and the cities inside it`() {
        val stats = listOf(
            show("2024-01-01", city = "queens", country = "United States"),
            show("2024-02-01", city = "chicago", country = "United States"),
            show("2024-03-01", city = "chicago", country = "United States"),
            show("2024-04-01", city = "berlin", country = "Germany"),
        ).toLibraryStats()

        val us = stats.countries.first { it.country == "United States" }
        assertEquals(3, us.shows)
        assertEquals("Chicago twice is still one city", 2, us.cities)
        // Most shows first — the passport reads as a ranking, not as insertion order.
        assertEquals(listOf("United States", "Germany"), stats.countries.map { it.country })
    }

    @Test
    fun `countries with the same number of shows sort alphabetically`() {
        val stats = listOf(
            show("2024-01-01", city = "lisbon", country = "Portugal"),
            show("2024-02-01", city = "berlin", country = "Germany"),
        ).toLibraryStats()

        // Otherwise the same library renders in a different order from one reading to the next.
        assertEquals(listOf("Germany", "Portugal"), stats.countries.map { it.country })
    }
}
