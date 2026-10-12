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

package eu.akoos.photos.presentation.duplicates

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/** How far a copy can be enlarged in the duplicate review. */
internal const val MAX_REVIEW_ZOOM = 6f

/** The enlargement a double tap jumps to. */
internal const val DOUBLE_TAP_ZOOM = 3f

/**
 * How a copy is enlarged in the duplicate review: the [scale] (1 = fitted to the screen) and the [offset] of
 * its centre from the view's centre, in pixels. Both surfaces scale around the view centre.
 */
internal data class PhotoZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val isZoomed: Boolean get() = scale > 1f
}

/**
 * The zoom after a pinch and pan: the content under [centroid] stays put as the scale changes, the finger pan
 * is added, and the offset is held inside the reachable range so the picture never slides off the view.
 * Back at scale 1 the offset is zero.
 */
internal fun applyPinchPan(current: PhotoZoom, zoomChange: Float, panChange: Offset, centroid: Offset, view: Size): PhotoZoom {
    val newScale = (current.scale * zoomChange).coerceIn(1f, MAX_REVIEW_ZOOM)
    if (newScale <= 1f) return PhotoZoom()
    val applied = if (current.scale != 0f) newScale / current.scale else 1f
    val cx = view.width / 2f
    val cy = view.height / 2f
    val maxX = view.width * (newScale - 1f) / 2f
    val maxY = view.height * (newScale - 1f) / 2f
    return PhotoZoom(
        scale = newScale,
        offset = Offset(
            ((centroid.x - cx) * (1f - applied) + applied * current.offset.x + panChange.x).coerceIn(-maxX, maxX),
            ((centroid.y - cy) * (1f - applied) + applied * current.offset.y + panChange.y).coerceIn(-maxY, maxY),
        ),
    )
}

/** A double tap: an enlarged copy goes back to fitted, a fitted one jumps to [DOUBLE_TAP_ZOOM] around the tap. */
internal fun applyDoubleTap(current: PhotoZoom, tap: Offset, view: Size): PhotoZoom =
    if (current.isZoomed) PhotoZoom() else applyPinchPan(current, DOUBLE_TAP_ZOOM, Offset.Zero, tap, view)

/**
 * Pinch, drag and double tap that enlarge a copy. A single finger on a fitted copy is left alone, so the
 * pager beside it still swipes; two fingers, or a drag on an enlarged copy, are consumed here. [zoom] and
 * [view] are read as the gesture runs, so a fast pinch always builds on the latest state.
 */
internal fun Modifier.photoZoomGestures(
    zoom: () -> PhotoZoom,
    view: () -> Size,
    onZoomChange: (PhotoZoom) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { tap -> onZoomChange(applyDoubleTap(zoom(), tap, view())) })
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val taken = event.changes.any { it.isConsumed }
                val twoFingers = event.changes.count { it.pressed } > 1
                if (!taken && (twoFingers || zoom().isZoomed)) {
                    val zoomChange = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoomChange != 1f || pan != Offset.Zero) {
                        onZoomChange(applyPinchPan(zoom(), zoomChange, pan, event.calculateCentroid(useCurrent = false), view()))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (!taken && event.changes.any { it.pressed })
        }
    }
