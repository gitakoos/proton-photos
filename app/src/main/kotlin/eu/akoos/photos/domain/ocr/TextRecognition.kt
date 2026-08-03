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

package eu.akoos.photos.domain.ocr

import android.graphics.Bitmap
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.FitPoint

/**
 * The four corners a detector puts around one run of text, in the pixel coordinates of the frame it
 * read. They follow the text's own reading direction, so the [topLeft] to [topRight] edge runs along
 * the baseline whatever angle the words sit at. A sign photographed at a slant, a page held askew and
 * a vertical caption all come back as tight quadrilaterals; flattening them to upright rectangles
 * would swallow the neighbouring lines.
 */
data class TextQuad(
    val topLeft: FitPoint,
    val topRight: FitPoint,
    val bottomRight: FitPoint,
    val bottomLeft: FitPoint,
) {

    /** The corners in draw order, closing back on [topLeft]. */
    val corners: List<FitPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** The smallest upright box holding every corner, for callers that cannot carry an angle. */
    val bounds: FitBox = FitBox(
        left = minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
        top = minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y),
        right = maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
        bottom = maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y),
    )

    companion object {
        /** An unrotated quad, for text the detector found sitting square to the frame. */
        fun upright(left: Float, top: Float, right: Float, bottom: Float): TextQuad = TextQuad(
            topLeft = FitPoint(left, top),
            topRight = FitPoint(right, top),
            bottomRight = FitPoint(right, bottom),
            bottomLeft = FitPoint(left, bottom),
        )
    }
}

/**
 * How far a read has got. The two halves are separate waits worth telling apart: finding the words
 * is one pass over the whole frame, while turning them into characters is one pass per line found
 * and is where a busy photograph spends its time.
 */
enum class TextReadStage {

    /** Looking for where the words are. */
    Detecting,

    /** The words have been found and are being turned into characters. */
    Reading,
}

/**
 * One run of recognised text, placed in the pixel coordinates of the frame it was read from.
 *
 * [confidence] runs 0 to 1 and is the reader's own: how sure it is of the characters in [text]. It
 * is not the detector's score, which only ever said how sure it was that something was written
 * there and stays behind once a block has actually been read.
 */
data class RecognizedTextBlock(
    val text: String,
    val confidence: Float,
    val quad: TextQuad,
) {
    /** The upright extent of [quad], for layout that cannot carry an angle. */
    val bounds: FitBox get() = quad.bounds
}

/**
 * Reads the text in one frame.
 *
 * The frame arrives already downsampled and already turned the way the user sees it, so every
 * returned coordinate is in that bitmap's own pixel space and needs no orientation correction.
 * Implementations run on a background dispatcher of their own choosing and must stay cancellable,
 * because the viewer drops the request the moment the user swipes to another photo.
 */
interface TextRecognizer {

    suspend fun recognize(bitmap: Bitmap): List<RecognizedTextBlock>
}
