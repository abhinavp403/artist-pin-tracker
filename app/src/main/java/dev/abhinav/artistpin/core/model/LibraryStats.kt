package dev.abhinav.artistpin.core.model

import java.time.LocalDate
import java.time.Month

/** A year on the shows-per-year list. */
data class YearTally(val year: Int, val shows: Int)

/** The month of the calendar that has drawn you out most often, summed across every year. */
data class MonthTally(val month: Month, val shows: Int)

/** The longest stretch between two consecutive shows, and the two shows that bound it. */
data class ShowGap(val days: Long, val from: LocalDate, val to: LocalDate)

/** One country in the passport, with the cities visited inside it. */
data class CountryTally(val country: String, val shows: Int, val cities: Int)

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
    /** Most recent year first. */
    val showsPerYear: List<YearTally> = emptyList(),
    /** Null until there is anything to count; ties resolve to the earlier month. */
    val busiestMonth: MonthTally? = null,
    /** Null until there are two shows to have a gap between. */
    val longestGap: ShowGap? = null,
    val cityCount: Int = 0,
    val countryCount: Int = 0,
    val venueCount: Int = 0,
    /** The passport, most-visited country first. */
    val countries: List<CountryTally> = emptyList(),
) {
    val isEmpty: Boolean get() = totalShows == 0
}
