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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop rectangle in bitmap-pixel space.
 *
 * Plain Kotlin rather than [android.graphics.Rect] because the unit-test source set builds
 * against the android.jar stubs with `unitTests.isReturnDefaultValues = true`: a stubbed
 * Rect constructor stores nothing, so every field would read 0 and the geometry tests would
 * pass against an empty rect without ever failing.
 */
internal data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal enum class CropHandle { TopLeft, TopRight, BottomLeft, BottomRight, Top, Bottom, Left, Right, Inside }

/**
 * The shapes the crop tool offers. Free leaves the rect unconstrained, which is how the tool
 * opens. Every other entry locks the shape: the rect holds that ratio while corners and edges are
 * dragged, so a 4:3 photo can be cropped smaller and still come out 4:3. Original locks to the
 * photo's own ratio, which is only known at runtime and so resolves from the bitmap rather than a
 * declared [ratio].
 *
 * The screen holds the chosen ENTRY, not its ratio. On a photo that already is 4:3, Original and
 * 4:3 are the same number, so a ratio cannot say which chip the user tapped and the wrong one
 * would light up.
 */
internal enum class CropAspect(val label: String, val ratio: Float?) {
    Free("Free", null),
    Original("Original", null),
    OneToOne("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f),
}

internal fun CropAspect.lockRatio(dispW: Int, dispH: Int): Float? = when (this) {
    CropAspect.Free -> null
    CropAspect.Original -> dispW.toFloat() / dispH
    else -> ratio
}

/**
 * The same shape after a quarter turn of the image. Free and 1:1 are unchanged by symmetry, and
 * Original because its ratio is read from the already-turned bitmap.
 */
internal fun CropAspect.turned(): CropAspect = when (this) {
    CropAspect.FourThree -> CropAspect.ThreeFour
    CropAspect.ThreeFour -> CropAspect.FourThree
    CropAspect.SixteenNine -> CropAspect.NineSixteen
    CropAspect.NineSixteen -> CropAspect.SixteenNine
    CropAspect.Free, CropAspect.Original, CropAspect.OneToOne -> this
}

internal fun CropBox.toRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

internal fun android.graphics.Rect.toCropBox(): CropBox = CropBox(left, top, right, bottom)

/** Largest [aspect]-shaped box centred inside a [srcW] x [srcH] image. */
internal fun centeredCrop(srcW: Int, srcH: Int, aspect: Float): CropBox {
    val srcRatio = srcW.toFloat() / srcH
    return if (srcRatio > aspect) {
        val newW = (srcH * aspect).toInt().coerceAtLeast(1)
        val xOffset = (srcW - newW) / 2
        CropBox(xOffset, 0, xOffset + newW, srcH)
    } else {
        val newH = (srcW / aspect).toInt().coerceAtLeast(1)
        val yOffset = (srcH - newH) / 2
        CropBox(0, yOffset, srcW, yOffset + newH)
    }
}

/**
 * Grab slop around a crop handle, in dp. Far wider than the drawn marker on purpose: a fingertip
 * covers roughly 38dp, so a hit area the size of the marker misses most touches and the gesture
 * falls through to pan-inside. Shared by the photo and video overlays so a handle feels the same
 * in both.
 */
internal const val CROP_HANDLE_TOUCH_SLOP_DP = 56f

/**
 * How far INSIDE the rect a handle still answers, as a fraction of the slop. Generous (~42dp at
 * the 56dp slop) so a resize can be started well clear of the screen edge, where the system's own
 * back gesture claims the touch first.
 */
private const val CROP_HANDLE_INWARD_FACTOR = 0.75f

/**
 * Ceiling on that inward reach per axis, as a fraction of the rect's own span. Two opposite
 * buffers then cover at most 60% of an axis, so even a tiny rect keeps a central block that
 * belongs to no handle and pan-inside stays reachable.
 */
private const val CROP_HANDLE_INWARD_MAX_SPAN_FRACTION = 0.3f

/**
 * Which crop handle a touch grabs, measured in screen pixels.
 *
 * [box] is the crop rect in image-pixel space; [scale] / [offsetX] / [offsetY] are the letterbox
 * fit that puts it on screen, and [pointX] / [pointY] are the touch in that same screen space.
 *
 * A touch reaches a full [touchSlopPx] OUTSIDE an edge and a clamped fraction of it INSIDE. The
 * two reaches differ because outside the rect there is nothing else to hit, while inside it the
 * pan-inside gesture has to keep some room. Corners win over edges, and a corner needs the touch
 * near TWO adjacent edges: a nearest-corner rule instead claims every touch in a portrait crop's
 * top half, so vertical drags appear to do nothing. Anything further in translates the rect
 * ([CropHandle.Inside]); anything further out is not a crop gesture (null).
 */
internal fun pickCropHandle(
    box: CropBox,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    pointX: Float,
    pointY: Float,
    touchSlopPx: Float,
): CropHandle? {
    val l = offsetX + box.left * scale
    val t = offsetY + box.top * scale
    val r = offsetX + box.right * scale
    val b = offsetY + box.bottom * scale

    val inward = touchSlopPx * CROP_HANDLE_INWARD_FACTOR
    val inwardX = min(inward, (r - l) * CROP_HANDLE_INWARD_MAX_SPAN_FRACTION)
    val inwardY = min(inward, (b - t) * CROP_HANDLE_INWARD_MAX_SPAN_FRACTION)

    val nearTop = (pointY - t) in -touchSlopPx..inwardY
    val nearBottom = (b - pointY) in -touchSlopPx..inwardY
    val nearLeft = (pointX - l) in -touchSlopPx..inwardX
    val nearRight = (r - pointX) in -touchSlopPx..inwardX

    val corner = when {
        nearTop && nearLeft -> CropHandle.TopLeft
        nearTop && nearRight -> CropHandle.TopRight
        nearBottom && nearLeft -> CropHandle.BottomLeft
        nearBottom && nearRight -> CropHandle.BottomRight
        else -> null
    }
    if (corner != null) return corner

    // Single edges — grab a side by its line: near that edge AND within the other axis's span.
    val withinX = pointX in (l - touchSlopPx)..(r + touchSlopPx)
    val withinY = pointY in (t - touchSlopPx)..(b + touchSlopPx)
    val edge = when {
        nearTop && withinX -> CropHandle.Top
        nearBottom && withinX -> CropHandle.Bottom
        nearLeft && withinY -> CropHandle.Left
        nearRight && withinY -> CropHandle.Right
        else -> null
    }
    if (edge != null) return edge

    return if (withinX && withinY) CropHandle.Inside else null
}

/**
 * How tall a run of gesture exclusions the platform honours per screen edge, in dp.
 * `View.setSystemGestureExclusionRects` drops the surplus without a word, so the overlay has to
 * fit inside it rather than hand over one tall strip.
 */
internal const val SYSTEM_GESTURE_EXCLUSION_BUDGET_DP = 200f

/** One square of screen the system's edge gesture must leave alone, in crop-container pixels. */
internal data class CropExclusionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where the crop overlay needs the system to yield its edge gesture, in the same screen-pixel
 * space [pickCropHandle] measures in.
 *
 * A handle inside the strip the system reserves for the edge back gesture cannot be grabbed at
 * all: the swipe is claimed before the overlay ever sees a touch. Only the three handles on the
 * rect's left edge and the three on its right edge compete with a left/right swipe, so the top and
 * bottom edges are left out, and a side is claimed only while its grab area actually reaches into
 * that strip. A rect parked mid-screen therefore asks for nothing, which keeps back reachable
 * everywhere it does not have to yield.
 *
 * [leftInsetPx] / [rightInsetPx] are the widths of those strips measured inwards from the
 * container's own edges. A container held a few dp off the display edge makes the test marginally
 * generous, which costs at most one exclusion nobody needed. A zero inset means the navigation
 * mode has no edge gesture to yield, so nothing is emitted.
 *
 * Budget: three handles a side, each a [CROP_HANDLE_TOUCH_SLOP_DP] grab area tall, is
 * 3 x 56 = 168dp against the 200dp of [SYSTEM_GESTURE_EXCLUSION_BUDGET_DP]. A fourth handle a
 * side (224dp), or a slop past 66dp, puts the run over the ceiling and the platform starts
 * dropping rects silently.
 */
internal fun cropGestureExclusions(
    leftPx: Float,
    topPx: Float,
    rightPx: Float,
    bottomPx: Float,
    containerWidthPx: Float,
    leftInsetPx: Float,
    rightInsetPx: Float,
    slopPx: Float,
): List<CropExclusionRect> {
    if (slopPx <= 0f) return emptyList()
    val half = slopPx / 2f
    // The three grabs a vertical edge offers: its top corner, its middle, its bottom corner.
    val centresY = listOf(topPx, (topPx + bottomPx) / 2f, bottomPx)
    val rects = ArrayList<CropExclusionRect>(6)
    if (leftInsetPx > 0f && leftPx - half < leftInsetPx) {
        centresY.mapTo(rects) { cy -> CropExclusionRect(leftPx - half, cy - half, leftPx + half, cy + half) }
    }
    if (rightInsetPx > 0f && rightPx + half > containerWidthPx - rightInsetPx) {
        centresY.mapTo(rects) { cy -> CropExclusionRect(rightPx - half, cy - half, rightPx + half, cy + half) }
    }
    return rects
}

/**
 * One resize decision for the crop overlay, in bitmap-pixel space.
 *
 * [bx] / [by] is the finger in bitmap coordinates, already clamped to the bitmap by the
 * caller. Working from an absolute position rather than accumulated deltas keeps the grabbed
 * corner pinned under the finger on fast drags.
 *
 * A null [ratio] is free-form: each handle moves its own edge(s) and nothing else. A non-null
 * [ratio] (width / height) is locked: the handle opposite the grab stays put, the finger sets
 * the driving dimension, and the other one follows from the ratio. When the result would leave
 * the bitmap it shrinks along both axes instead of clipping one, so the ratio survives the edge
 * of the image; the anchor never moves while that happens.
 *
 * [minPx] is a per-axis floor. Under a lock it is applied through the ratio, or a tall shape at
 * its minimum height would come out only a few pixels wide.
 */
internal fun resizeCrop(
    box: CropBox,
    handle: CropHandle,
    bx: Int,
    by: Int,
    ratio: Float?,
    boundsW: Int,
    boundsH: Int,
    minPx: Int,
    insideOffsetX: Int = 0,
    insideOffsetY: Int = 0,
): CropBox {
    if (handle == CropHandle.Inside) {
        // Bodily translate, preserving width x height and clamping to bitmap bounds.
        val w = box.width
        val h = box.height
        val newLeft = (bx - insideOffsetX).coerceIn(0, (boundsW - w).coerceAtLeast(0))
        val newTop = (by - insideOffsetY).coerceIn(0, (boundsH - h).coerceAtLeast(0))
        return CropBox(newLeft, newTop, newLeft + w, newTop + h)
    }
    if (ratio == null || ratio <= 0f) {
        // A photo smaller than the minimum has no frame that honours it: the minimum put the grabbed edge past
        // the opposite one, outside the photo. Such a photo takes half its short side as the floor instead.
        val free = resizeFree(box, handle, bx, by, min(minPx, min(boundsW, boundsH) / 2).coerceAtLeast(1))
        return CropBox(
            free.left.coerceIn(0, boundsW), free.top.coerceIn(0, boundsH),
            free.right.coerceIn(0, boundsW), free.bottom.coerceIn(0, boundsH),
        )
    }
    return when (handle) {
        CropHandle.TopLeft, CropHandle.TopRight, CropHandle.BottomLeft, CropHandle.BottomRight ->
            resizeLockedCorner(box, handle, bx, by, ratio, boundsW, boundsH, minPx)
        CropHandle.Left, CropHandle.Right ->
            resizeLockedHorizontal(box, handle, bx, ratio, boundsW, boundsH, minPx)
        CropHandle.Top, CropHandle.Bottom ->
            resizeLockedVertical(box, handle, by, ratio, boundsW, boundsH, minPx)
        CropHandle.Inside -> box
    }
}

/** Free-form resize: the grabbed edge follows the finger, the opposite three stay put. */
private fun resizeFree(box: CropBox, handle: CropHandle, bx: Int, by: Int, minPx: Int): CropBox =
    when (handle) {
        CropHandle.TopLeft -> CropBox(
            bx.coerceAtMost(box.right - minPx), by.coerceAtMost(box.bottom - minPx), box.right, box.bottom,
        )
        CropHandle.TopRight -> CropBox(
            box.left, by.coerceAtMost(box.bottom - minPx), bx.coerceAtLeast(box.left + minPx), box.bottom,
        )
        CropHandle.BottomLeft -> CropBox(
            bx.coerceAtMost(box.right - minPx), box.top, box.right, by.coerceAtLeast(box.top + minPx),
        )
        CropHandle.BottomRight -> CropBox(
            box.left, box.top, bx.coerceAtLeast(box.left + minPx), by.coerceAtLeast(box.top + minPx),
        )
        CropHandle.Top -> CropBox(box.left, by.coerceAtMost(box.bottom - minPx), box.right, box.bottom)
        CropHandle.Bottom -> CropBox(box.left, box.top, box.right, by.coerceAtLeast(box.top + minPx))
        CropHandle.Left -> CropBox(bx.coerceAtMost(box.right - minPx), box.top, box.right, box.bottom)
        CropHandle.Right -> CropBox(box.left, box.top, bx.coerceAtLeast(box.left + minPx), box.bottom)
        CropHandle.Inside -> box
    }

/**
 * Bounds win over the minimum: on an image smaller than [minPx] there is no size that satisfies
 * both, and returning the largest one that fits keeps the rect inside the bitmap.
 */
private fun clampSize(want: Float, minSize: Float, maxSize: Float): Float =
    if (maxSize < minSize) maxSize else want.coerceIn(minSize, maxSize)

private fun resizeLockedCorner(
    box: CropBox,
    handle: CropHandle,
    bx: Int,
    by: Int,
    ratio: Float,
    boundsW: Int,
    boundsH: Int,
    minPx: Int,
): CropBox {
    val growsLeft = handle == CropHandle.TopLeft || handle == CropHandle.BottomLeft
    val growsUp = handle == CropHandle.TopLeft || handle == CropHandle.TopRight
    val anchorX = if (growsLeft) box.right else box.left
    val anchorY = if (growsUp) box.bottom else box.top

    // Room between the anchor and the edge of the bitmap, in the direction the rect grows.
    val availW = (if (growsLeft) anchorX else boundsW - anchorX).toFloat()
    val availH = (if (growsUp) anchorY else boundsH - anchorY).toFloat()

    // Measure from the anchor in the direction the rect grows, and ONLY in that direction. An
    // unsigned distance would mirror: a finger dragged past the anchor would start growing the
    // rect again on the far side, ending up larger than it began.
    val dx = (if (growsLeft) anchorX - bx else bx - anchorX).coerceAtLeast(0)
    val dy = (if (growsUp) anchorY - by else by - anchorY).coerceAtLeast(0)
    // Follow whichever axis the finger reached furthest, so the corner tracks a diagonal drag
    // instead of sticking to one axis.
    val want = max(dx.toFloat(), dy * ratio)
    val w = clampSize(want, max(minPx.toFloat(), minPx * ratio), min(availW, availH * ratio))
    val wi = w.roundToInt().coerceAtLeast(1)
    val hi = (w / ratio).roundToInt().coerceAtLeast(1)

    val left = if (growsLeft) anchorX - wi else anchorX
    val top = if (growsUp) anchorY - hi else anchorY
    return CropBox(left, top, left + wi, top + hi)
}

private fun resizeLockedHorizontal(
    box: CropBox,
    handle: CropHandle,
    bx: Int,
    ratio: Float,
    boundsW: Int,
    boundsH: Int,
    minPx: Int,
): CropBox {
    val growsLeft = handle == CropHandle.Left
    val anchorX = if (growsLeft) box.right else box.left
    val availW = (if (growsLeft) anchorX else boundsW - anchorX).toFloat()

    val want = (if (growsLeft) anchorX - bx else bx - anchorX).coerceAtLeast(0).toFloat()
    val w = clampSize(want, max(minPx.toFloat(), minPx * ratio), min(availW, boundsH * ratio))
    val wi = w.roundToInt().coerceAtLeast(1)
    val hi = (w / ratio).roundToInt().coerceAtLeast(1)

    val left = if (growsLeft) anchorX - wi else anchorX
    // The untouched axis keeps its centre, then slides just enough to stay on the bitmap.
    val centerY = (box.top + box.bottom) / 2
    val top = (centerY - hi / 2).coerceIn(0, (boundsH - hi).coerceAtLeast(0))
    return CropBox(left, top, left + wi, top + hi)
}

private fun resizeLockedVertical(
    box: CropBox,
    handle: CropHandle,
    by: Int,
    ratio: Float,
    boundsW: Int,
    boundsH: Int,
    minPx: Int,
): CropBox {
    val growsUp = handle == CropHandle.Top
    val anchorY = if (growsUp) box.bottom else box.top
    val availH = (if (growsUp) anchorY else boundsH - anchorY).toFloat()

    val want = (if (growsUp) anchorY - by else by - anchorY).coerceAtLeast(0).toFloat()
    val h = clampSize(want, max(minPx.toFloat(), minPx / ratio), min(availH, boundsW / ratio))
    val hi = h.roundToInt().coerceAtLeast(1)
    val wi = (h * ratio).roundToInt().coerceAtLeast(1)

    val top = if (growsUp) anchorY - hi else anchorY
    val centerX = (box.left + box.right) / 2
    val left = (centerX - wi / 2).coerceIn(0, (boundsW - wi).coerceAtLeast(0))
    return CropBox(left, top, left + wi, top + hi)
}

/**
 * The crop frame during a two-finger pinch, in bitmap-pixel space: the photo stays still and the
 * frame resizes as if all four edges were dragged at once.
 *
 * Every input is measured from when the pinch began, so rounding never piles up: [start] is the
 * frame then, ([focusX], [focusY]) the point between the fingers then, [scale] their spread since
 * (above 1 grows the frame) and ([panX], [panY]) how far that point has moved. The focus keeps its
 * relative place in the frame, taken from the nearest border point when it lies outside, and the
 * pan then carries the frame along.
 *
 * The frame keeps [ratio], or [start]'s own shape when that is null as under Free. Its size holds
 * [minPx] on both axes through the ratio and stops at the largest box of that shape the photo holds,
 * so the last step only slides it back inside. A scale that is not a finite number counts as 1.
 */
internal fun scaleCropAbout(
    start: CropBox,
    focusX: Float,
    focusY: Float,
    scale: Float,
    panX: Float,
    panY: Float,
    ratio: Float?,
    boundsW: Int,
    boundsH: Int,
    minPx: Int,
): CropBox {
    if (boundsW <= 0 || boundsH <= 0) return CropBox(0, 0, 0, 0)
    val keep = ratio?.takeIf { it.isFinite() && it > 0f }
        ?: (start.width.toFloat() / start.height).takeIf { it.isFinite() && it > 0f }
        ?: 1f
    val s = if (scale.isFinite()) scale else 1f

    val largestW = centeredCrop(boundsW, boundsH, keep).width.toFloat()
    val w = clampSize(start.width * s, max(minPx.toFloat(), minPx * keep), largestW)
    // The height follows the ROUNDED width, which keeps the ratio within half a pixel of height.
    val wi = w.roundToInt().coerceIn(1, boundsW)
    val hi = (wi / keep).roundToInt().coerceIn(1, boundsH)

    val u = fractionAlong(focusX, start.left, start.width)
    val v = fractionAlong(focusY, start.top, start.height)
    val dx = if (panX.isFinite()) panX else 0f
    val dy = if (panY.isFinite()) panY else 0f
    val left = start.left + u * start.width + dx - u * wi
    val top = start.top + v * start.height + dy - v * hi
    return slideInsideBounds(left, top, wi, hi, boundsW, boundsH)
}

/** Where [point] sits along a span as a fraction in 0..1, or its middle when that is undefined. */
private fun fractionAlong(point: Float, from: Int, span: Int): Float {
    val t = (point - from) / span
    return if (t.isFinite()) t.coerceIn(0f, 1f) else 0.5f
}

/**
 * A [width] x [height] frame placed at ([left], [top]) and slid back onto the [boundsW] x [boundsH]
 * photo where it overhangs. Only the position moves; the frame must already fit.
 */
private fun slideInsideBounds(
    left: Float,
    top: Float,
    width: Int,
    height: Int,
    boundsW: Int,
    boundsH: Int,
): CropBox {
    val l = left.coerceIn(0f, (boundsW - width).coerceAtLeast(0).toFloat()).roundToInt()
    val t = top.coerceIn(0f, (boundsH - height).coerceAtLeast(0).toFloat()).roundToInt()
    return CropBox(l, t, l + width, t + height)
}
