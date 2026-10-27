package dev.abhinav.artistpin.feature.stats

import dev.abhinav.artistpin.core.model.LibraryStats
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val SinceFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** `SINCE 3 MAR 2018`, the eyebrow over the hero number. */
fun LibraryStats.sinceLabel(): String =
    firstShow?.let { "SINCE ${it.format(SinceFormat).uppercase(Locale.getDefault())}" }.orEmpty()

/**
 * `in 8½ years`, or `in 6 months` while that would still read as zero.
 *
 * The half symbol is the point of the design's phrasing, so a whole number never renders as "8.0".
 */
fun LibraryStats.spanLabel(): String {
    val halves = halfYearsSpanned
    if (halves < 1.0) return "in under a year"
    val whole = halves.toInt()
    val hasHalf = halves % 1.0 != 0.0
    val number = if (hasHalf) "$whole½" else "$whole"
    return if (whole == 1 && !hasHalf) "in 1 year" else "in $number years"
}

/**
 * The shows-per-year headline: how high the peak is and how many years reached it.
 *
 * "Three years at 14" says more than "Best year: 2024" when a plateau is the shape of the data,
 * which is the whole reason the chart stacks one tile per show.
 */
fun LibraryStats.peakHeadline(): String {
    val peaks = peakYears
    return when {
        peaks.isEmpty() -> "No shows yet"
        peaks.size == 1 -> "Best year: ${peaks.single().year}"
        else -> "${countWord(peaks.size)} years at $peakCount"
    }
}

/** `2024–26 is 75% of everything`. */
fun LibraryStats.peakSubtitle(): String {
    val peaks = peakYears
    if (peaks.isEmpty() || totalShows == 0) return ""
    val share = (peaks.sumOf { it.shows } * 100.0 / totalShows).roundToInt()
    return "${peakYearsLabel()} is $share% of everything"
}

/** A contiguous run reads as a range; anything else is listed, because a range would lie. */
private fun LibraryStats.peakYearsLabel(): String {
    val years = peakYears.map { it.year }.sorted()
    if (years.isEmpty()) return ""
    if (years.size == 1) return years.single().toString()
    val contiguous = years.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (contiguous) {
        "${years.first()}–${(years.last() % 100).toString().padStart(2, '0')}"
    } else {
        years.joinToString(", ")
    }
}

/**
 * `1 in 4 shows at The Ave Live`, the venue-loyalty headline.
 *
 * Rounded to a fraction people say out loud rather than a percentage: "1 in 4" is the claim the
 * treemap is making, and 23.2% is not. The rounding has a ceiling, though — everything from two
 * thirds upwards rounds to "1 in 1", and telling someone that half their shows were at the only
 * venue they have ever been to is a lie the chart underneath immediately contradicts.
 */
fun LibraryStats.venueHeadline(): String {
    val top = venues.firstOrNull() ?: return "No venues yet"
    if (totalShows == 0) return "No venues yet"
    val ratio = top.shows.toDouble() / totalShows
    return when {
        ratio >= 1.0 -> "Every show at ${top.name}"
        ratio >= 2.0 / 3.0 -> "Most of your shows at ${top.name}"
        ratio >= 0.4 -> "Half your shows at ${top.name}"
        else -> "1 in ${(1 / ratio).roundToInt()} shows at ${top.name}"
    }
}

/** The share of all shows that happened at a venue visited exactly once. */
fun LibraryStats.oneTimeSharePercent(): Int =
    if (totalShows == 0) 0 else (oneTimeVenues.size * 100.0 / totalShows).roundToInt()

fun LibraryStats.venueSharePercent(shows: Int): Int =
    if (totalShows == 0) 0 else (shows * 100.0 / totalShows).roundToInt()

/** Small numbers read better as words in a headline; past that, digits. */
private fun countWord(n: Int): String = when (n) {
    2 -> "Two"
    3 -> "Three"
    4 -> "Four"
    5 -> "Five"
    6 -> "Six"
    7 -> "Seven"
    8 -> "Eight"
    9 -> "Nine"
    else -> n.toString()
}
