package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.database.EventStatsRow
import dev.abhinav.artistpin.core.model.CountryTally
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.core.model.MonthTally
import dev.abhinav.artistpin.core.model.VenueTally
import dev.abhinav.artistpin.core.model.YearTally
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns the library into the numbers the stats screen reads.
 *
 * A plain function over plain rows rather than a pile of aggregate SQL: the counting is trivial at
 * this size, and keeping it out of the query means every case below can be tested without standing
 * up a database.
 *
 * [today] is a parameter because two of these answers depend on it — the year axis runs to the
 * current year, and the hero reads "in 8½ years" — and a statistic that quietly consults the
 * system clock is a statistic that cannot be tested.
 */
fun List<EventStatsRow>.toLibraryStats(today: LocalDate = LocalDate.now()): LibraryStats {
    if (isEmpty()) return LibraryStats()

    val dates = map { LocalDate.ofEpochDay(it.dateEpochDay) }.sorted()
    val first = dates.first()
    val last = dates.last()

    // Every year end to end, not just the ones with shows: a gap year is the most interesting
    // column on the chart, and leaving it out would close the gap it exists to show.
    val countsByYear = dates.groupingBy { it.year }.eachCount()
    val perYear = (first.year..maxOf(last.year, today.year))
        .map { year -> YearTally(year, countsByYear[year] ?: 0) }

    val countsByMonth = dates.groupingBy { it.month }.eachCount()
    val perMonth = Month.entries.map { MonthTally(it, countsByMonth[it] ?: 0) }

    val venues = groupBy { it.venueId }
        .map { (venueId, rows) ->
            VenueTally(
                venueId = venueId,
                name = rows.first().venueName,
                cityName = rows.first().cityName,
                shows = rows.size,
            )
        }
        .sortedWith(compareByDescending<VenueTally> { it.shows }.thenBy { it.name })

    val countries = groupBy { it.country }
        .map { (country, rows) ->
            CountryTally(
                country = country,
                code = country.isoAlpha2(),
                shows = rows.size,
                cities = rows.distinctBy { it.cityId }.size,
            )
        }
        // Most shows first, then alphabetically so two equal countries don't swap places between
        // readings of the same library.
        .sortedWith(compareByDescending<CountryTally> { it.shows }.thenBy { it.country })

    return LibraryStats(
        totalShows = size,
        firstShow = first,
        lastShow = last,
        showsPerYear = perYear,
        showsPerMonth = perMonth,
        venues = venues,
        cityCount = distinctBy { it.cityId }.size,
        countryCount = countries.size,
        venueCount = venues.size,
        countries = countries,
        halfYearsSpanned = halfYearsBetween(first, today),
    )
}

/** Rounded to the nearest half, which is all "8½ years" claims to be. */
private fun halfYearsBetween(from: LocalDate, to: LocalDate): Double {
    if (!to.isAfter(from)) return 0.0
    val months = ChronoUnit.MONTHS.between(from, to)
    return Math.round(months / 6.0) / 2.0
}

/**
 * Every country the JVM knows, by its English name, built once.
 *
 * The map is constant, and the lookup it replaces was a scan of ~250 regions — each one building
 * a Locale and formatting a display name — run per country per recomputation. Stats recompute on
 * every change to the library, so that was a few thousand throwaway Locales each time a show was
 * saved.
 */
private val codesByCountryName: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy(
        keySelector = { code -> Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT) },
        valueTransform = { it },
    )
}

/**
 * The country name as the passport stamp wants it: two letters.
 *
 * Read from the JVM's own country list rather than a table shipped here, because the names in the
 * database came from the Places SDK in English and that is exactly what Locale knows. A name it
 * cannot place — anything hand-typed or non-English — falls back to its first two letters, which
 * is wrong but legible, and never blank.
 */
internal fun String.isoAlpha2(): String =
    codesByCountryName[lowercase(Locale.ROOT)] ?: take(2).uppercase(Locale.ROOT)
