package dev.abhinav.artistpin.feature.stats

/** The height of a year's tile stack, in dp. */
const val STACK_HEIGHT = 156f

/** How many years the chart shows without scrolling. */
const val VISIBLE_YEARS = 8

/**
 * The fixed width a year column needs so exactly [visible] of them fill the card, or null when
 * there are few enough years to share the width equally.
 *
 * Split out as arithmetic because the choice it encodes is a judgement, not a layout detail: below
 * the threshold the chart stays exactly as designed, filling the card; past it the columns stop
 * shrinking and the row scrolls instead. Letting them keep shrinking is what eventually collides
 * the year labels and then clips the tiles.
 */
fun yearColumnWidth(
    availableWidth: Float,
    years: Int,
    gap: Float,
    visible: Int = VISIBLE_YEARS,
): Float? {
    if (years <= visible || visible <= 0 || availableWidth <= 0f) return null
    return ((availableWidth - gap * (visible - 1)) / visible).coerceAtLeast(0f)
}

/** A tile's meaning and size, shared by every column so the legend can describe all of them. */
data class YearTileScale(
    /** How many shows one tile stands for. 1 until a year is too tall to draw show by show. */
    val showsPerTile: Int,
    val tileHeight: Float,
    val gap: Float,
) {
    /** Tiles drawn for a year — the last one is partial in meaning, never in size. */
    fun tilesFor(shows: Int): Int = if (shows <= 0) 0 else (shows + showsPerTile - 1) / showsPerTile

    val legend: String
        get() = if (showsPerTile == 1) "= 1 show" else "= $showsPerTile shows"
}

private const val FULL_TILE = 8f
private const val FULL_GAP = 3f
private const val MIN_TILE = 4f
private const val TIGHT_GAP = 2f

/**
 * How to draw a stack of one tile per show when the busiest year has [peakShows] of them.
 *
 * One tile per show is the design and holds for any year that fits. Past that the tiles shrink,
 * and past *that* — below the 4dp where a tile stops being a tile and becomes a line — one tile
 * starts standing for several shows and the legend says so. The alternative, which is what this
 * replaces, was tiles pinned at 2dp and a stack three times taller than the track it sits in: a
 * 100-show year drew about 500dp of column straight through the card.
 *
 * The scale is computed from the peak year and shared by all of them, because a chart whose unit
 * changes column by column is not a chart.
 */
fun yearTileScale(peakShows: Int, track: Float = STACK_HEIGHT): YearTileScale {
    if (peakShows <= 0 || track <= 0f) return YearTileScale(1, FULL_TILE, FULL_GAP)

    fun stackHeight(tiles: Int, tile: Float, gap: Float) = tiles * tile + (tiles - 1) * gap

    if (stackHeight(peakShows, FULL_TILE, FULL_GAP) <= track) {
        return YearTileScale(1, FULL_TILE, FULL_GAP)
    }

    // Still one tile per show, just a shorter tile.
    val shrunk = (track - (peakShows - 1) * TIGHT_GAP) / peakShows
    if (shrunk >= MIN_TILE) {
        return YearTileScale(1, shrunk.coerceAtMost(FULL_TILE), TIGHT_GAP)
    }

    // One tile, several shows. Enough tiles to fill the track at the smallest legible size.
    val maxTiles = ((track + TIGHT_GAP) / (MIN_TILE + TIGHT_GAP)).toInt().coerceAtLeast(1)
    val showsPerTile = ((peakShows + maxTiles - 1) / maxTiles).coerceAtLeast(1)
    val tiles = (peakShows + showsPerTile - 1) / showsPerTile
    val tile = ((track - (tiles - 1) * TIGHT_GAP) / tiles).coerceIn(MIN_TILE, FULL_TILE)
    return YearTileScale(showsPerTile, tile, TIGHT_GAP)
}
