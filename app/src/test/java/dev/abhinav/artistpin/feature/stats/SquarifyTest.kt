package dev.abhinav.artistpin.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A treemap that is merely pretty is wrong: the whole claim the chart makes is that box area is
 * proportional to shows. These check the claim rather than the appearance.
 */
class SquarifyTest {

    private val width = 300f
    private val height = 250f

    private fun areaOf(r: TreemapRect) = r.width * r.height

    @Test
    fun `area is proportional to weight`() {
        val weights = listOf(13f, 6f, 5f, 4f, 3f, 2f)

        val rects = squarify(weights, width, height)

        val total = weights.sum()
        val canvas = width * height
        weights.forEachIndexed { i, w ->
            val expected = w / total * canvas
            assertEquals("box $i", expected, areaOf(rects[i]), canvas * 0.001f)
        }
    }

    @Test
    fun `the boxes fill the canvas and do not overlap`() {
        val rects = squarify(listOf(13f, 6f, 5f, 4f, 3f, 2f), width, height)

        assertEquals(width * height, rects.sumOf { areaOf(it).toDouble() }.toFloat(), 1f)
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlapX = max(0f, min(a.x + a.width, b.x + b.width) - max(a.x, b.x))
                val overlapY = max(0f, min(a.y + a.height, b.y + b.height) - max(a.y, b.y))
                assertEquals("box $i overlaps box $j", 0f, overlapX * overlapY, 0.01f)
            }
        }
    }

    @Test
    fun `every box stays inside the canvas`() {
        val rects = squarify(listOf(20f, 1f, 1f, 1f), width, height)

        rects.forEach {
            assertTrue(it.x >= -0.01f && it.y >= -0.01f)
            assertTrue(it.x + it.width <= width + 0.01f)
            assertTrue(it.y + it.height <= height + 0.01f)
        }
    }

    @Test
    fun `boxes are squarish rather than slivers`() {
        // The point of squarifying: a venue name needs a box with two usable dimensions. A naive
        // slice-and-dice layout would hand the smallest venue a 4px-wide column here.
        val rects = squarify(listOf(13f, 6f, 5f, 4f, 3f, 2f), width, height)

        rects.forEach {
            val ratio = max(it.width, it.height) / min(it.width, it.height)
            assertTrue("aspect ratio $ratio is a sliver", ratio < 4f)
        }
    }

    @Test
    fun `order is preserved so callers can zip their own items back`() {
        val rects = squarify(listOf(10f, 1f), width, height)

        assertTrue("the first weight must get the bigger box", areaOf(rects[0]) > areaOf(rects[1]))
    }

    @Test
    fun `a single venue takes the whole canvas`() {
        val rect = squarify(listOf(7f), width, height).single()

        assertEquals(0f, rect.x, 0.01f)
        assertEquals(width, rect.width, 0.01f)
        assertEquals(height, rect.height, 0.01f)
    }

    @Test
    fun `degenerate input does not throw`() {
        assertTrue(squarify(emptyList(), width, height).isEmpty())
        assertTrue(squarify(listOf(1f), 0f, height).isEmpty())
        assertEquals(2, squarify(listOf(0f, 0f), width, height).size)
    }

    @Test
    fun `equal weights get equal area`() {
        val rects = squarify(List(4) { 1f }, 200f, 200f)

        val areas = rects.map { areaOf(it) }
        assertTrue(areas.all { abs(it - 10_000f) < 1f })
    }
}
