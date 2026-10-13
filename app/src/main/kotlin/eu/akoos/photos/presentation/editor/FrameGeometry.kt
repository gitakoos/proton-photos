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

import eu.akoos.photos.util.FitBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ratios a frame's canvas can take, in the order the shape chips show them: the tall ones, then the wide ones. Original follows the photo, so it
 * resolves at layout time rather than from a declared [ratio]: the natural shape of the photo and its margins. A list
 * of its own rather than more [CropAspect] entries, since both crop tools offer every one of those as a chip.
 */
enum class FrameRatio(val label: String, val ratio: Float?) {
    Original("Original", null),
    OneToOne("1:1", 1f),
    FourFive("4:5", 4f / 5f),
    ThreeFour("3:4", 3f / 4f),
    TwoThree("2:3", 2f / 3f),
    NineSixteen("9:16", 9f / 16f),
    FourThree("4:3", 4f / 3f),
    ThreeTwo("3:2", 3f / 2f),
    SixteenNine("16:9", 16f / 9f),
}

/** What covers the canvas around the photo: one ARGB colour, or a blurred copy of the photo itself. */
sealed interface FrameFill {
    data class Solid(val argb: Int) : FrameFill
    data object Blur : FrameFill
}

/** Opaque white as ARGB: the first fill swatch, and the fill a first frame opens with. */
internal const val FRAME_WHITE = 0xFFFFFFFF.toInt()

/** Opaque black as ARGB: the second fill swatch. */
internal const val FRAME_BLACK = 0xFF000000.toInt()

/** Longest side of a framed photo, in pixels. A frame that would be larger scales down whole, the photo with it. */
internal const val FRAME_MAX_LONG_SIDE = 4096

/** Smallest [FrameSpec.scale]: the photo at 30% of the fit, the zoom-out floor a collage cell has too. */
internal const val FRAME_MIN_SCALE = 0.3f

/** The even border every shape but Polaroid opens with, on each side, as a fraction of the photo's shorter side. */
internal const val FRAME_BORDER_MARGIN = 0.05f

/** Polaroid's top, left and right border, as a fraction of the photo's shorter side. */
internal const val FRAME_POLAROID_MARGIN = 0.06f

/** Polaroid's bottom border, in multiples of its other three. */
internal const val FRAME_POLAROID_BOTTOM_FACTOR = 3.5f

/** How near the middle of the frame, in dp on the preview, a dragged photo's centre comes before it snaps there. */
internal const val FRAME_SNAP_DP = 12f

/** Pixels of the small copy a blurred fill is made from, along the long side of the canvas it covers. */
internal const val FRAME_BLUR_CELLS = 64

/** Longest side, in pixels, that small copy may take, whatever shapes the picture and the canvas have. */
internal const val FRAME_BLUR_MAX_SIDE = 512

/** Radius, in pixels of that small copy, of each box blur that softens it. */
internal const val FRAME_BLUR_RADIUS = 2

/** How many box blurs soften that small copy; three come close to a Gaussian. */
internal const val FRAME_BLUR_PASSES = 3

/** The black laid over a blurred fill, as ARGB: about 28%, so the sharp photo stands out against it. */
internal const val FRAME_BLUR_SCRIM = 0x47000000

/** Widest border a spec can ask for on one side, as a fraction of the photo's shorter side. */
private const val FRAME_MAX_MARGIN = 1f

/** Widest border the Frame tool's slider sets, as a fraction of the photo's shorter side. */
internal const val FRAME_MAX_BORDER = 0.3f

/** Narrowest border a Polaroid keeps: with none, its deeper bottom would be gone and it would read as a plain frame. */
internal const val FRAME_MIN_CARD_BORDER = 0.01f

/**
 * Less room than this along an axis, in canvas pixels, counts as none: the photo has nowhere visible to go there, and
 * a drag divided by it would be all but infinite.
 */
private const val FRAME_MIN_ROOM_PX = 0.5f

/**
 * A frame around the photo.
 *
 * The canvas is the smallest [ratio]-shaped box that holds the whole photo at full size plus its margins, so a
 * [scale] of 1 keeps every pixel. [margin] is a border the photo never enters on the top, left and right, and
 * [bottomMargin] the one below it, both as fractions of the photo's shorter side. That keeps a border's proportions
 * on any photo shape: every shape opens with [FRAME_BORDER_MARGIN] all round, which at Original is an even border
 * and in a fixed ratio the border on the sides that hold the photo, and Polaroid is [FRAME_POLAROID_MARGIN] with
 * [FRAME_POLAROID_BOTTOM_FACTOR] times that below. With both at 0, Original is the photo's own ratio.
 *
 * [scale] sizes the photo within the margins: 1 fills the space between them on the limiting side, so a 16:9 photo
 * in a 1:1 frame spans the full width with bars above and below, and less shrinks it, down to [FRAME_MIN_SCALE].
 * [offsetX] and [offsetY] place it in the room that leaves on each axis, from -1 (against the left or top) through 0
 * (centred) to 1 (against the right or bottom). Everything is relative, so one spec lays out the preview and the
 * full-size save alike.
 *
 * Numbers out of range are clamped wherever a spec is read; one that is not a number reads as 1 for [scale] and 0
 * for the rest.
 */
data class FrameSpec(
    val ratio: FrameRatio = FrameRatio.Original,
    val fill: FrameFill = FrameFill.Solid(FRAME_WHITE),
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val margin: Float = 0f,
    val bottomMargin: Float = margin,
)

/**
 * The shapes the Frame tool offers, in chip order. Off is no frame at all: the edit carries no [FrameSpec]. Polaroid
 * is the card with the deep bottom border, in its own natural shape; every other shape is an even border in [ratio].
 */
internal enum class FrameShape(val ratio: FrameRatio?) {
    Off(null),
    Original(FrameRatio.Original),
    OneToOne(FrameRatio.OneToOne),
    FourFive(FrameRatio.FourFive),
    ThreeFour(FrameRatio.ThreeFour),
    TwoThree(FrameRatio.TwoThree),
    NineSixteen(FrameRatio.NineSixteen),
    FourThree(FrameRatio.FourThree),
    ThreeTwo(FrameRatio.ThreeTwo),
    SixteenNine(FrameRatio.SixteenNine),
    Polaroid(FrameRatio.Original),
}

/**
 * The frame [this] shape opens with in [fill], or null for [FrameShape.Off]: the photo at full size in the middle,
 * behind Polaroid's card or an even border of [FRAME_BORDER_MARGIN] of its shorter side on the sides that hold it.
 * At Original that border runs all round; in a fixed ratio the other two sides get equal, wider bars.
 */
internal fun FrameShape.toSpec(fill: FrameFill = FrameFill.Solid(FRAME_WHITE)): FrameSpec? {
    val ratio = ratio ?: return null
    return if (this == FrameShape.Polaroid) {
        FrameSpec(
            ratio = ratio,
            fill = fill,
            margin = FRAME_POLAROID_MARGIN,
            bottomMargin = FRAME_POLAROID_MARGIN * FRAME_POLAROID_BOTTOM_FACTOR,
        )
    } else {
        FrameSpec(ratio = ratio, fill = fill, margin = FRAME_BORDER_MARGIN, bottomMargin = FRAME_BORDER_MARGIN)
    }
}

/**
 * The shape chip [spec] shows as: Off for no frame, Polaroid for a deeper border below than beside, else the chip of
 * its ratio. Read from the spec rather than kept beside it, so the lit chip follows an undo.
 */
internal fun frameShapeOf(spec: FrameSpec?): FrameShape {
    val s = spec?.clamped() ?: return FrameShape.Off
    if (s.bottomMargin > s.margin) return FrameShape.Polaroid
    return FrameShape.entries.firstOrNull { it != FrameShape.Polaroid && it.ratio == s.ratio } ?: FrameShape.Original
}

/**
 * The frame once [shape] is picked over [this] one, or null for [FrameShape.Off]. The fill carries over, white for a
 * first frame, and the photo goes back to the middle at full size as [toSpec] opens the shape, so every pick lands
 * on the even border whatever a pinch or a drag did before it.
 */
internal fun FrameSpec?.withShape(shape: FrameShape): FrameSpec? =
    shape.toSpec(this?.fill ?: FrameFill.Solid(FRAME_WHITE))

/**
 * The borders the slider can set on [this] frame, as fractions of the photo's shorter side: from none to
 * [FRAME_MAX_BORDER], or for a Polaroid card from [FRAME_MIN_CARD_BORDER] to the widest its deeper bottom allows.
 */
internal fun FrameSpec.borderRange(): ClosedFloatingPointRange<Float> {
    val s = clamped()
    return if (s.bottomMargin > s.margin) {
        FRAME_MIN_CARD_BORDER..(FRAME_MAX_MARGIN / FRAME_POLAROID_BOTTOM_FACTOR)
    } else {
        0f..FRAME_MAX_BORDER
    }
}

/** The border [this] frame shows on the slider: its side margin, held to [borderRange]. */
internal fun FrameSpec.borderValue(): Float = clamped().margin.coerceIn(borderRange())

/**
 * [this] frame with its border set to [border], held to [borderRange]; a number that is not one reads as the
 * narrowest. A Polaroid keeps its deeper bottom in the same proportion, and the shape, the fill and the photo's size
 * and place are kept.
 */
internal fun FrameSpec.withBorder(border: Float): FrameSpec {
    val range = borderRange()
    val side = (if (border.isNaN()) range.start else border).coerceIn(range.start, range.endInclusive)
    val s = clamped()
    val card = s.bottomMargin > s.margin
    return copy(margin = side, bottomMargin = if (card) side * FRAME_POLAROID_BOTTOM_FACTOR else side)
}

/** [this] frame with [fill] around the photo, the shape and the photo's size and place kept. No frame stays none. */
internal fun FrameSpec?.withFill(fill: FrameFill): FrameSpec? = this?.copy(fill = fill)

/**
 * The fill swatches in order: the blur of the photo, then white, black and [palette]'s other colours as it lists
 * them, each once.
 */
internal fun frameFills(palette: List<Int>): List<FrameFill> {
    val colours = listOf(FRAME_WHITE, FRAME_BLACK) + palette.filter { it != FRAME_WHITE && it != FRAME_BLACK }
    return listOf<FrameFill>(FrameFill.Blur) + colours.distinct().map { FrameFill.Solid(it) }
}

/**
 * A rectangle on a frame's canvas, in canvas pixels. Plain Kotlin rather than [android.graphics.RectF] for the reason
 * [CropBox] gives.
 */
internal data class FrameRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A frame laid out: a [width] x [height] canvas with the whole photo drawn into [photo]. */
internal data class FrameCanvas(val width: Int, val height: Int, val photo: FrameRect)

/**
 * The canvas [spec] needs around a [photoW] x [photoH] photo, and where on it the photo goes.
 *
 * At full size the canvas is the smallest [FrameSpec.ratio]-shaped box that holds the photo and its margins. When
 * its long side is over [maxLongSide], the canvas and the photo on it scale down together, which keeps both shapes.
 * The photo keeps its own shape and never grows past its size at scale 1. The canvas rounds to whole pixels; where
 * that leaves it a sliver short, the photo gives way rather than leave the canvas.
 *
 * A photo side or a cap under one pixel counts as one, so every input lays out on a canvas of at least one pixel.
 */
internal fun frameCanvas(
    photoW: Int,
    photoH: Int,
    spec: FrameSpec,
    maxLongSide: Int = FRAME_MAX_LONG_SIDE,
): FrameCanvas {
    val s = spec.clamped()
    val room = frameRoom(photoW, photoH, s, maxLongSide)
    val w = room.fitW * s.scale
    val h = room.fitH * s.scale
    val left = placeAlong(room.left, room.width, w, s.offsetX, room.canvasW)
    val top = placeAlong(room.top, room.height, h, s.offsetY, room.canvasH)
    val right = min(left + w, room.canvasW.toFloat())
    val bottom = min(top + h, room.canvasH.toFloat())
    return FrameCanvas(room.canvasW, room.canvasH, FrameRect(left, top, right, bottom))
}

/**
 * [this] frame after a pinch by [zoom] and a drag of ([panX], [panY]) pixels on the canvas [frameCanvas] lays out
 * for the same photo size and cap.
 *
 * The zoom multiplies the scale, held to [FRAME_MIN_SCALE]..1, and the photo keeps its place in the room around it.
 * The drag then moves it by exactly those pixels, turned into an offset through the room left at the new scale and
 * stopped at the edges. An axis with no room keeps offset 0, so a drag there cannot stash a jump for a later shrink
 * to reveal. Measure the zoom and the drag from where the gesture began, as the crop pinch does, so an edge met
 * halfway does not stick. A zoom that is not a finite number counts as 1, and such a pan as 0.
 */
internal fun FrameSpec.afterGesture(
    zoom: Float,
    panX: Float,
    panY: Float,
    photoW: Int,
    photoH: Int,
    maxLongSide: Int = FRAME_MAX_LONG_SIDE,
): FrameSpec {
    val start = clamped()
    val scale = (start.scale * (if (zoom.isFinite()) zoom else 1f)).coerceIn(FRAME_MIN_SCALE, 1f)
    val room = frameRoom(photoW, photoH, start, maxLongSide)
    return start.copy(
        scale = scale,
        offsetX = offsetAfterPan(start.offsetX, panX, room.width - room.fitW * scale),
        offsetY = offsetAfterPan(start.offsetY, panY, room.height - room.fitH * scale),
    )
}

/**
 * [this] frame after a pinch by [zoom] and a drag of ([dragX], [dragY]) pixels on the editor's preview, which
 * draws the frame's canvas to fit a [boxW] x [boxH] box as ContentScale.Fit does. The drag reaches [afterGesture]
 * in pixels of the canvas [frameCanvas] lays out for the full-size [photoW] x [photoH] photo: the fit keeps the
 * canvas's shape, so one factor turns both axes, and since the preview is that same shape at whatever size it
 * rendered, the photo moves exactly as far as the finger until it meets an edge. A box side under one pixel
 * counts as one.
 */
internal fun FrameSpec.afterPreviewGesture(
    zoom: Float,
    dragX: Float,
    dragY: Float,
    photoW: Int,
    photoH: Int,
    boxW: Int,
    boxH: Int,
): FrameSpec {
    val canvas = frameCanvas(photoW, photoH, this)
    val canvasPerScreenPx = canvasPerPreviewPx(canvas.width, canvas.height, boxW, boxH)
    return afterGesture(zoom, dragX * canvasPerScreenPx, dragY * canvasPerScreenPx, photoW, photoH)
}

/**
 * How a point on a preview of the edit maps onto the full-size photo the frame step receives: across, the photo
 * pixel is (preview pixel - [offsetX]) * [scaleX], and down likewise. One preview pixel spans [scaleX] photo pixels,
 * which sizes a brush.
 */
internal data class PreviewToPhoto(val offsetX: Float, val offsetY: Float, val scaleX: Float, val scaleY: Float) {
    fun x(previewX: Float): Float = (previewX - offsetX) * scaleX
    fun y(previewY: Float): Float = (previewY - offsetY) * scaleY
}

/**
 * Where a point on a [previewW] x [previewH] preview lands on the full-size [photoW] x [photoH] photo. With a
 * [frame] the preview is the frame's canvas at whatever size it rendered, so the point goes through the canvas
 * [frameCanvas] lays out for the full-size photo and back off the photo's box on it; with none the preview is the
 * photo itself. A preview at the photo's own size with no frame maps every point to itself exactly. A side under one
 * pixel counts as one.
 */
internal fun previewToPhoto(previewW: Int, previewH: Int, photoW: Int, photoH: Int, frame: FrameSpec?): PreviewToPhoto {
    val pw = previewW.coerceAtLeast(1).toFloat()
    val ph = previewH.coerceAtLeast(1).toFloat()
    val w = photoW.coerceAtLeast(1)
    val h = photoH.coerceAtLeast(1)
    if (frame == null) return PreviewToPhoto(0f, 0f, w / pw, h / ph)
    val canvas = frameCanvas(w, h, frame)
    val canvasPerPreviewX = canvas.width / pw
    val canvasPerPreviewY = canvas.height / ph
    val box = canvas.photo
    return PreviewToPhoto(
        offsetX = box.left / canvasPerPreviewX,
        offsetY = box.top / canvasPerPreviewY,
        scaleX = canvasPerPreviewX * w / max(box.width, FRAME_MIN_ROOM_PX),
        scaleY = canvasPerPreviewY * h / max(box.height, FRAME_MIN_ROOM_PX),
    )
}

/**
 * A frame after [snappedToCentre], and whether the photo sits on the frame's middle across ([snappedX]) and down
 * ([snappedY]).
 */
internal data class FrameSnap(val spec: FrameSpec, val snappedX: Boolean, val snappedY: Boolean)

/**
 * [this] frame with the photo pulled onto the middle of the frame on each axis where its centre lies within
 * [thresholdPx] pixels of it on the editor's preview, which draws the canvas to fit a [boxW] x [boxH] box as
 * [afterPreviewGesture] reads it: the offset there becomes 0 and the other axis keeps its own. The middle is that of
 * the room inside the margins, which is the frame's own but for Polaroid's deeper bottom. An axis the photo has no
 * room to move on never snaps, since there is nothing to line up.
 */
internal fun FrameSpec.snappedToCentre(photoW: Int, photoH: Int, boxW: Int, boxH: Int, thresholdPx: Float): FrameSnap {
    val s = clamped()
    val room = frameRoom(photoW, photoH, s, FRAME_MAX_LONG_SIDE)
    val reach = thresholdPx * canvasPerPreviewPx(room.canvasW, room.canvasH, boxW, boxH)
    val x = nearCentre(s.offsetX, room.width - room.fitW * s.scale, reach)
    val y = nearCentre(s.offsetY, room.height - room.fitH * s.scale, reach)
    return FrameSnap(copy(offsetX = if (x) 0f else offsetX, offsetY = if (y) 0f else offsetY), x, y)
}

/**
 * Where the Frame tool's preview needs the system to hold back its edge gesture, in the preview's own pixels. A
 * side of the drawn [photo] that reaches into the strip that edge's back gesture claims, [leftInsetPx] or
 * [rightInsetPx] wide measured in from the preview's edges, gets a strip of its own, so a drag that starts on the
 * photo's edge moves the photo rather than going back. The strip runs down the middle of the photo for at most
 * [maxHeightPx], the most the platform honours per screen edge. A side clear of its strip, or a strip of no
 * width, asks for nothing, which keeps back reachable wherever the photo does not need the edge, as
 * [cropGestureExclusions] does for the crop handles.
 */
internal fun frameGestureExclusions(
    photo: FitBox,
    containerWidthPx: Float,
    leftInsetPx: Float,
    rightInsetPx: Float,
    maxHeightPx: Float,
): List<FitBox> {
    val height = min(photo.bottom - photo.top, maxHeightPx)
    if (height.isNaN() || height <= 0f) return emptyList()
    val middle = (photo.top + photo.bottom) / 2f
    val top = middle - height / 2f
    val bottom = middle + height / 2f
    val rects = ArrayList<FitBox>(2)
    if (leftInsetPx > 0f && photo.left < leftInsetPx) rects += FitBox(0f, top, leftInsetPx, bottom)
    if (rightInsetPx > 0f && photo.right > containerWidthPx - rightInsetPx) {
        rects += FitBox(containerWidthPx - rightInsetPx, top, containerWidthPx, bottom)
    }
    return rects
}

/**
 * The size of the small copy a blurred fill is made from, for a [pictureW] x [pictureH] picture scaled to cover a
 * [canvasW] x [canvasH] canvas: [FRAME_BLUR_CELLS] of its pixels along the canvas's long side. It follows the shapes
 * rather than the pixel counts, so the fast preview and the full-res save of one frame get the same size, within a
 * pixel of rounding, and so the same blur, and a picture that runs past a canvas of another shape blurs no harder
 * than one that fits it. Each side is held to 1..[FRAME_BLUR_MAX_SIDE].
 */
internal fun frameBlurSize(pictureW: Int, pictureH: Int, canvasW: Int, canvasH: Int): Pair<Int, Int> {
    val pw = pictureW.coerceAtLeast(1).toFloat()
    val ph = pictureH.coerceAtLeast(1).toFloat()
    val cw = canvasW.coerceAtLeast(1).toFloat()
    val ch = canvasH.coerceAtLeast(1).toFloat()
    val k = FRAME_BLUR_CELLS * max(cw / pw, ch / ph) / max(cw, ch)
    val w = pw * k
    val h = ph * k
    val fit = min(1f, FRAME_BLUR_MAX_SIDE / max(w, h))
    return (w * fit).roundToInt().coerceIn(1, FRAME_BLUR_MAX_SIDE) to
        (h * fit).roundToInt().coerceIn(1, FRAME_BLUR_MAX_SIDE)
}

/**
 * [pixels], a [width] x [height] ARGB image, after [passes] box blurs of [radius] pixels, each across and then
 * down, every channel on its own. A pixel past an edge reads as the edge pixel, so the border keeps its colour
 * rather than fading. [pixels] itself is left as it is; with no radius, no passes or too few pixels for the size,
 * the answer is a plain copy.
 */
internal fun softenArgb(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
    val out = pixels.copyOf()
    if (width <= 0 || height <= 0 || radius <= 0 || passes <= 0 || pixels.size < width * height) return out
    val across = IntArray(out.size)
    repeat(passes) {
        boxBlur(out, across, lines = height, length = width, lineStep = width, step = 1, radius = radius)
        boxBlur(across, out, lines = width, length = height, lineStep = 1, step = width, radius = radius)
    }
    return out
}

/** [this] spec with every number in range; see [FrameSpec]. */
private fun FrameSpec.clamped(): FrameSpec = copy(
    scale = scale.inRangeOr(1f, FRAME_MIN_SCALE, 1f),
    offsetX = offsetX.inRangeOr(0f, -1f, 1f),
    offsetY = offsetY.inRangeOr(0f, -1f, 1f),
    margin = margin.inRangeOr(0f, 0f, FRAME_MAX_MARGIN),
    bottomMargin = bottomMargin.inRangeOr(0f, 0f, FRAME_MAX_MARGIN),
)

private fun Float.inRangeOr(fallback: Float, low: Float, high: Float): Float =
    if (isNaN()) fallback else coerceIn(low, high)

/**
 * One frame measured for one photo, in canvas pixels: the canvas, the room inside its margins, and the photo at
 * scale 1.
 */
private class FrameRoom(
    val canvasW: Int,
    val canvasH: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val fitW: Float,
    val fitH: Float,
)

/** Measures a [spec] that is already clamped; see [frameCanvas]. */
private fun frameRoom(photoW: Int, photoH: Int, spec: FrameSpec, maxLongSide: Int): FrameRoom {
    val w = photoW.coerceAtLeast(1).toFloat()
    val h = photoH.coerceAtLeast(1).toFloat()
    val side = spec.margin * min(w, h)
    val below = spec.bottomMargin * min(w, h)
    val cardW = w + 2f * side
    val cardH = h + side + below
    val ratio = spec.ratio.ratio ?: (cardW / cardH)
    val fullW = max(cardW, cardH * ratio)
    val fullH = max(cardH, cardW / ratio)
    val cap = maxLongSide.coerceAtLeast(1)
    val k = min(1f, cap / max(fullW, fullH))
    val canvasW = (fullW * k).roundToInt().coerceIn(1, cap)
    val canvasH = (fullH * k).roundToInt().coerceIn(1, cap)
    // Rounding down can leave the canvas a sliver short of the photo; only then does the photo shrink.
    val fit = min(1f, min(canvasW / (w * k), canvasH / (h * k)))
    return FrameRoom(
        canvasW = canvasW,
        canvasH = canvasH,
        left = side * k,
        top = side * k,
        width = canvasW - 2f * side * k,
        height = canvasH - (side + below) * k,
        fitW = w * k * fit,
        fitH = h * k * fit,
    )
}

/**
 * Canvas pixels under one pixel of a preview that fits a [canvasW] x [canvasH] canvas into a [boxW] x [boxH] box as
 * ContentScale.Fit does: the fit keeps the canvas's shape, so one factor serves both axes. A box side under one pixel
 * counts as one.
 */
private fun canvasPerPreviewPx(canvasW: Int, canvasH: Int, boxW: Int, boxH: Int): Float =
    max(canvasW.toFloat() / boxW.coerceAtLeast(1), canvasH.toFloat() / boxH.coerceAtLeast(1))

/**
 * Where a photo [size] long starts along one axis: placed by [offset] in the room it leaves, or centred on the room
 * when rounding leaves none, then held on the [canvas].
 */
private fun placeAlong(roomStart: Float, roomSize: Float, size: Float, offset: Float, canvas: Int): Float {
    val free = roomSize - size
    val start = roomStart + free / 2f * (1f + if (free > 0f) offset else 0f)
    return start.coerceIn(0f, (canvas - size).coerceAtLeast(0f))
}

/** [offset] after a [pan] of pixels across [room] pixels of room, which the offset spans from -1 to 1. */
private fun offsetAfterPan(offset: Float, pan: Float, room: Float): Float {
    if (room < FRAME_MIN_ROOM_PX) return 0f
    val moved = offset + 2f * (if (pan.isFinite()) pan else 0f) / room
    return moved.coerceIn(-1f, 1f)
}

/**
 * Whether a photo placed at [offset] in [room] pixels of room has its centre within [reach] pixels of the room's
 * middle, the offset spanning the room from -1 to 1 as in [offsetAfterPan]. No room means nothing to line up.
 */
private fun nearCentre(offset: Float, room: Float, reach: Float): Boolean =
    room >= FRAME_MIN_ROOM_PX && abs(offset) * room / 2f <= reach

/**
 * One box blur of [radius] from [src] into [dst] along [lines] lines of [length] pixels: line i starts at i times
 * [lineStep], and its pixels lie [step] apart. Each output pixel is the rounded mean of the window around it, carried
 * along the line as a running sum.
 */
private fun boxBlur(src: IntArray, dst: IntArray, lines: Int, length: Int, lineStep: Int, step: Int, radius: Int) {
    val span = 2 * radius + 1
    val half = span / 2
    val last = length - 1
    for (line in 0 until lines) {
        val base = line * lineStep
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[base + i.coerceIn(0, last) * step]
            a += p ushr 24
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (i in 0..last) {
            dst[base + i * step] = ((a + half) / span shl 24) or ((r + half) / span shl 16) or
                ((g + half) / span shl 8) or ((b + half) / span)
            val leaving = src[base + (i - radius).coerceIn(0, last) * step]
            val entering = src[base + (i + radius + 1).coerceIn(0, last) * step]
            a += (entering ushr 24) - (leaving ushr 24)
            r += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            g += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
