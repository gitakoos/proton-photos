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
 * The DB post-processing and the geometry helpers below are derived from mobile_ocr, which is MIT
 * licensed:
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The detector's own output: for every cell of the network's input grid, how strongly it believes
 * that cell is part of a word. Everything downstream of the model run works from this and nothing
 * else, which is what keeps the whole post-processing chain testable without a runtime.
 */
class ProbabilityMap(val width: Int, val height: Int, private val values: FloatArray) {

    init {
        require(values.size >= width * height) { "probability map is smaller than its own bounds" }
    }

    fun at(x: Int, y: Int): Float = values[y * width + x]
}

/** One region the detector found, with the mean probability over its own area as the confidence. */
data class DetectedQuad(val quad: TextQuad, val score: Float)

/** Probability above which a cell counts as part of a word. */
private const val BINARY_THRESHOLD = 0.3f

/** Mean probability a region has to reach to be reported at all. */
private const val BOX_THRESHOLD = 0.6f

/**
 * How far a region is grown before it is reported, as a fraction of area over perimeter. The
 * binarisation cuts where a stroke fades out, so the raw region sits inside the ink and the report has
 * to grow back out to cover the strokes the threshold ate. A little above the reference default so a
 * bold glyph on a product label keeps its outer strokes: a first "K" whose diagonals are clipped reads
 * as a narrower letter, and the extra margin is what keeps the letter whole for the reader.
 */
private const val UNCLIP_RATIO = 1.8f

/** Shortest side, in grid cells, a grown region has to keep. */
private const val MIN_SIDE = 3f

/** Ceiling on how many regions are examined, largest first. */
private const val MAX_COMPONENTS = 1000

private const val EPSILON = 1e-6f

/**
 * How far two runs' tops may sit apart and still be ordered as one line, as a fraction of the shorter
 * run's own height. Stated as a fraction because the quads are already in image pixels by this point:
 * a fixed allowance that suits a phone screenshot splits a single line of a 4000 pixel photograph
 * across two rows and hands back the words in the wrong order.
 */
private const val SAME_ROW_SLACK_RATIO = 0.5f

/**
 * Turns a [map] of per-cell probabilities into oriented quadrilaterals in the pixel coordinates of
 * an [imageWidth] by [imageHeight] frame.
 *
 * The chain is the DB algorithm's: threshold the map, take each connected blob, score the blob's own
 * outline against the probabilities underneath it, wrap the survivors in their smallest rotated
 * rectangle, grow that back out to cover the strokes the threshold ate, and only then scale into
 * image pixels. Scaling last matters: every threshold and minimum above is stated in grid cells, so a
 * 12 MP photo and a 2 MP photo are judged identically.
 *
 * Scoring the outline rather than the rectangle around it is the difference between the two ways DB
 * can be run. The rectangle is cheaper and is the reference implementation's default, but a rectangle
 * fitted to a slanted or ragged run also covers the background in its corners, and that background
 * drags the mean down until a perfectly good run falls under [BOX_THRESHOLD]. The outline covers what
 * was actually found, so the score answers the question it is being asked.
 */
fun detectQuads(map: ProbabilityMap, imageWidth: Int, imageHeight: Int): List<DetectedQuad> {
    if (map.width <= 0 || map.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return emptyList()

    val components = connectedComponents(map)
        .sortedByDescending { it.size }
        .take(MAX_COMPONENTS)
    val scaleX = imageWidth.toFloat() / map.width
    val scaleY = imageHeight.toFloat() / map.height

    val found = mutableListOf<DetectedQuad>()
    for (component in components) {
        if (component.size < 4) continue

        val hull = convexHull(hullCandidates(component, map.width))
        if (hull.size < 3) continue

        val score = regionScore(map, hull)
        if (score < BOX_THRESHOLD) continue

        val rect = minimumAreaRectangle(hull, alreadyConvex = true)
        if (rect.isEmpty()) continue

        val grown = unclip(rect, UNCLIP_RATIO)
        if (grown.isEmpty()) continue

        val grownRect = minimumAreaRectangle(grown, alreadyConvex = false)
        if (grownRect.isEmpty()) continue
        if (shortestSide(grownRect) < MIN_SIDE) continue

        val clipped = clipToBounds(grownRect, map.width, map.height)
        val scaled = clipped.map { FitPoint(it.x * scaleX, it.y * scaleY) }
        found += DetectedQuad(quadOf(orderClockwise(scaled)), score)
    }
    return sortReadingOrder(found)
}

/** Reading order: top to bottom, and left to right within a line. */
fun sortReadingOrder(quads: List<DetectedQuad>): List<DetectedQuad> {
    if (quads.size < 2) return quads
    val byTop = quads.sortedBy { it.quad.bounds.top }
    val ordered = mutableListOf<DetectedQuad>()
    var index = 0
    while (index < byTop.size) {
        val first = byTop[index].quad
        var end = index + 1
        while (end < byTop.size && onSameRow(first, byTop[end].quad)) end++
        ordered += byTop.subList(index, end).sortedBy { it.quad.bounds.left }
        index = end
    }
    return ordered
}

/**
 * Whether [other] belongs on the same line as [first] when the page is put into reading order.
 *
 * The tops of two runs on one line never agree exactly: one starts with a capital and the next with a
 * lower-case letter, and each was grown outwards by its own size. What they do share is a scale, so
 * the allowance is [SAME_ROW_SLACK_RATIO] of the shorter run's height rather than a fixed distance.
 */
internal fun onSameRow(first: TextQuad, other: TextQuad): Boolean {
    val shorter = min(
        first.bounds.bottom - first.bounds.top,
        other.bounds.bottom - other.bounds.top,
    )
    return abs(other.bounds.top - first.bounds.top) <= max(shorter, 1f) * SAME_ROW_SLACK_RATIO
}

/** Four ordered corners as a quad; anything else collapses to its own upright extent. */
private fun quadOf(corners: List<FitPoint>): TextQuad = if (corners.size == 4) {
    TextQuad(corners[0], corners[1], corners[2], corners[3])
} else {
    TextQuad.upright(
        left = corners.minOf { it.x }, top = corners.minOf { it.y },
        right = corners.maxOf { it.x }, bottom = corners.maxOf { it.y },
    )
}

/**
 * The only cells of [component] that can carry its outline: the leftmost and rightmost cell of every
 * row it covers, in a grid [width] cells across.
 *
 * Lossless rather than an approximation. A cell that is neither the first nor the last of its own row
 * lies on the straight line between two cells that are, so it is already inside the hull and can
 * never be a corner of it. Dropping the rest turns the hull's input from the blob's whole area into
 * twice its height, which is the difference between a few points and a few thousand for a single line
 * of text, and the count of those points is what the detector's post-processing spends its time on
 * once the frame is read at any real resolution.
 */
internal fun hullCandidates(component: IntArray, width: Int): List<FitPoint> {
    if (component.isEmpty() || width <= 0) return emptyList()
    var minRow = Int.MAX_VALUE
    var maxRow = Int.MIN_VALUE
    for (index in component) {
        val row = index / width
        if (row < minRow) minRow = row
        if (row > maxRow) maxRow = row
    }
    val rows = maxRow - minRow + 1
    val leftmost = IntArray(rows) { Int.MAX_VALUE }
    val rightmost = IntArray(rows) { Int.MIN_VALUE }
    for (index in component) {
        val row = index / width - minRow
        val column = index % width
        if (column < leftmost[row]) leftmost[row] = column
        if (column > rightmost[row]) rightmost[row] = column
    }
    val points = ArrayList<FitPoint>(rows * 2)
    for (row in 0 until rows) {
        if (rightmost[row] < leftmost[row]) continue
        val y = (row + minRow).toFloat()
        points += FitPoint(leftmost[row].toFloat(), y)
        if (rightmost[row] != leftmost[row]) points += FitPoint(rightmost[row].toFloat(), y)
    }
    return points
}

/**
 * Every 8-connected blob of above-threshold cells, each as the flat indices of its own cells.
 *
 * Indices rather than points on purpose: a text-heavy frame lights up a six-figure number of cells,
 * and holding those as objects costs several megabytes at exactly the moment the viewer is already
 * carrying a full-resolution bitmap. A blob becomes points only while its own hull is computed.
 */
internal fun connectedComponents(map: ProbabilityMap): List<IntArray> {
    val width = map.width
    val height = map.height
    val visited = BooleanArray(width * height)
    val components = mutableListOf<IntArray>()
    val stack = IntBuffer()
    val blob = IntBuffer()

    for (y in 0 until height) {
        for (x in 0 until width) {
            val start = y * width + x
            if (visited[start] || map.at(x, y) <= BINARY_THRESHOLD) continue

            stack.clear()
            blob.clear()
            stack.push(start)
            visited[start] = true
            while (stack.isNotEmpty) {
                val index = stack.pop()
                blob.push(index)
                val cx = index % width
                val cy = index / width
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        if (nx < 0 || nx >= width) continue
                        val neighbour = ny * width + nx
                        if (visited[neighbour] || map.at(nx, ny) <= BINARY_THRESHOLD) continue
                        visited[neighbour] = true
                        stack.push(neighbour)
                    }
                }
            }
            components += blob.toIntArray()
        }
    }
    return components
}

/** A growable int stack, so the flood fill neither boxes its coordinates nor churns arrays. */
private class IntBuffer(initialCapacity: Int = 256) {

    private var data = IntArray(initialCapacity)
    private var count = 0

    val isNotEmpty: Boolean get() = count > 0

    fun push(value: Int) {
        if (count == data.size) data = data.copyOf(count * 2)
        data[count++] = value
    }

    fun pop(): Int = data[--count]

    fun clear() {
        count = 0
    }

    fun toIntArray(): IntArray = data.copyOf(count)
}

/**
 * Mean probability over the cells [polygon] covers, which is how confident the region is.
 *
 * [polygon] has to be convex, which both of the shapes this is asked about are. The edges are read
 * out into flat arrays first rather than walked as points: this is the one place in the chain whose
 * cost is the region's whole area times its number of edges, and an outline has far more edges than
 * the four a rectangle has.
 */
internal fun regionScore(map: ProbabilityMap, polygon: List<FitPoint>): Float {
    if (polygon.size < 3) return 0f
    val minX = floor(polygon.minOf { it.x.toDouble() }).toInt().coerceIn(0, map.width - 1)
    val maxX = ceil(polygon.maxOf { it.x.toDouble() }).toInt().coerceIn(0, map.width - 1)
    val minY = floor(polygon.minOf { it.y.toDouble() }).toInt().coerceIn(0, map.height - 1)
    val maxY = ceil(polygon.maxOf { it.y.toDouble() }).toInt().coerceIn(0, map.height - 1)
    if (maxX < minX || maxY < minY) return 0f

    val edges = polygon.size
    val originX = FloatArray(edges)
    val originY = FloatArray(edges)
    val alongX = FloatArray(edges)
    val alongY = FloatArray(edges)
    for (i in 0 until edges) {
        val a = polygon[i]
        val b = polygon[if (i + 1 == edges) 0 else i + 1]
        originX[i] = a.x
        originY[i] = a.y
        alongX[i] = b.x - a.x
        alongY[i] = b.y - a.y
    }

    var sum = 0f
    var count = 0
    for (y in minY..maxY) {
        val cellY = y + 0.5f
        for (x in minX..maxX) {
            val cellX = x + 0.5f
            var positive = false
            var negative = false
            var inside = true
            for (i in 0 until edges) {
                val cross = alongX[i] * (cellY - originY[i]) - alongY[i] * (cellX - originX[i])
                if (cross > 0f) positive = true else if (cross < 0f) negative = true
                if (positive && negative) {
                    inside = false
                    break
                }
            }
            if (inside) {
                sum += map.at(x, y)
                count++
            }
        }
    }
    return if (count > 0) sum / count else 0f
}

/** True when ([x], [y]) is inside convex [polygon], edges counted as inside. */
internal fun polygonContainsPoint(polygon: List<FitPoint>, x: Float, y: Float): Boolean {
    if (polygon.size < 3) return false
    var positive = false
    var negative = false
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
        if (cross > 0f) positive = true else if (cross < 0f) negative = true
        if (positive && negative) return false
    }
    return true
}

/** Monotone-chain convex hull; collinear points are dropped. */
internal fun convexHull(points: List<FitPoint>): List<FitPoint> {
    if (points.size < 3) return points
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    val lower = mutableListOf<FitPoint>()
    val upper = mutableListOf<FitPoint>()
    for (point in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0f) {
            lower.removeAt(lower.lastIndex)
        }
        lower.add(point)
    }
    for (point in sorted.reversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0f) {
            upper.removeAt(upper.lastIndex)
        }
        upper.add(point)
    }
    if (lower.isEmpty() || upper.isEmpty()) return emptyList()
    lower.removeAt(lower.lastIndex)
    upper.removeAt(upper.lastIndex)
    return lower + upper
}

private fun cross(origin: FitPoint, a: FitPoint, b: FitPoint): Float =
    (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)

/**
 * The smallest-area rectangle enclosing [points], as four corners. Rotating calipers over the hull:
 * the answer always has one side flush with a hull edge, so every edge is tried in turn.
 */
internal fun minimumAreaRectangle(points: List<FitPoint>, alreadyConvex: Boolean = false): List<FitPoint> {
    val hull = if (alreadyConvex) points else convexHull(points)
    if (hull.size < 3) return emptyList()

    var best: List<FitPoint> = emptyList()
    var smallest = Float.MAX_VALUE

    for (i in hull.indices) {
        val origin = hull[i]
        val along = unitVector(origin, hull[(i + 1) % hull.size]) ?: continue
        val across = FitPoint(-along.y, along.x)

        var minAlong = Float.MAX_VALUE
        var maxAlong = -Float.MAX_VALUE
        var minAcross = Float.MAX_VALUE
        var maxAcross = -Float.MAX_VALUE
        for (point in hull) {
            val dx = point.x - origin.x
            val dy = point.y - origin.y
            val projected = dx * along.x + dy * along.y
            val offset = dx * across.x + dy * across.y
            if (projected < minAlong) minAlong = projected
            if (projected > maxAlong) maxAlong = projected
            if (offset < minAcross) minAcross = offset
            if (offset > maxAcross) maxAcross = offset
        }

        val width = maxAlong - minAlong
        val height = maxAcross - minAcross
        val area = width * height
        if (area < smallest && width > 1e-3f && height > 1e-3f) {
            smallest = area
            fun corner(a: Float, b: Float) = FitPoint(
                origin.x + along.x * a + across.x * b,
                origin.y + along.y * a + across.y * b,
            )
            best = listOf(
                corner(minAlong, minAcross),
                corner(maxAlong, minAcross),
                corner(maxAlong, maxAcross),
                corner(minAlong, maxAcross),
            )
        }
    }
    return if (best.isEmpty()) axisAlignedBounds(hull) else best
}

private fun axisAlignedBounds(points: List<FitPoint>): List<FitPoint> {
    if (points.isEmpty()) return emptyList()
    val left = points.minOf { it.x }
    val right = points.maxOf { it.x }
    val top = points.minOf { it.y }
    val bottom = points.maxOf { it.y }
    return listOf(
        FitPoint(left, top),
        FitPoint(right, top),
        FitPoint(right, bottom),
        FitPoint(left, bottom),
    )
}

/**
 * Grows [polygon] outwards by area over perimeter times [ratio]. The binarisation cuts at the point
 * where a stroke fades out, so the raw region sits inside the glyphs; without this the reported box
 * clips the tops and tails of the letters it found.
 */
internal fun unclip(polygon: List<FitPoint>, ratio: Float): List<FitPoint> {
    if (polygon.size < 3) return emptyList()
    val perimeter = perimeter(polygon)
    if (perimeter <= EPSILON) return emptyList()
    val offset = abs(signedArea(polygon)) * ratio / perimeter
    if (offset <= EPSILON) return polygon
    val expanded = offsetPolygon(polygon, offset)
    return if (expanded.size >= 3) expanded else emptyList()
}

/** Moves every edge of [polygon] outwards by [offset] and re-intersects the neighbouring edges. */
private fun offsetPolygon(polygon: List<FitPoint>, offset: Float): List<FitPoint> {
    val count = polygon.size
    if (count < 3) return emptyList()
    val counterClockwise = signedArea(polygon) > 0f
    val result = ArrayList<FitPoint>(count)

    for (i in 0 until count) {
        val previous = polygon[(i - 1 + count) % count]
        val current = polygon[i]
        val next = polygon[(i + 1) % count]

        val incoming = normalize(FitPoint(current.x - previous.x, current.y - previous.y)) ?: continue
        val outgoing = normalize(FitPoint(next.x - current.x, next.y - current.y)) ?: continue

        val normalIn = outwardNormal(incoming, counterClockwise)
        val normalOut = outwardNormal(outgoing, counterClockwise)
        val shiftedIn = FitPoint(current.x + normalIn.x * offset, current.y + normalIn.y * offset)
        val shiftedOut = FitPoint(current.x + normalOut.x * offset, current.y + normalOut.y * offset)

        result += intersect(shiftedIn, incoming, shiftedOut, outgoing) ?: current
    }
    return result
}

private fun outwardNormal(direction: FitPoint, counterClockwise: Boolean): FitPoint =
    if (counterClockwise) FitPoint(direction.y, -direction.x) else FitPoint(-direction.y, direction.x)

private fun intersect(point: FitPoint, direction: FitPoint, other: FitPoint, otherDirection: FitPoint): FitPoint? {
    val denominator = direction.x * otherDirection.y - direction.y * otherDirection.x
    if (abs(denominator) < EPSILON) return null
    val dx = other.x - point.x
    val dy = other.y - point.y
    val t = (dx * otherDirection.y - dy * otherDirection.x) / denominator
    return FitPoint(point.x + direction.x * t, point.y + direction.y * t)
}

/** Holds [points] inside a [width] by [height] grid, edges included. */
internal fun clipToBounds(points: List<FitPoint>, width: Int, height: Int): List<FitPoint> = points.map {
    FitPoint(it.x.coerceIn(0f, width - 1f), it.y.coerceIn(0f, height - 1f))
}

/**
 * Reorders four corners clockwise starting at the top left, which is the order a quad's own corners
 * are declared in. Sorting by angle about the centroid handles a rectangle at any rotation; the
 * corner with the smallest coordinate sum is the one closest to the frame's origin.
 */
internal fun orderClockwise(points: List<FitPoint>): List<FitPoint> {
    if (points.size != 4) return points
    val centreX = points.sumOf { it.x.toDouble() }.toFloat() / 4f
    val centreY = points.sumOf { it.y.toDouble() }.toFloat() / 4f
    val byAngle = points.sortedBy { atan2((it.y - centreY).toDouble(), (it.x - centreX).toDouble()) }
    var first = 0
    var smallestSum = byAngle[0].x + byAngle[0].y
    for (i in 1 until 4) {
        val sum = byAngle[i].x + byAngle[i].y
        if (sum < smallestSum) {
            smallestSum = sum
            first = i
        }
    }
    return List(4) { byAngle[(first + it) % 4] }
}

internal fun shortestSide(polygon: List<FitPoint>): Float {
    if (polygon.size < 2) return 0f
    var shortest = Float.MAX_VALUE
    for (i in polygon.indices) {
        val length = distance(polygon[i], polygon[(i + 1) % polygon.size])
        if (length < shortest) shortest = length
    }
    return if (shortest == Float.MAX_VALUE) 0f else shortest
}

private fun signedArea(points: List<FitPoint>): Float {
    var area = 0f
    for (i in points.indices) {
        val j = (i + 1) % points.size
        area += points[i].x * points[j].y - points[j].x * points[i].y
    }
    return area / 2f
}

private fun perimeter(points: List<FitPoint>): Float {
    var total = 0f
    for (i in points.indices) total += distance(points[i], points[(i + 1) % points.size])
    return total
}

private fun distance(a: FitPoint, b: FitPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}

private fun unitVector(from: FitPoint, to: FitPoint): FitPoint? =
    normalize(FitPoint(to.x - from.x, to.y - from.y))

private fun normalize(vector: FitPoint): FitPoint? {
    val length = sqrt(vector.x * vector.x + vector.y * vector.y)
    if (length < EPSILON) return null
    return FitPoint(vector.x / length, vector.y / length)
}

/**
 * The network reads a fixed-stride grid, so the frame is resized to a multiple of [STRIDE] with its
 * longest side no more than [limitSide], and never below one stride in either direction.
 */
fun detectorInputSize(width: Int, height: Int, limitSide: Int = LIMIT_SIDE_MIN): Pair<Int, Int> {
    val longest = max(width, height)
    val ratio = if (longest > limitSide) limitSide.toFloat() / longest else 1f
    val scaledWidth = max(1, (width * ratio).roundToInt())
    val scaledHeight = max(1, (height * ratio).roundToInt())
    return roundUpToStride(scaledWidth) to roundUpToStride(scaledHeight)
}

/**
 * The longest side a [width] by [height] frame is reduced to before the detector looks at it, on a
 * process the runtime will let grow to [maxHeapBytes].
 *
 * This one number decides what can be found at all. The frame is squashed to it before anything is
 * looked at, so a caption 12 pixels tall in a 4000 pixel photograph arrives 3 pixels tall at
 * [LIMIT_SIDE_MIN] and is not so much missed as never seen. What it buys is paid for twice over:
 * the work and the memory both follow the pixel count, so going from 960 to 1600 is not two thirds
 * more of either but nearly three times as much.
 *
 * Three rules decide it, and the frame's own size is the first. There is nothing behind an upscale,
 * so a frame smaller than the ceiling sets its own limit and is never enlarged into detail it does
 * not have; a small photograph therefore costs no more than it does today. The second is the heap:
 * the detector may claim a [DETECTION_HEAP_FRACTION] of what the process is allowed, which on a
 * phone with half a gigabyte to give carries a 12 megapixel photo to about 1700 and on a constrained
 * one falls back to [LIMIT_SIDE_MIN] and behaves exactly as it did before. The third is
 * [LIMIT_SIDE_MAX], which is about the wait rather than the memory: past it the user is holding a
 * finger on a photograph for longer than the answer is worth.
 *
 * Deliberately not decided by what a first pass found. Measuring the text and going round again for
 * the small cases is the obvious refinement and it cannot work here, because the first pass's own
 * blindness is the thing being fixed: a page whose body text was never seen at 960 looks, from its
 * one detected heading, like a page that needs nothing.
 */
fun detectionLimitSide(width: Int, height: Int, maxHeapBytes: Long): Int {
    val longest = max(width, height)
    val shortest = min(width, height)
    if (longest <= 0 || shortest <= 0 || maxHeapBytes <= 0L) return LIMIT_SIDE_MIN
    val affordablePixels = maxHeapBytes / DETECTION_HEAP_FRACTION / DETECTION_BYTES_PER_PIXEL
    // Pixels at a given longest side depend on the frame's proportions, so the limit is recovered
    // from the budget through the frame's own aspect rather than through a square.
    val affordableSide = sqrt(affordablePixels.toDouble() * longest / shortest)
    val limit = min(affordableSide, longest.toDouble())
    return limit.toInt().coerceIn(LIMIT_SIDE_MIN, LIMIT_SIDE_MAX)
}

private fun roundUpToStride(value: Int): Int = max(((value + STRIDE - 1) / STRIDE) * STRIDE, STRIDE)

/**
 * Smallest longest-side the detector ever works at, and the reference implementation's own default.
 * A floor rather than a target: a device too small to afford more reads exactly as well as it did
 * before, and never worse.
 */
const val LIMIT_SIDE_MIN = 960

/**
 * Largest longest-side the detector works at. Four and a half times the work of [LIMIT_SIDE_MIN],
 * which is where the wait rather than the memory becomes the thing that bounds this, and where a
 * device generous enough to afford more should stop being given it.
 */
const val LIMIT_SIDE_MAX = 2048

/**
 * Heap the detector holds for each pixel of its own input, near enough.
 *
 * The frame arrives as three float planes, twelve bytes a pixel, and the runtime keeps a copy of
 * them for as long as the run lasts. The probability map comes back as a float a pixel and is copied
 * once more so the post-processing can work without the tensor. The network's own working buffers
 * come to about as much again. Counted against the input grid and not the photograph, because the
 * frame is scaled down to that grid before a byte of this is allocated.
 */
private const val DETECTION_BYTES_PER_PIXEL = 48

/**
 * How much of the process heap that allocation may claim, as a divisor. A fifth leaves the viewer's
 * own decoded pages, its cached neighbours and the gallery behind it the rest, and all of them are
 * still being held while this runs.
 */
private const val DETECTION_HEAP_FRACTION = 5

/** The network's own downsampling factor; its input has to be a whole number of these. */
const val STRIDE = 32
