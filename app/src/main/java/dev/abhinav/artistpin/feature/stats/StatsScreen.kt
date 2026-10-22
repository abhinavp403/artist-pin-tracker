package dev.abhinav.artistpin.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.model.CountryTally
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.core.model.YearTally
import org.koin.androidx.compose.koinViewModel
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pin.Page),
    ) {
        Header(onBack = onBack)

        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            uiState.stats.isEmpty -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Add a show and this fills up.",
                    color = Pin.OnPageSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> StatsList(uiState.stats)
        }
    }
}

@Composable
private fun StatsList(stats: LibraryStats) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Totals(stats) }

        item { SectionTitle("Shows per year") }
        items(stats.showsPerYear, key = { it.year }) { tally ->
            YearRow(tally = tally, busiest = stats.showsPerYear.maxOf { it.shows })
        }

        item { SectionTitle("Patterns") }
        item {
            val month = stats.busiestMonth
            val gap = stats.longestGap
            Card {
                if (month != null) {
                    Fact(
                        label = "Busiest month",
                        value = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        detail = "${month.shows} ${showWord(month.shows)}, counting every year together",
                    )
                }
                if (gap != null) {
                    Spacer(Modifier.height(14.dp))
                    Fact(
                        label = "Longest gap between shows",
                        value = gapLabel(gap.days),
                        detail = "${gap.from.format(DayMonthYear)} to ${gap.to.format(DayMonthYear)}",
                    )
                }
                // One show can't have a gap after it, and the screen shouldn't pretend otherwise.
                if (gap == null) {
                    Spacer(Modifier.height(14.dp))
                    Fact(
                        label = "Longest gap between shows",
                        value = "Not yet",
                        detail = "Takes two shows to have a gap between them",
                    )
                }
            }
        }

        item { SectionTitle("Passport") }
        items(stats.countries, key = { it.country }) { CountryRow(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Totals(stats: LibraryStats) {
    Card {
        Row(Modifier.fillMaxWidth()) {
            BigNumber(stats.totalShows, showWord(stats.totalShows), Modifier.weight(1f))
            BigNumber(stats.venueCount, if (stats.venueCount == 1) "venue" else "venues", Modifier.weight(1f))
            BigNumber(stats.cityCount, if (stats.cityCount == 1) "city" else "cities", Modifier.weight(1f))
            BigNumber(
                stats.countryCount,
                if (stats.countryCount == 1) "country" else "countries",
                Modifier.weight(1f),
            )
        }
        val first = stats.firstShow
        val last = stats.lastShow
        if (first != null && last != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (first == last) "On ${first.format(DayMonthYear)}"
                else "${first.format(DayMonthYear)} — ${last.format(DayMonthYear)}",
                color = Pin.OnPageTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BigNumber(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value.toString(),
            color = Pin.OnPage,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Text(label, color = Pin.OnPageSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/** A year's bar, drawn in proportion to the busiest year rather than to a fixed scale. */
@Composable
private fun YearRow(tally: YearTally, busiest: Int) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tally.year.toString(),
                color = Pin.OnPageStrong,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(52.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Pin.Fill),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = tally.shows.toFloat() / busiest.coerceAtLeast(1))
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Pin.Accent),
                )
            }
            Text(
                tally.shows.toString(),
                color = Pin.OnPageSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(32.dp).padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun CountryRow(tally: CountryTally) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tally.country,
                color = Pin.OnPageStrong,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${tally.shows} ${showWord(tally.shows)} · ${tally.cities} " +
                    if (tally.cities == 1) "city" else "cities",
                color = Pin.OnPageSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, detail: String) {
    Column {
        Text(label, color = Pin.OnPageSecondary, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            color = Pin.OnPage,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(detail, color = Pin.OnPageTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        color = Pin.OnPageTertiary,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pin.Card)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Pin.Chrome)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Pin.OnChrome,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Your shows, counted",
            color = Pin.OnPage,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
    }
}

private fun showWord(count: Int) = if (count == 1) "show" else "shows"

private fun gapLabel(days: Long): String = when {
    days >= 365 -> {
        val years = days / 365
        val months = (days % 365) / 30
        if (months == 0L) "$years ${if (years == 1L) "year" else "years"}"
        else "$years ${if (years == 1L) "yr" else "yrs"} $months mo"
    }

    days >= 60 -> "${days / 30} months"
    else -> "$days ${if (days == 1L) "day" else "days"}"
}
