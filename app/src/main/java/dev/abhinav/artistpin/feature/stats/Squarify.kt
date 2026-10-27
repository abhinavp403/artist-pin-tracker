package dev.abhinav.artistpin.feature.stats

import kotlin.math.max
import kotlin.math.min

/** A box in the treemap, in the same units as the width and height it was laid out in. */
data class TreemapRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Squarified treemap layout (Bruls, Huizing, van Wijk).
 *
 * Boxes get area in proportion to their weight, and the algorithm prefers shapes close to square,
 * because a 4-pixel-wide sliver cannot carry a venue name. Weights are expected largest-first;
 * the output keeps the input order so callers can zip it back to their own items.
 *
 * Kept as a pure function over numbers rather than a Composable: the part worth testing is the
 * geometry, and it needs no layout pass to check.
 */
fun squarify(weights: List<Float>, width: Float, height: Float): List<TreemapRect> {
    if (weights.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val total = weights.sum()
    if (total <= 0f) return weights.map { TreemapRect(0f, 0f, 0f, 0f) }

    val scale = width * height / total
    val areas = weights.map { it * scale }
    val result = MutableList(weights.size) { TreemapRect(0f, 0f, 0f, 0f) }

    var freeX = 0f
    var freeY = 0f
    var freeW = width
    var freeH = height
    var index = 0
    var row = mutableListOf<Int>()

    /** The worst aspect ratio in a row — what the algorithm minimises. */
    fun worst(candidate: List<Int>): Float {
        val side = min(freeW, freeH)
        val sum = candidate.sumOf { areas[it].toDouble() }.toFloat()
        if (sum <= 0f || side <= 0f) return Float.MAX_VALUE
        val maxArea = candidate.maxOf { areas[it] }
        val minArea = candidate.minOf { areas[it] }
        return max(side * side * maxArea / (sum * sum), sum * sum / (side * side * minArea))
    }

    fun placeRow() {
        val sum = row.sumOf { areas[it].toDouble() }.toFloat()
        if (sum <= 0f) { row = mutableListOf(); return }
        if (freeW >= freeH) {
            // A column down the left of what is left.
            val colWidth = sum / freeH
            var y = freeY
            for (i in row) {
                val h = if (sum == 0f) 0f else areas[i] / colWidth
                result[i] = TreemapRect(freeX, y, colWidth, h)
                y += h
            }
            freeX += colWidth
            freeW -= colWidth
        } else {
            // A band across the top.
            val rowHeight = sum / freeW
            var x = freeX
            for (i in row) {
                val w = if (sum == 0f) 0f else areas[i] / rowHeight
                result[i] = TreemapRect(x, freeY, w, rowHeight)
                x += w
            }
            freeY += rowHeight
            freeH -= rowHeight
        }
        row = mutableListOf()
    }

    while (index < areas.size) {
        if (row.isEmpty()) {
            row.add(index)
            index++
            continue
        }
        val candidate = row + index
        if (worst(candidate) <= worst(row)) {
            row.add(index)
            index++
        } else {
            placeRow()
        }
    }
    if (row.isNotEmpty()) placeRow()

    return result
}
