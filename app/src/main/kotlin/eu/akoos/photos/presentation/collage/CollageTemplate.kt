/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.presentation.collage

/**
 * A cell's rectangle within the collage canvas, as fractions of the canvas in `[0f, 1f]`, with
 * `(0, 0)` at the top-left. Kept framework-free (not an `android.graphics.RectF`) so the whole
 * layout catalogue is plain-JVM unit-testable, and so the same fractions render into any output
 * aspect: the UI multiplies them by the actual pixel width/height at draw time.
 */
data class CollageCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

/**
 * One collage layout: an ordered list of cells that tile the unit canvas edge-to-edge, one photo per
 * cell. The order is the order photos fill in, so cell 0 gets the first selected photo. Templates are
 * aspect-independent (fractional cells), so any template can render into any [CollageAspect].
 */
data class CollageTemplate(
    val id: String,
    val cells: List<CollageCell>,
    /** A freeform layout has no fixed cells: photos are placed, scaled and rotated by hand on a free
     *  canvas over the chosen background. Its [cells] list is empty and [cellCount] is not meaningful. */
    val freeform: Boolean = false,
) {
    val cellCount: Int get() = cells.size
}

/** The single freeform layout: an empty-cell template the UI renders as a free canvas. */
fun freeformCollageTemplate() = CollageTemplate("freeform", emptyList(), freeform = true)

/**
 * The output canvas aspect ratio (width : height). This only sets the canvas shape; the templates are
 * fractional, so every template works with every aspect. The UI may also offer an "original" aspect
 * derived from the first photo, which is computed there rather than being a fixed member here.
 */
enum class CollageAspect(val ratioW: Int, val ratioH: Int) {
    SQUARE(1, 1),
    PORTRAIT_4_5(4, 5),
    PORTRAIT_9_16(9, 16),
    LANDSCAPE_3_2(3, 2),
    LANDSCAPE_16_9(16, 9);

    /** width / height as a float. */
    val ratio: Float get() = ratioW.toFloat() / ratioH.toFloat()
}

/** Fewest photos a collage can hold. */
const val COLLAGE_MIN_PHOTOS = 2

/** Most photos a GRID collage can hold in this version. Above this the selection can still be trimmed. */
const val COLLAGE_MAX_PHOTOS = 6

/** Most photos a FREEFORM collage can hold: freeform places photos by hand, so it takes more than a
 *  grid's fixed cells. Above this the add button stops. */
const val COLLAGE_FREEFORM_MAX = 12

/**
 * The candidate layouts for [count] photos, the most balanced one first (so `first()` is a good
 * default). Returns an empty list when [count] is outside `[COLLAGE_MIN_PHOTOS, COLLAGE_MAX_PHOTOS]`.
 * Every template returned holds exactly [count] cells and tiles the canvas with no gaps or overlaps.
 */
fun collageTemplatesFor(count: Int): List<CollageTemplate> = when (count) {
    2 -> listOf(cols(2), rows(2), colsSplit(0.62f), rowsSplit(0.62f))
    3 -> listOf(cols(3), rows(3), bigLeftTwoRight(), twoTopOneBottom(), bigRightTwoLeft(), oneTopTwoBottom())
    4 -> listOf(grid(2, 2), bigLeftThreeRight(), oneTopThreeBottom(), threeTopOneBottom(), cols(4), rows(4))
    5 -> listOf(twoTopThreeBottom(), threeTopTwoBottom(), oneBigTopFourBottom(), oneBigLeftFourRight())
    6 -> listOf(grid(2, 3), grid(3, 2), twoTopFourBottom(), oneBigTopFiveBottom())
    else -> emptyList()
}

/** The default layout for [count] photos, or null when the count is unsupported. */
fun defaultCollageTemplate(count: Int): CollageTemplate? = collageTemplatesFor(count).firstOrNull()

// ── Layout builders ──────────────────────────────────────────────────────────
// All coordinates are canvas fractions; every builder returns an exact tiling of the unit square.

private fun cell(left: Float, top: Float, right: Float, bottom: Float) = CollageCell(left, top, right, bottom)

/** [n] equal-height horizontal strips, top to bottom. */
private fun rows(n: Int): CollageTemplate {
    val h = 1f / n
    return CollageTemplate("rows$n", (0 until n).map { cell(0f, it * h, 1f, (it + 1) * h) })
}

/** [n] equal-width vertical strips, left to right. */
private fun cols(n: Int): CollageTemplate {
    val w = 1f / n
    return CollageTemplate("cols$n", (0 until n).map { cell(it * w, 0f, (it + 1) * w, 1f) })
}

/** A [rows] x [cols] uniform grid, filled row by row. */
private fun grid(rows: Int, cols: Int): CollageTemplate {
    val w = 1f / cols
    val h = 1f / rows
    val cells = ArrayList<CollageCell>(rows * cols)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            cells += cell(c * w, r * h, (c + 1) * w, (r + 1) * h)
        }
    }
    return CollageTemplate("grid${rows}x$cols", cells)
}

/** One tall photo on the left half, two stacked on the right. */
private fun bigLeftTwoRight() = CollageTemplate(
    "bigLeft2Right",
    listOf(
        cell(0f, 0f, 0.5f, 1f),
        cell(0.5f, 0f, 1f, 0.5f),
        cell(0.5f, 0.5f, 1f, 1f),
    ),
)

/** Two photos across the top half, one wide photo along the bottom half. */
private fun twoTopOneBottom() = CollageTemplate(
    "2top1bottom",
    listOf(
        cell(0f, 0f, 0.5f, 0.5f),
        cell(0.5f, 0f, 1f, 0.5f),
        cell(0f, 0.5f, 1f, 1f),
    ),
)

/** One tall photo on the left 60%, three stacked on the right 40%. */
private fun bigLeftThreeRight(): CollageTemplate {
    val split = 0.6f
    val h = 1f / 3f
    return CollageTemplate(
        "bigLeft3Right",
        listOf(
            cell(0f, 0f, split, 1f),
            cell(split, 0f, 1f, h),
            cell(split, h, 1f, 2f * h),
            cell(split, 2f * h, 1f, 1f),
        ),
    )
}

/** Two photos across the top half, three across the bottom half. */
private fun twoTopThreeBottom(): CollageTemplate {
    val t = 1f / 3f
    return CollageTemplate(
        "2top3bottom",
        listOf(
            cell(0f, 0f, 0.5f, 0.5f),
            cell(0.5f, 0f, 1f, 0.5f),
            cell(0f, 0.5f, t, 1f),
            cell(t, 0.5f, 2f * t, 1f),
            cell(2f * t, 0.5f, 1f, 1f),
        ),
    )
}

/** One wide photo across the top 60%, four across the bottom 40%. */
private fun oneBigTopFourBottom() = CollageTemplate(
    "1big4bottom",
    listOf(
        cell(0f, 0f, 1f, 0.6f),
        cell(0f, 0.6f, 0.25f, 1f),
        cell(0.25f, 0.6f, 0.5f, 1f),
        cell(0.5f, 0.6f, 0.75f, 1f),
        cell(0.75f, 0.6f, 1f, 1f),
    ),
)

/** Two columns, the left taking [split] of the width. */
private fun colsSplit(split: Float) =
    CollageTemplate("cols2_${(split * 100).toInt()}", listOf(cell(0f, 0f, split, 1f), cell(split, 0f, 1f, 1f)))

/** Two rows, the top taking [split] of the height. */
private fun rowsSplit(split: Float) =
    CollageTemplate("rows2_${(split * 100).toInt()}", listOf(cell(0f, 0f, 1f, split), cell(0f, split, 1f, 1f)))

/** Two stacked on the left, one tall on the right. */
private fun bigRightTwoLeft() = CollageTemplate(
    "bigRight2Left",
    listOf(cell(0f, 0f, 0.5f, 0.5f), cell(0f, 0.5f, 0.5f, 1f), cell(0.5f, 0f, 1f, 1f)),
)

/** One wide photo on the top half, two on the bottom half. */
private fun oneTopTwoBottom() = CollageTemplate(
    "1top2bottom",
    listOf(cell(0f, 0f, 1f, 0.5f), cell(0f, 0.5f, 0.5f, 1f), cell(0.5f, 0.5f, 1f, 1f)),
)

/** One wide photo on the top half, three across the bottom half. */
private fun oneTopThreeBottom(): CollageTemplate {
    val t = 1f / 3f
    return CollageTemplate(
        "1top3bottom",
        listOf(
            cell(0f, 0f, 1f, 0.5f),
            cell(0f, 0.5f, t, 1f), cell(t, 0.5f, 2f * t, 1f), cell(2f * t, 0.5f, 1f, 1f),
        ),
    )
}

/** Three across the top half, one wide photo on the bottom half. */
private fun threeTopOneBottom(): CollageTemplate {
    val t = 1f / 3f
    return CollageTemplate(
        "3top1bottom",
        listOf(
            cell(0f, 0f, t, 0.5f), cell(t, 0f, 2f * t, 0.5f), cell(2f * t, 0f, 1f, 0.5f),
            cell(0f, 0.5f, 1f, 1f),
        ),
    )
}

/** Three across the top half, two on the bottom half. */
private fun threeTopTwoBottom(): CollageTemplate {
    val t = 1f / 3f
    return CollageTemplate(
        "3top2bottom",
        listOf(
            cell(0f, 0f, t, 0.5f), cell(t, 0f, 2f * t, 0.5f), cell(2f * t, 0f, 1f, 0.5f),
            cell(0f, 0.5f, 0.5f, 1f), cell(0.5f, 0.5f, 1f, 1f),
        ),
    )
}

/** One tall photo on the left 60%, four stacked on the right 40%. */
private fun oneBigLeftFourRight(): CollageTemplate {
    val q = 0.25f
    return CollageTemplate(
        "1bigLeft4Right",
        listOf(
            cell(0f, 0f, 0.6f, 1f),
            cell(0.6f, 0f, 1f, q), cell(0.6f, q, 1f, 2f * q), cell(0.6f, 2f * q, 1f, 3f * q), cell(0.6f, 3f * q, 1f, 1f),
        ),
    )
}

/** Two across the top half, four across the bottom half. */
private fun twoTopFourBottom() = CollageTemplate(
    "2top4bottom",
    listOf(
        cell(0f, 0f, 0.5f, 0.5f), cell(0.5f, 0f, 1f, 0.5f),
        cell(0f, 0.5f, 0.25f, 1f), cell(0.25f, 0.5f, 0.5f, 1f), cell(0.5f, 0.5f, 0.75f, 1f), cell(0.75f, 0.5f, 1f, 1f),
    ),
)

/** One wide photo across the top 55%, five across the bottom 45%. */
private fun oneBigTopFiveBottom() = CollageTemplate(
    "1bigTop5",
    listOf(
        cell(0f, 0f, 1f, 0.55f),
        cell(0f, 0.55f, 0.2f, 1f), cell(0.2f, 0.55f, 0.4f, 1f), cell(0.4f, 0.55f, 0.6f, 1f),
        cell(0.6f, 0.55f, 0.8f, 1f), cell(0.8f, 0.55f, 1f, 1f),
    ),
)
