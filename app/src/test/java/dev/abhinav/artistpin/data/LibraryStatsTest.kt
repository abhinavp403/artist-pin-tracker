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
 * a month counted per-year instead of across years, a country counted once per show, a year with
 * no shows dropped from the axis rather than drawn as the gap it was.
 */
class LibraryStatsTest {

    private val today = LocalDate.of(2026, 10, 25)
    private var seq = 0

    private fun show(
        date: String,
        city: String = "queens",
        cityName: String = "Queens",
        country: String = "United States",
        venue: String = "venue-${seq++}",
        venueName: String = "Knockdown Center",
    ) = EventStatsRow(
        dateEpochDay = LocalDate.parse(date).toEpochDay(),
        cityId = city,
        cityName = cityName,
        country = country,
        venueId = venue,
        venueName = venueName,
    )

    @Test
    fun `an empty library reports nothing rather than zeroes it cannot justify`() {
        val stats = emptyList<EventStatsRow>().toLibraryStats(today)

        assertTrue(stats.isEmpty)
        assertNull(stats.busiestMonth)
        assertNull(stats.firstShow)
        assertEquals(emptyList<Any>(), stats.countries)
        assertEquals(emptyList<Any>(), stats.showsPerYear)
    }

    @Test
    fun `the year axis runs from the first show to this year, including the empty ones`() {
        val stats = listOf(show("2023-05-01"), show("2026-02-11")).toLibraryStats(today)

        // 2024 and 2025 have no shows and are the most interesting columns on the chart.
        assertEquals(listOf(2023, 2024, 2025, 2026), stats.showsPerYear.map { it.year })
        assertEquals(listOf(1, 0, 0, 1), stats.showsPerYear.map { it.shows })
    }

    @Test
    fun `a year ahead of today still extends the axis`() {
        // A show booked for next year must not be cut off the right-hand edge.
        val stats = listOf(show("2026-01-01"), show("2027-06-01")).toLibraryStats(today)

        assertEquals(listOf(2026, 2027), stats.showsPerYear.map { it.year })
    }

    @Test
    fun `every month is present so the chart never has to guess at a gap`() {
        val stats = listOf(show("2024-03-10"), show("2025-03-14"), show("2024-08-01"))
            .toLibraryStats(today)

        assertEquals(12, stats.showsPerMonth.size)
        assertEquals(listOf(Month.JANUARY, Month.FEBRUARY, Month.MARCH), stats.showsPerMonth.take(3).map { it.month })
        // March sums across years; August is a single year; the rest are zero rather than absent.
        assertEquals(2, stats.showsPerMonth.first { it.month == Month.MARCH }.shows)
        assertEquals(1, stats.showsPerMonth.first { it.month == Month.AUGUST }.shows)
        assertEquals(0, stats.showsPerMonth.first { it.month == Month.JANUARY }.shows)
        assertEquals(Month.MARCH, stats.busiestMonth?.month)
    }

    @Test
    fun `venues carry their name and city, counted by shows`() {
        val stats = listOf(
            show("2024-01-01", venue = "v1", venueName = "The Ave Live", cityName = "Philadelphia"),
            show("2024-02-01", venue = "v1", venueName = "The Ave Live", cityName = "Philadelphia"),
            show("2024-03-01", venue = "v2", venueName = "Brooklyn Mirage", cityName = "Queens"),
        ).toLibraryStats(today)

        val top = stats.venues.first()
        assertEquals("The Ave Live", top.name)
        assertEquals("Philadelphia", top.cityName)
        assertEquals(2, top.shows)
        assertEquals(2, stats.venueCount)
        assertEquals(listOf("The Ave Live"), stats.repeatVenues.map { it.name })
        assertEquals(listOf("Brooklyn Mirage"), stats.oneTimeVenues.map { it.name })
    }

    @Test
    fun `the passport counts places once, however many shows happened there`() {
        val stats = listOf(
            show("2024-01-01", city = "queens", cityName = "Queens", venue = "v1"),
            show("2024-02-01", city = "queens", cityName = "Queens", venue = "v1"),
            show("2024-03-01", city = "chicago", cityName = "Chicago", venue = "v2"),
            show("2024-04-01", city = "berlin", cityName = "Berlin", country = "Germany", venue = "v3"),
        ).toLibraryStats(today)

        assertEquals(4, stats.totalShows)
        assertEquals(3, stats.venueCount)
        assertEquals(3, stats.cityCount)
        assertEquals(2, stats.countryCount)

        val us = stats.countries.first { it.country == "United States" }
        assertEquals(3, us.shows)
        assertEquals("Queens twice is still one city", 2, us.cities)
        // Most shows first — the passport reads as a ranking, not as insertion order.
        assertEquals(listOf("United States", "Germany"), stats.countries.map { it.country })
    }

    @Test
    fun `countries with the same number of shows sort alphabetically`() {
        val stats = listOf(
            show("2024-01-01", city = "lisbon", country = "Portugal"),
            show("2024-02-01", city = "berlin", country = "Germany"),
        ).toLibraryStats(today)

        // Otherwise the same library renders in a different order from one reading to the next.
        assertEquals(listOf("Germany", "Portugal"), stats.countries.map { it.country })
    }

    @Test
    fun `a stamp carries the ISO code, not the first two letters of the name`() {
        val stats = listOf(
            show("2024-01-01", country = "United States"),
            show("2024-02-01", country = "Germany"),
            show("2024-03-01", country = "India"),
        ).toLibraryStats(today)

        assertEquals("US", stats.countries.first { it.country == "United States" }.code)
        assertEquals("DE", stats.countries.first { it.country == "Germany" }.code)
        assertEquals("IN", stats.countries.first { it.country == "India" }.code)
    }

    @Test
    fun `a country the JVM cannot place still gets a legible code`() {
        // Hand-typed or non-English names have to render something rather than an empty stamp.
        val stats = listOf(show("2024-01-01", country = "Wakanda")).toLibraryStats(today)

        assertEquals("WA", stats.countries.single().code)
    }

    @Test
    fun `the peak is the highest year and every year that reached it`() {
        val stats = listOf(
            show("2024-01-01"), show("2024-02-01"),
            show("2025-01-01"), show("2025-02-01"),
            show("2026-01-01"),
        ).toLibraryStats(today)

        assertEquals(2, stats.peakCount)
        assertEquals(listOf(2024, 2025), stats.peakYears.map { it.year })
    }

    @Test
    fun `the span is measured to today, rounded to the nearest half year`() {
        // First show 2018-03-03, today 2026-10-25: 103 months, which is 8.58 half-year steps.
        val stats = listOf(show("2018-03-03")).toLibraryStats(today)

        assertEquals(8.5, stats.halfYearsSpanned, 0.001)
    }

    @Test
    fun `a library that started this month does not claim a year`() {
        val stats = listOf(show("2026-10-01")).toLibraryStats(today)

        assertEquals(0.0, stats.halfYearsSpanned, 0.001)
    }
}
