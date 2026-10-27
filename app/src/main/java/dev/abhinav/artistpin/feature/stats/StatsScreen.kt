package dev.abhinav.artistpin.feature.stats

import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.abhinav.artistpin.core.designsystem.Pin
import dev.abhinav.artistpin.core.model.CountryTally
import dev.abhinav.artistpin.core.model.LibraryStats
import dev.abhinav.artistpin.core.model.YearTally
import org.koin.androidx.compose.koinViewModel
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// Monospace carries every label, year, code and small figure in this design; tabular figures keep
// columns of numbers from shifting as they change.
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private const val SidePadding = 18
private const val TileHeight = 8
private const val TileWidth = 14

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
                    style = TextStyle(fontSize = 16.sp),
                )
            }

            else -> StatsList(uiState.stats)
        }
    }
}

@Composable
private fun StatsList(stats: LibraryStats) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(start = SidePadding.dp, end = SidePadding.dp, bottom = 40.dp),
    ) {
        item { HeroCard(stats) }

        item { SectionLabel("Shows per year") }
        item { ShowsPerYearCard(stats) }

        item { SectionLabel("Patterns") }
        item { BusiestMonthCard(stats) }

        if (stats.venues.isNotEmpty()) {
            item { SectionLabel("Venue loyalty") }
            item { VenueLoyaltyCard(stats) }
        }

        item { SectionLabel("Passport") }
        item { PassportCard(stats) }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
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
        Spacer(Modifier.width(10.dp))
        Text(
            "Your stats",
            color = Pin.OnPage,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        color = Pin.OnPageTertiary,
        style = Mono.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 34.dp, bottom = 14.dp),
    )
}

@Composable
private fun StatsCard(
    padding: PaddingValues,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Pin.RadiusStatsCard))
            .background(Pin.Card)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun HeroCard(stats: LibraryStats) {
    Column(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Pin.RadiusHero))
            // 160deg across the whole card. Built from the measured size rather than from fixed
            // offsets: an Offset is in pixels, so a literal one finishes a quarter of the way
            // across a 3x screen and lands somewhere else again on a 2x one.
            .drawWithCache {
                val brush = Brush.linearGradient(
                    colors = Pin.HeroGradient,
                    start = Offset(size.width * 0.18f, 0f),
                    end = Offset(size.width * 0.82f, size.height),
                )
                onDrawBehind { drawRect(brush) }
            }
            // Two concentric rings off the top-right corner, clipped by the card.
            .drawBehind {
                val centre = Offset(size.width + 40.dp.toPx() - 100.dp.toPx(), -40.dp.toPx() + 100.dp.toPx())
                drawCircle(Pin.HeroCircle, radius = 100.dp.toPx(), center = centre, style = Stroke(1.dp.toPx()))
                drawCircle(
                    Pin.HeroCircleFaint,
                    radius = 70.dp.toPx(),
                    center = Offset(size.width + 10.dp.toPx() - 70.dp.toPx(), -10.dp.toPx() + 70.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp),
    ) {
        Text(
            stats.sinceLabel(),
            color = Pin.HeroEyebrow,
            style = Mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp),
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stats.totalShows.toString(),
                color = Pin.OnHero,
                style = Tabular.copy(
                    fontSize = 96.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 79.sp,
                    letterSpacing = (-5).sp,
                ),
            )
            Text(
                "shows\n${stats.spanLabel()}",
                color = Pin.OnHeroSecondary,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        Pin.HeroRule,
                        Offset(0f, 0f),
                        Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(top = 14.dp),
        ) {
            HeroStat(stats.venueCount, "venues", Modifier.weight(1f), divider = false)
            HeroStat(stats.cityCount, "cities", Modifier.weight(1f), divider = true)
            HeroStat(stats.countryCount, "countries", Modifier.weight(1f), divider = true)
        }
    }
}

@Composable
private fun HeroStat(value: Int, label: String, modifier: Modifier, divider: Boolean) {
    Column(
        modifier = modifier
            .then(
                if (!divider) Modifier else Modifier.drawBehind {
                    drawLine(Pin.HeroRule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                },
            )
            .padding(start = if (divider) 14.dp else 0.dp),
    ) {
        Text(
            value.toString(),
            color = Pin.OnHero,
            style = Tabular.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        )
        Text(
            label,
            color = Pin.OnHeroLabel,
            style = TextStyle(fontSize = 12.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ShowsPerYearCard(stats: LibraryStats) {
    StatsCard(PaddingValues(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stats.peakHeadline(),
                    color = Pin.OnPage,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
                )
                Text(
                    stats.peakSubtitle(),
                    color = Pin.OnPageSecondary,
                    style = TextStyle(fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                "PEAK",
                color = Pin.StatsAccentLight,
                style = Mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Pin.StatsChip)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }

        val peak = stats.peakCount
        val scale = yearTileScale(peak)
        val gap = 2.dp
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fixedWidth = yearColumnWidth(
                availableWidth = maxWidth.value,
                years = stats.showsPerYear.size,
                gap = gap.value,
            )?.dp

            if (fixedWidth == null) {
                // Few enough years to share the card, which is the design as drawn.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    stats.showsPerYear.forEach { tally ->
                        YearColumn(
                            tally = tally,
                            isPeak = tally.shows == peak && tally.shows > 0,
                            scale = scale,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                val scroll = rememberScrollState()
                // Opens on the most recent years. The left edge is the year you first went to a
                // show, which is the least interesting end of a chart that grows forever.
                LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                val canScrollBack by remember { derivedStateOf { scroll.value > 0 } }
                val canScrollOn by remember { derivedStateOf { scroll.value < scroll.maxValue } }

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scroll),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        stats.showsPerYear.forEach { tally ->
                            YearColumn(
                                tally = tally,
                                isPeak = tally.shows == peak && tally.shows > 0,
                                scale = scale,
                                modifier = Modifier.width(fixedWidth),
                            )
                        }
                    }
                    // Only on the side that actually has more years, so the chart says where it
                    // continues rather than decorating both edges whatever the position.
                    ScrollCue(Alignment.CenterStart, visible = canScrollBack, Modifier.align(Alignment.CenterStart))
                    ScrollCue(Alignment.CenterEnd, visible = canScrollOn, Modifier.align(Alignment.CenterEnd))
                }
            }
        }

        Row(
            modifier = Modifier.padding(start = 4.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(TileWidth.dp, scale.tileHeight.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Pin.StatsAccent),
            )
            Text(scale.legend, color = Pin.OnPageTertiary, style = TextStyle(fontSize = 11.5.sp))
        }
    }
}

/**
 * The "there are more years this way" cue.
 *
 * A chevron over a short fade of the card's own colour, so the column it covers reads as running
 * under the edge rather than being cut off. Not a button: the chart is scrolled, and a tap target
 * here would sit on top of the tiles it is pointing past.
 */
@Composable
private fun ScrollCue(side: Alignment, visible: Boolean, modifier: Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        val card = Pin.Card
        val toStart = side == Alignment.CenterStart
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(STACK_HEIGHT.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = if (toStart) listOf(card, Color.Transparent) else listOf(Color.Transparent, card),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (toStart) {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = if (toStart) "Earlier years" else "Later years",
                tint = Pin.OnPageSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One year: the count, a stack of one tile per show, and the year.
 *
 * Tiles shrink rather than overflow once a year passes what the 156dp track can hold, so a
 * fifty-show year still reads as a full column instead of spilling over the card.
 */
@Composable
private fun YearColumn(
    tally: YearTally,
    isPeak: Boolean,
    scale: YearTileScale,
    modifier: Modifier,
) {
    val tiles = scale.tilesFor(tally.shows)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            tally.shows.toString(),
            color = when {
                isPeak -> Pin.StatsAccentLight
                tally.shows == 0 -> Pin.OnPageTertiary
                else -> Pin.OnPageSecondary
            },
            style = Mono.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
        Column(
            modifier = Modifier.height(STACK_HEIGHT.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (tally.shows == 0) {
                // A dashed outline rather than nothing: an empty year is a fact about the year,
                // and a blank column would read as a rendering gap.
                Box(
                    Modifier
                        // The same height as a real tile: at 8dp beside scaled-down tiles an
                        // empty year would stand taller than a year with shows in it.
                        .size(TileWidth.dp, scale.tileHeight.dp)
                        .border(1.dp, Pin.OnPageFaint, RoundedCornerShape(2.5.dp)),
                )
            } else {
                repeat(tiles) { index ->
                    val isTop = index == tiles - 1
                    Box(
                        Modifier
                            .padding(top = if (index == 0) 0.dp else scale.gap.dp)
                            .size(TileWidth.dp, scale.tileHeight.dp)
                            .then(
                                if (isPeak && isTop) {
                                    // The design's `box-shadow: 0 0 12px`. A radial gradient
                                    // rather than a flat circle, which reads as a blob at 14dp.
                                    Modifier.drawBehind {
                                        drawCircle(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Pin.StatsAccent.copy(alpha = 0.55f),
                                                    Color.Transparent,
                                                ),
                                                center = Offset(size.width / 2, size.height / 2),
                                                radius = 12.dp.toPx(),
                                            ),
                                            radius = 12.dp.toPx(),
                                            center = Offset(size.width / 2, size.height / 2),
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(if (isPeak) Pin.StatsAccent else Pin.StatsTileIdle),
                    )
                }
            }
        }
        Text(
            "'${(tally.year % 100).toString().padStart(2, '0')}",
            color = if (isPeak) Pin.OnPage else Pin.OnPageTertiary,
            style = Mono.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        )
    }
}

@Composable
private fun BusiestMonthCard(stats: LibraryStats) {
    val busiest = stats.busiestMonth
    val max = stats.showsPerMonth.maxOfOrNull { it.shows } ?: 0
    // Tapping a column answers "how many in June?" in place. Only the peak month's count is
    // written on the card otherwise, and a chart you cannot interrogate invites guessing at it.
    // Opens on the month you are actually in: "how am I doing this October" is the question you
    // have while standing in October, and the peak is one tap away.
    val currentMonth = remember { java.time.LocalDate.now().month }
    var picked by remember { mutableStateOf<java.time.Month?>(currentMonth) }
    val shown = stats.showsPerMonth.firstOrNull { it.month == picked } ?: busiest
    StatsCard(PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (shown?.month) {
                        busiest?.month -> "Busiest month"
                        currentMonth -> "This month"
                        else -> "That month"
                    },
                    color = Pin.OnPageSecondary,
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                )
                Text(
                    shown?.month?.getDisplayName(JavaTextStyle.FULL, Locale.getDefault()).orEmpty(),
                    color = Pin.Kraft,
                    style = TextStyle(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp,
                        lineHeight = 32.sp,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "${shown?.shows ?: 0} ${if (shown?.shows == 1) "show" else "shows"}\nacross all years",
                color = Pin.OnPageSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                textAlign = TextAlign.End,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            stats.showsPerMonth.forEach { tally ->
                val isPeak = shown != null && tally.month == shown.month
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { picked = if (picked == tally.month) null else tally.month },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val fraction = if (max == 0) 0f else tally.shows.toFloat() / max
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((64f * fraction).dp.coerceAtLeast(if (tally.shows > 0) 4.dp else 0.dp))
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(
                                    if (isPeak) {
                                        Pin.Kraft
                                    } else {
                                        // Opacity carries the count, so a quiet month is visible
                                        // without competing with the peak.
                                        Pin.OnPage.copy(alpha = 0.08f + fraction * 0.22f)
                                    },
                                ),
                        )
                    }
                    Text(
                        tally.month.getDisplayName(JavaTextStyle.NARROW, Locale.ENGLISH),
                        color = if (isPeak) Pin.Kraft else Pin.OnPageTertiary,
                        style = Mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun VenueLoyaltyCard(stats: LibraryStats) {
    StatsCard(PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
        Column(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 16.dp)) {
            Text(
                stats.venueHeadline(),
                color = Pin.OnPage,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            )
            Text(
                "Box size = shows at that venue",
                color = Pin.OnPageSecondary,
                style = TextStyle(fontSize = 12.5.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        // The two bands are sized by shows, not by venue count: twenty-three one-night stands are
        // twenty-three shows, and the band has to carry the same weight as the repeat venues do.
        // The shows actually drawn, not every repeat show: the band would otherwise claim height
        // for venues that are not in the chart.
        val repeatShows = stats.repeatVenues.take(TreemapBoxes).sumOf { it.shows }
        val singleShows = stats.oneTimeVenues.size
        Column(
            modifier = Modifier.fillMaxWidth().height(290.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (repeatShows > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(repeatShows.toFloat())
                        .clip(
                            RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (singleShows > 0) 5.dp else 12.dp,
                                bottomEnd = if (singleShows > 0) 5.dp else 12.dp,
                            ),
                        ),
                ) {
                    RepeatVenueTreemap(stats)
                }
            }
            if (singleShows > 0) {
                OneNightStands(
                    count = singleShows,
                    share = stats.oneTimeSharePercent(),
                    // The band's own shape, used for the hatch and the border alike. Clipping it
                    // rounder than the border is drawn shaves the corners off the border.
                    shape = RoundedCornerShape(
                        topStart = if (repeatShows > 0) 5.dp else 12.dp,
                        topEnd = if (repeatShows > 0) 5.dp else 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(singleShows.toFloat()),
                )
            }
        }
    }
}

/**
 * Boxes in the top band, including the Others box.
 *
 * The cap is on what gets drawn, not on what gets named: past this, the remaining repeat venues
 * are summed into the last box rather than dropped, so the band still accounts for every show.
 */
/**
 * Venues drawn in the top band.
 *
 * A hard cap rather than a cap plus a summary box: five named venues say more than four and an
 * "Others" that cannot be opened, named or reasoned about. Everything past the fifth is simply
 * not in this chart — the shows are still counted in the hero and the passport.
 */
private const val TreemapBoxes = 5

@Composable
private fun RepeatVenueTreemap(stats: LibraryStats) {
    // Already ordered by shows, then by name, so a tie is broken alphabetically rather than by
    // whichever row the database happened to return first.
    val drawn = stats.repeatVenues.take(TreemapBoxes)

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 3.dp
        val rects = squarify(
            weights = drawn.map { it.shows.toFloat() },
            width = constraints.maxWidth.toFloat(),
            height = constraints.maxHeight.toFloat(),
        )
        val density = androidx.compose.ui.platform.LocalDensity.current

        rects.forEachIndexed { index, rect ->
            val venue = drawn[index]
            with(density) {
                Box(
                    modifier = Modifier
                        .padding(start = rect.x.toDp(), top = rect.y.toDp())
                        .size(
                            width = (rect.width - gap.toPx()).coerceAtLeast(0f).toDp(),
                            height = (rect.height - gap.toPx()).coerceAtLeast(0f).toDp(),
                        )
                        .clip(RoundedCornerShape(5.dp))
                        .background(Pin.TreemapShades[index.coerceAtMost(Pin.TreemapShades.lastIndex)]),
                ) {
                    TreemapLabel(
                        name = venue.name,
                        shows = venue.shows,
                        share = stats.venueSharePercent(venue.shows),
                        city = venue.cityName,
                        boxWidth = rect.width.toDp(),
                        boxHeight = rect.height.toDp(),
                    )
                }
            }
        }
    }
}

/**
 * A box's name and count.
 *
 * The name always shows — a numbered box nobody can identify is not worth drawing. What changes
 * with the box is the arrangement: a short box puts the name and the count on one line, because
 * stacking them there clips one of the two.
 */
@Composable
private fun TreemapLabel(
    name: String,
    shows: Int,
    share: Int,
    city: String?,
    boxWidth: androidx.compose.ui.unit.Dp,
    boxHeight: androidx.compose.ui.unit.Dp,
) {
    // Thresholds in dp, sized against a 360dp-wide phone: at that width the biggest box is about
    // 120dp across, so a 150dp bar for "large" would never once be met on a real screen.
    val large = boxWidth >= 110.dp && boxHeight >= 100.dp
    val short = boxHeight < 56.dp
    val mid = !large && !short && boxWidth >= 60.dp

    val padding = when {
        large -> 12.dp
        short -> 8.dp
        else -> 10.dp
    }
    val nameSize = when {
        large -> 14.sp
        mid -> 12.5.sp
        else -> 11.5.sp
    }
    val countSize = when {
        large -> 34.sp
        mid -> 22.sp
        short -> 16.sp
        else -> 18.sp
    }

    if (short) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                name,
                color = Pin.OnTreemap,
                style = TextStyle(fontSize = nameSize, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                shows.toString(),
                color = Pin.OnTreemap,
                style = Tabular.copy(
                    fontSize = countSize,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                ),
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            name,
            color = Pin.OnTreemap,
            style = TextStyle(
                fontSize = nameSize,
                fontWeight = if (large) FontWeight.Bold else FontWeight.SemiBold,
                lineHeight = nameSize * 1.2f,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Weighted, so a two-line venue name cannot push the count out of the box. Without
            // this the Column hands the name whatever it asks for and the number below is the
            // part that gets clipped — the one thing the box exists to show.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (large) {
            Column {
                Text(
                    shows.toString(),
                    color = Pin.OnTreemap,
                    style = Tabular.copy(
                        fontSize = countSize,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.5).sp,
                        lineHeight = countSize,
                    ),
                )
                Text(
                    listOfNotNull("$share%", city?.uppercase(Locale.getDefault())).joinToString(" · "),
                    color = Pin.OnTreemapSecondary,
                    style = Mono.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    shows.toString(),
                    color = Pin.OnTreemap,
                    style = Tabular.copy(
                        fontSize = countSize,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp,
                        lineHeight = countSize,
                    ),
                )
                if (mid) {
                    Text(
                        "$share%",
                        color = Pin.OnTreemapSecondary,
                        style = Mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The venues visited exactly once, hatched like a ticket stub's edge.
 *
 * Counted as venues, not shows — "23 one-night stands" is a fact about places, and each of them
 * contributed exactly one show, which is what makes the two numbers the same.
 */
@Composable
private fun OneNightStands(
    count: Int,
    share: Int,
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    modifier: Modifier,
) {
    val kraft = Pin.Kraft
    Row(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                // repeating-linear-gradient(135deg, kraft@10% 0 6px, kraft@3% 6px 12px)
                val step = 12.dp.toPx()
                val stroke = 6.dp.toPx()
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = kraft.copy(alpha = 0.10f),
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = stroke,
                    )
                    x += step
                }
            }
            .border(1.dp, kraft.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$count one-night stand${if (count == 1) "" else "s"}",
                color = kraft,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                "Venues you've been to exactly once",
                color = kraft.copy(alpha = 0.7f),
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                count.toString(),
                color = kraft,
                style = Tabular.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
            )
            Text(
                "$share%",
                color = kraft.copy(alpha = 0.7f),
                style = Mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PassportCard(stats: LibraryStats) {
    StatsCard(PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp)) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            stats.countries.forEachIndexed { index, country ->
                Stamp(
                    country = country,
                    isTop = index == 0,
                    // Alternating tilt, so a row of stamps looks pressed by hand rather than laid out.
                    rotation = if (index % 2 == 0) -2f else 3f,
                    modifier = Modifier
                        .then(
                            // A stamp alone on the last row would otherwise stretch the full
                            // width of the card and stop looking like a stamp. Held to roughly
                            // the width it would have had with a neighbour.
                            if (index == stats.countries.lastIndex && stats.countries.size % 2 == 1) {
                                Modifier.fillMaxWidth(0.55f)
                            } else {
                                Modifier.weight(if (index == 0) 1.35f else 1f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun Stamp(
    country: CountryTally,
    isTop: Boolean,
    rotation: Float,
    modifier: Modifier,
) {
    val kraft = Pin.Kraft
    Column(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .border(2.dp, if (isTop) kraft else kraft.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .background(kraft.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            // The inner dashed rule is the part that makes it read as a stamp rather than a chip.
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = kraft.copy(alpha = if (isTop) 0.5f else 0.35f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        ),
                    ),
                )
            }
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 13.dp),
    ) {
        Text(
            "ADMITTED \u00B7 ${country.code}",
            color = kraft,
            style = Mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
        )
        Text(
            country.country,
            color = kraft,
            style = TextStyle(
                fontSize = if (isTop) 22.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                lineHeight = if (isTop) 24.sp else 21.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp),
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                country.shows.toString(),
                color = kraft,
                style = Mono.copy(
                    fontSize = if (isTop) 26.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                ),
            )
            Text(
                "${if (country.shows == 1) "SHOW" else "SHOWS"}\n" +
                    "${country.cities} ${if (country.cities == 1) "CITY" else "CITIES"}",
                color = kraft.copy(alpha = 0.7f),
                style = Mono.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    lineHeight = 14.sp,
                ),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
