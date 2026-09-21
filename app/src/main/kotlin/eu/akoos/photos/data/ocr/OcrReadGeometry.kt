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

/*
 * The crop sizing, the upright turn and the angle-classifier rule below are derived from mobile_ocr,
 * which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

import eu.akoos.photos.domain.ocr.TextQuad
import eu.akoos.photos.util.FitPoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Height, in pixels, every crop is squeezed to before it reaches the reader. */
const val READ_HEIGHT = 48

/** Narrowest input the reader accepts, and the width an ordinary run of lines lands on. */
const val READ_MIN_WIDTH = 320

/**
 * Widest input a crop is scaled to, and so the width a long line's glyphs are read at. A line longer
 * than this is squeezed horizontally to fit rather than given more columns, which is a balance struck
 * in both directions: set it too low and a full-width line of small type is crushed until the reader
 * gives nothing back, set it too high and a single wide crop's output, one score per column across an
 * eighteen thousand entry alphabet, runs to tens of megabytes. This is wide enough to read a
 * full-width line of a large screenshot close to its own scale, and a crop this wide forms a reader
 * run of its own, so that output is paid for once rather than multiplied across a batch.
 */
const val READ_MAX_WIDTH = 2400

/** Most crops one reader run may carry. */
const val READ_MAX_BATCH = 6

/** Total input columns one reader run may cover, which is what actually bounds its output. */
const val READ_WIDTH_BUDGET = READ_MIN_WIDTH * READ_MAX_BATCH

/** Tallest a crop is cut at. The reader squeezes to [READ_HEIGHT] anyway, so more pixels are waste. */
const val CROP_HEIGHT_LIMIT = 96

/** Height over width at which a crop is a column of text rather than a line, and is turned upright. */
const val UPRIGHT_TURN_RATIO = 1.5f

/** Confidence the angle classifier has to reach before a crop is accepted as upside down. */
const val UPSIDE_DOWN_THRESHOLD = 0.9f

/**
 * How much of the frame one detected quad is cut out as, before the reader squeezes it.
 *
 * [width] and [height] are the quad's own edge lengths, so a line photographed at a slant comes out
 * as the straightened line rather than the upright box around it. [turnsUpright] marks the crop as a
 * column of text, which is quarter-turned so the reader always sees a horizontal line.
 */
data class OcrCropSize(val width: Int, val height: Int, val turnsUpright: Boolean) {

    /** The crop's width once [turnsUpright] has been applied. */
    val readWidth: Int get() = if (turnsUpright) height else width

    /** The crop's height once [turnsUpright] has been applied. */
    val readHeight: Int get() = if (turnsUpright) width else height

    /** What the reader batches on: wider means more columns, so like shapes are run together. */
    val readRatio: Float get() = readWidth.toFloat() / readHeight
}

/**
 * The pixel size [quad] is cut out at.
 *
 * The longer of each pair of opposing edges wins, so a quad whose corners drifted slightly still
 * covers every glyph it was drawn around rather than clipping the wider end. The result is then
 * scaled so the side that becomes the height stays within [heightLimit]: the reader squeezes to
 * [READ_HEIGHT] regardless, so a quad spanning a 4000 pixel photo would otherwise allocate a
 * sixteen megabyte bitmap to be thrown away one line later.
 */
fun cropSizeFor(quad: TextQuad, heightLimit: Int = CROP_HEIGHT_LIMIT): OcrCropSize {
    val width = max(
        distanceBetween(quad.topLeft, quad.topRight),
        distanceBetween(quad.bottomRight, quad.bottomLeft),
    )
    val height = max(
        distanceBetween(quad.topLeft, quad.bottomLeft),
        distanceBetween(quad.topRight, quad.bottomRight),
    )
    val turns = width > 0f && height / width >= UPRIGHT_TURN_RATIO
    val readHeight = if (turns) width else height
    val scale = if (readHeight > heightLimit && readHeight > 0f) heightLimit / readHeight else 1f
    return OcrCropSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
        turnsUpright = turns,
    )
}

/**
 * The input width a batch of crops with [maxRatio] as their widest shape is padded to. Every crop in
 * a run has to share one width, so the widest sets it and the narrower ones are zero-padded.
 */
fun readBatchWidth(maxRatio: Float): Int {
    if (!maxRatio.isFinite() || maxRatio <= 0f) return READ_MIN_WIDTH
    val natural = ceil(READ_HEIGHT * maxRatio).toInt()
    return natural.coerceIn(READ_MIN_WIDTH, READ_MAX_WIDTH)
}

/**
 * How [ratiosAscending] is split into reader runs, as the size of each run in order.
 *
 * Crops arrive sorted narrowest first so each run holds like shapes and pads as little as possible.
 * A run is closed when adding one more crop would take the run past [READ_WIDTH_BUDGET] columns or
 * past [READ_MAX_BATCH] crops, which is what keeps a run of banner-shaped crops from allocating an
 * output tensor several times the size of an ordinary one. A single crop always forms a run, however
 * wide it is, because there is nothing to split it with.
 */
fun planReadBatches(ratiosAscending: List<Float>): List<Int> {
    if (ratiosAscending.isEmpty()) return emptyList()
    val sizes = mutableListOf<Int>()
    var index = 0
    while (index < ratiosAscending.size) {
        var count = 1
        while (index + count < ratiosAscending.size && count < READ_MAX_BATCH) {
            val width = readBatchWidth(ratiosAscending[index + count])
            if (width.toLong() * (count + 1) > READ_WIDTH_BUDGET) break
            count++
        }
        sizes += count
        index += count
    }
    return sizes
}

/**
 * The width one crop of [ratio] occupies inside a run padded to [batchWidth]. Everything past it is
 * zeroes, and the reader has to know where the picture stops so it does not read the padding as
 * characters.
 */
fun readContentWidth(ratio: Float, batchWidth: Int): Int {
    if (!ratio.isFinite() || ratio <= 0f) return 1
    return min(ceil(READ_HEIGHT * ratio).toInt().coerceAtLeast(1), batchWidth)
}

/**
 * Whether the angle classifier's two scores mean the crop is upside down.
 *
 * The classifier answers with one score for upright and one for turned; a turn is only accepted when
 * it both wins and clears [UPSIDE_DOWN_THRESHOLD], because turning a line the wrong way round costs
 * the whole line and a near-tie carries no information worth acting on.
 */
fun classifierTurnsCrop(upright: Float, upsideDown: Float): Boolean =
    upsideDown > upright && upsideDown > UPSIDE_DOWN_THRESHOLD

private fun distanceBetween(a: FitPoint, b: FitPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}
