package dev.abhinav.artistpin.core.model

import java.time.LocalDate
import java.time.Month

/** A year on the shows-per-year chart. Years nobody went to a show are present, with zero. */
data class YearTally(val year: Int, val shows: Int)

/** One calendar month, summed across every year. */
data class MonthTally(val month: Month, val shows: Int)

/** One venue in the loyalty treemap. */
data class VenueTally(
    val venueId: String,
    val name: String,
    val cityName: String,
    val shows: Int,
)

/** One country in the passport. [code] is the ISO alpha-2, falling back to the name's first two letters. */
data class CountryTally(
    val country: String,
    val code: String,
    val shows: Int,
    val cities: Int,
)

/**
 * Everything the stats screen shows, derived rather than stored.
 *
 * Recomputed from the library on every change instead of being maintained alongside it: at this
 * size the arithmetic is free, and a counter kept in a column is a counter that can disagree with
 * the rows it counts.
 */
data class LibraryStats(
    val totalShows: Int = 0,
    val firstShow: LocalDate? = null,
    val lastShow: LocalDate? = null,
    /** Every year from the first show to this one, oldest first, zeros included. */
    val showsPerYear: List<YearTally> = emptyList(),
    /** All twelve, in calendar order, so the month chart never has to guess at a gap. */
    val showsPerMonth: List<MonthTally> = emptyList(),
    /** Most shows first. */
    val venues: List<VenueTally> = emptyList(),
    val cityCount: Int = 0,
    val countryCount: Int = 0,
    val venueCount: Int = 0,
    /** The passport, most-visited country first. */
    val countries: List<CountryTally> = emptyList(),
    /** Half-year steps from the first show to today, for "in 8½ years". */
    val halfYearsSpanned: Double = 0.0,
) {
    val isEmpty: Boolean get() = totalShows == 0

    /** The highest single-year count, and every year that reached it. */
    val peakYears: List<YearTally> get() = showsPerYear.filter { it.shows == peakCount && it.shows > 0 }

    val peakCount: Int get() = showsPerYear.maxOfOrNull { it.shows } ?: 0

    val busiestMonth: MonthTally? get() = showsPerMonth.maxByOrNull { it.shows }?.takeIf { it.shows > 0 }

    /** Venues seen more than once, which is what the treemap's top band is made of. */
    val repeatVenues: List<VenueTally> get() = venues.filter { it.shows >= 2 }

    val oneTimeVenues: List<VenueTally> get() = venues.filter { it.shows == 1 }
}
