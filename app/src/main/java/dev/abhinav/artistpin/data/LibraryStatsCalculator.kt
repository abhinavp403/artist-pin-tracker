package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.database.EventStatsRow
import dev.abhinav.artistpin.core.model.CountryTally
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.core.model.MonthTally
import dev.abhinav.artistpin.core.model.ShowGap
import dev.abhinav.artistpin.core.model.YearTally
import java.time.LocalDate

/**
 * Turns the library into the numbers the stats screen reads.
 *
 * A plain function over plain rows rather than a pile of aggregate SQL: the counting is trivial at
 * this size, and "longest gap between consecutive shows" is a window function in SQLite against
 * four lines of Kotlin here. Keeping it out of the query also means every case below can be tested
 * without standing up a database.
 */
fun List<EventStatsRow>.toLibraryStats(): LibraryStats {
    if (isEmpty()) return LibraryStats()

    val dates = map { LocalDate.ofEpochDay(it.dateEpochDay) }.sorted()

    val perYear = dates.groupingBy { it.year }.eachCount()
        .map { (year, shows) -> YearTally(year, shows) }
        .sortedByDescending { it.year }

    // maxByOrNull keeps the first maximum it meets, so an iteration in calendar order makes a tie
    // resolve to the earlier month rather than to whichever one the grouping happened to yield.
    val busiest = dates.groupingBy { it.month }.eachCount()
        .entries.sortedBy { it.key.value }
        .maxByOrNull { it.value }
        ?.let { MonthTally(it.key, it.value) }

    // Gaps are between *consecutive* shows, which is why the dates are sorted first. Two shows on
    // one night produce a zero-day gap, which is correct and simply never wins.
    val longestGap = dates.zipWithNext()
        .maxByOrNull { (from, to) -> to.toEpochDay() - from.toEpochDay() }
        ?.let { (from, to) -> ShowGap(to.toEpochDay() - from.toEpochDay(), from, to) }

    val countries = groupBy { it.country }
        .map { (country, rows) ->
            CountryTally(
                country = country,
                shows = rows.size,
                cities = rows.distinctBy { it.cityId }.size,
            )
        }
        // Most shows first, then alphabetically so two equal countries don't swap places between
        // readings of the same library.
        .sortedWith(compareByDescending<CountryTally> { it.shows }.thenBy { it.country })

    return LibraryStats(
        totalShows = size,
        firstShow = dates.first(),
        lastShow = dates.last(),
        showsPerYear = perYear,
        busiestMonth = busiest,
        longestGap = longestGap,
        cityCount = distinctBy { it.cityId }.size,
        countryCount = countries.size,
        venueCount = distinctBy { it.venueId }.size,
        countries = countries,
    )
}
