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

package eu.akoos.photos.presentation.editor

import kotlin.math.min
import kotlin.math.roundToInt

/** Room between a text and the edge of its background, sideways, as a multiple of the font size. */
internal const val TEXT_BACKDROP_PAD_X = 0.4f

/** Room between a text and the edge of its background, above and below, as a multiple of the font size. */
internal const val TEXT_BACKDROP_PAD_Y = 0.12f

/**
 * A text's background box, in the pixels the text was measured in: half its width and half its height, both from the
 * text's centre, and the corner radius.
 */
internal data class TextBackdrop(val halfWidth: Float, val halfHeight: Float, val radius: Float)

/**
 * The background box of a text of [lineCount] lines at [fontPx], whose widest line is [widestLinePx] wide. The lines
 * are 1.2 times the font size apart, as the text is laid out, and the box keeps [TEXT_BACKDROP_PAD_X] and
 * [TEXT_BACKDROP_PAD_Y] of room round them. [cornerRadius] runs from 0, square, to 1, fully rounded ends, and a
 * number that is not one reads as square. One function lays out the live overlay and the full-size save alike.
 */
internal fun textBackdrop(fontPx: Float, widestLinePx: Float, lineCount: Int, cornerRadius: Float): TextBackdrop {
    val lines = lineCount.coerceAtLeast(1)
    val halfW = widestLinePx.coerceAtLeast(0f) / 2f + fontPx * TEXT_BACKDROP_PAD_X
    val halfH = fontPx * 1.2f * lines / 2f + fontPx * TEXT_BACKDROP_PAD_Y
    val corner = if (cornerRadius.isNaN()) 0f else cornerRadius.coerceIn(0f, 1f)
    return TextBackdrop(halfW, halfH, min(halfW, halfH) * corner)
}

/** Whether [this] text has a background to draw: one with no opacity is none. */
internal fun TextItem.hasBackground(): Boolean = backgroundOpacity > 0.001f

/** The background colour of [item] as ARGB: its colour with the opacity as the alpha. */
internal fun textBackgroundArgb(item: TextItem): Int {
    val opacity = if (item.backgroundOpacity.isNaN()) 0f else item.backgroundOpacity.coerceIn(0f, 1f)
    return ((opacity * 255f).roundToInt() shl 24) or (item.background and 0x00FFFFFF)
}
