package dev.abhinav.artistpin.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The year chart has to survive the library getting older every January.
 *
 * Equal-width columns are the design, but they are also a slow leak: each new year makes every
 * column narrower, and past a dozen or so the year labels collide and then the tiles clip. These
 * fix the point where the chart stops shrinking and starts scrolling.
 */
class YearChartSizingTest {

    // The card's inner width on a 360dp phone: 18dp screen padding and 14dp card padding a side.
    private val available = 296f
    private val gap = 2f

    @Test
    fun `eight years or fewer share the card`() {
        (1..VISIBLE_YEARS).forEach { years ->
            assertNull("$years years should fill the width", yearColumnWidth(available, years, gap))
        }
    }

    @Test
    fun `the ninth year switches to a fixed width`() {
        val width = yearColumnWidth(available, years = 9, gap = gap)

        // Eight columns and seven gaps fill the card exactly, so the ninth is the one you scroll to.
        assertEquals(((296f - 7 * 2f) / 8), width!!, 0.01f)
    }

    @Test
    fun `the width does not shrink as more years arrive`() {
        val atNine = yearColumnWidth(available, years = 9, gap = gap)
        val atThirty = yearColumnWidth(available, years = 30, gap = gap)

        // The whole point: year thirty is as readable as year nine, further along the scroll.
        assertEquals(atNine!!, atThirty!!, 0.01f)
    }

    @Test
    fun `a column stays wide enough for its year label`() {
        val width = yearColumnWidth(available, years = 40, gap = gap)!!

        // "'18" at 11sp monospace needs roughly 20dp; the tiles need 14dp.
        assertTrue("column of ${width}dp is too narrow to label", width >= 22f)
    }

    @Test
    fun `degenerate input falls back to filling the width`() {
        assertNull(yearColumnWidth(availableWidth = 0f, years = 20, gap = gap))
        assertNull(yearColumnWidth(available, years = 20, gap = gap, visible = 0))
    }

    // ---- Tile scale -----------------------------------------------------------------------

    private fun stackHeight(scale: YearTileScale, shows: Int): Float {
        val tiles = scale.tilesFor(shows)
        return tiles * scale.tileHeight + (tiles - 1) * scale.gap
    }

    @Test
    fun `a year that fits draws one tile per show at full size`() {
        val scale = yearTileScale(peakShows = 14)

        assertEquals(1, scale.showsPerTile)
        assertEquals(8f, scale.tileHeight, 0.01f)
        assertEquals("= 1 show", scale.legend)
        assertEquals(14, scale.tilesFor(14))
    }

    @Test
    fun `a taller year keeps one tile per show by shrinking the tile`() {
        val scale = yearTileScale(peakShows = 22)

        assertEquals("still show-by-show", 1, scale.showsPerTile)
        assertTrue("tile shrank below legibility: ${scale.tileHeight}", scale.tileHeight >= 4f)
        assertTrue(stackHeight(scale, 22) <= STACK_HEIGHT + 0.01f)
    }

    @Test
    fun `a hundred shows scales the tile instead of overflowing the track`() {
        val scale = yearTileScale(peakShows = 100)

        // The old behaviour pinned tiles at 2dp and drew a 497dp column inside a 156dp track,
        // straight through the count above it and out of the card.
        assertTrue("one tile must stand for several shows", scale.showsPerTile > 1)
        assertTrue(stackHeight(scale, 100) <= STACK_HEIGHT + 0.01f)
        assertTrue("tile is a line, not a tile", scale.tileHeight >= 4f)
        assertEquals("= ${scale.showsPerTile} shows", scale.legend)
    }

    @Test
    fun `no year overflows the track at any size`() {
        listOf(1, 13, 14, 15, 20, 31, 32, 50, 100, 365, 1000).forEach { peak ->
            val scale = yearTileScale(peak)
            assertTrue(
                "peak $peak overflows: ${stackHeight(scale, peak)}dp",
                stackHeight(scale, peak) <= STACK_HEIGHT + 0.01f,
            )
            assertTrue("peak $peak draws an illegible tile", scale.tileHeight >= 4f)
        }
    }

    @Test
    fun `a quiet year still gets a tile under a scaled legend`() {
        val scale = yearTileScale(peakShows = 100)

        // Rounded up, so one show never renders as an empty column next to the dashed zero.
        assertEquals(1, scale.tilesFor(1))
        assertEquals(0, scale.tilesFor(0))
    }

    @Test
    fun `an empty library does not divide by zero`() {
        val scale = yearTileScale(peakShows = 0)

        assertEquals(1, scale.showsPerTile)
        assertEquals(8f, scale.tileHeight, 0.01f)
    }
}
