package dev.abhinav.artistpin.feature.stats

import dev.abhinav.artistpin.core.model.CountryTally
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.core.model.VenueTally
import dev.abhinav.artistpin.core.model.YearTally
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The headlines are generated from the data, so they are as capable of lying as a chart is. */
class StatsCopyTest {

    private fun stats(
        total: Int = 56,
        years: List<Pair<Int, Int>> = emptyList(),
        venues: List<VenueTally> = emptyList(),
        first: LocalDate? = LocalDate.of(2018, 3, 3),
        halves: Double = 8.5,
    ) = LibraryStats(
        totalShows = total,
        firstShow = first,
        showsPerYear = years.map { YearTally(it.first, it.second) },
        venues = venues,
        halfYearsSpanned = halves,
        countries = listOf(CountryTally("United States", "US", total, 9)),
    )

    private fun venue(name: String, shows: Int) = VenueTally("id-$name", name, "Philadelphia", shows)

    @Test
    fun `a plateau is described as a plateau`() {
        val s = stats(years = listOf(2023 to 7, 2024 to 14, 2025 to 14, 2026 to 14))

        assertEquals("Three years at 14", s.peakHeadline())
    }

    @Test
    fun `a single peak names the year instead`() {
        val s = stats(years = listOf(2024 to 9, 2025 to 14, 2026 to 3))

        assertEquals("Best year: 2025", s.peakHeadline())
    }

    @Test
    fun `the subtitle gives the run and its share`() {
        val s = stats(total = 56, years = listOf(2023 to 7, 2024 to 14, 2025 to 14, 2026 to 14))

        assertEquals("2024–26 is 75% of everything", s.peakSubtitle())
    }

    @Test
    fun `peak years that are not consecutive are listed rather than ranged`() {
        // "2019–24" would claim a six-year run that never happened.
        val s = stats(total = 10, years = listOf(2019 to 4, 2020 to 1, 2024 to 4))

        assertEquals("2019, 2024 is 80% of everything", s.peakSubtitle())
    }

    @Test
    fun `venue loyalty rounds to a fraction people say out loud`() {
        assertEquals(
            "1 in 4 shows at The Ave Live",
            stats(total = 56, venues = listOf(venue("The Ave Live", 13))).venueHeadline(),
        )
        assertEquals(
            "Half your shows at The Fillmore",
            stats(total = 10, venues = listOf(venue("The Fillmore", 5))).venueHeadline(),
        )
    }

    @Test
    fun `a share the rounding cannot express is not called half`() {
        // Everything from two thirds up rounds to "1 in 1", and "half your shows at the only
        // venue you have ever been to" is contradicted by the treemap directly underneath it.
        assertEquals(
            "Every show at The Ave Live",
            stats(total = 3, venues = listOf(venue("The Ave Live", 3))).venueHeadline(),
        )
        assertEquals(
            "Most of your shows at The Ave Live",
            stats(total = 4, venues = listOf(venue("The Ave Live", 3))).venueHeadline(),
        )
    }

    @Test
    fun `the span reads as the design wrote it`() {
        assertEquals("in 8½ years", stats(halves = 8.5).spanLabel())
        assertEquals("in 8 years", stats(halves = 8.0).spanLabel())
        assertEquals("in 1 year", stats(halves = 1.0).spanLabel())
        assertEquals("in under a year", stats(halves = 0.5).spanLabel())
    }

    @Test
    fun `the eyebrow uses the first show's date`() {
        assertEquals("SINCE 3 MAR 2018", stats().sinceLabel())
        assertEquals("", stats(first = null).sinceLabel())
    }

    @Test
    fun `an empty library says so rather than dividing by zero`() {
        val empty = LibraryStats()

        assertEquals("No shows yet", empty.peakHeadline())
        assertEquals("", empty.peakSubtitle())
        assertEquals("No venues yet", empty.venueHeadline())
        assertEquals(0, empty.oneTimeSharePercent())
    }
}
