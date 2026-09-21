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

import eu.akoos.photos.util.FitPoint
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Everything a page of readings goes through between the models and the screen: level out the runs
 * that only look tilted, fuse the readings that landed on top of each other into one run, and keep
 * the order the words were read in.
 *
 * Deliberately after recognition rather than after detection. Both of the decisions here need what
 * only the reader knows: which characters each of two overlapping regions produced, and how sure it
 * was of them.
 */
fun cleanTextBlocks(blocks: List<RecognizedTextBlock>): List<RecognizedTextBlock> =
    fusedRuns(blocks.map { it.copy(quad = deskewed(it.quad)) })

// ─── laying flat what only looks tilted ─────────────────────────────────────

/**
 * The angle the text in [quad] sits at, in degrees from horizontal, and always the acute one: a run
 * that reads right to left across an upside-down photo is as level as one that reads left to right,
 * and neither of them is tilted.
 */
fun quadTiltDegrees(quad: TextQuad): Float {
    // Both long edges, so one corner landing badly counts for half as much as it would alone.
    val dx = (quad.topRight.x - quad.topLeft.x) + (quad.bottomRight.x - quad.bottomLeft.x)
    val dy = (quad.topRight.y - quad.topLeft.y) + (quad.bottomRight.y - quad.bottomLeft.y)
    if (abs(dx) < EPSILON && abs(dy) < EPSILON) return 0f
    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val folded = ((degrees % 180f) + 180f) % 180f
    return if (folded > 90f) 180f - folded else folded
}

/**
 * The tilt a run whose box is [lengthOverHeight] times as long as it is tall may carry before the
 * angle counts as the photograph's own, in degrees.
 *
 * What a fitted rectangle gets wrong is a distance, not an angle: the smallest rectangle around a
 * ragged blob is pulled at one end by whatever the outline does there, an ascender, a comma below
 * the baseline, a stroke that faded out early, and the two ends end up disagreeing by about
 * [FIT_WOBBLE] of the run's own height. The angle that one distance subtends is arctan of it over
 * the run's length, so the same wobble that turns a full line by three degrees turns four characters
 * by fifteen. An allowance that scales with the proportions holds a long line to a fraction of a
 * degree and still lays a short button label flat, where a single figure can only ever suit one of
 * them.
 *
 * Capped at [MAX_DESKEW_DEGREES], because a short enough run would otherwise be allowed almost any
 * angle at all and a one-word sign photographed at a slant has to keep its own.
 */
fun deskewAllowanceDegrees(lengthOverHeight: Float): Float {
    if (lengthOverHeight <= EPSILON) return MAX_DESKEW_DEGREES
    val fromWobble = Math.toDegrees(atan((FIT_WOBBLE / lengthOverHeight).toDouble())).toFloat()
    return min(MAX_DESKEW_DEGREES, fromWobble)
}

/**
 * [quad] with a tilt it only appears to have taken out of it.
 *
 * Anything inside the run's own [deskewAllowanceDegrees] is laid flat about the quad's centre,
 * keeping the run's measured length and height rather than expanding to its upright extent, which
 * for a long line would be half as tall again.
 *
 * A larger angle is the photograph's and survives untouched. A sign shot at a slant, a page held
 * askew and a vertical caption genuinely are at an angle, and squaring those up would swallow the
 * lines on either side.
 */
fun deskewed(quad: TextQuad): TextQuad {
    val length = quadLength(quad)
    val height = quadHeight(quad)
    if (height <= EPSILON) return quad
    if (quadTiltDegrees(quad) > deskewAllowanceDegrees(length / height)) return quad
    val centreX = quad.corners.sumOf { it.x.toDouble() }.toFloat() / 4f
    val centreY = quad.corners.sumOf { it.y.toDouble() }.toFloat() / 4f
    return TextQuad.upright(
        left = centreX - length / 2f,
        top = centreY - height / 2f,
        right = centreX + length / 2f,
        bottom = centreY + height / 2f,
    )
}

// ─── one run, one highlight ─────────────────────────────────────────────────

/**
 * [blocks] with the readings that landed on top of each other fused into one run each.
 *
 * A run of text can reach the reader more than once: the threshold that turns the detector's map
 * into blobs can split one heading in two, or leave a narrow sliver of it standing on its own, and
 * every piece is then grown back out until it covers characters another piece also read. What lands
 * on screen is two outlines over one heading, usually at slightly different angles, with the words
 * drawn twice on top of each other.
 *
 * They are fused rather than one of them being dropped, because each reading carries its own
 * characters and dropping one takes with it whatever the other never read, out of a page the user is
 * there to select and copy. Which readings belong together is [sameRun], and the groups are the
 * connected sets of that, so three pieces that only overlap in pairs still come out as one run and
 * the answer does not depend on the order the page arrived in. Each group keeps the place of its
 * earliest member, so a page comes out the same way twice.
 */
fun fusedRuns(blocks: List<RecognizedTextBlock>): List<RecognizedTextBlock> {
    if (blocks.size < 2) return blocks
    val group = IntArray(blocks.size) { it }
    fun root(index: Int): Int {
        var current = index
        while (group[current] != current) current = group[current]
        return current
    }
    for (i in blocks.indices) {
        for (j in i + 1 until blocks.size) {
            if (!sameRun(blocks[i].quad, blocks[j].quad)) continue
            val first = root(i)
            val second = root(j)
            // The smaller index always becomes the root, so a group is named by its earliest member.
            if (first < second) group[second] = first else if (second < first) group[first] = second
        }
    }
    val groups = LinkedHashMap<Int, MutableList<RecognizedTextBlock>>()
    for (i in blocks.indices) groups.getOrPut(root(i)) { mutableListOf() } += blocks[i]
    return groups.values.map { if (it.size == 1) it.single() else fused(it) }
}

/**
 * Whether [a] and [b] are two readings of one run rather than two runs.
 *
 * Either one of them is largely inside the other, which is one detection sitting on another
 * whatever their proportions, or the two lie in the same band across the line and their spans along
 * it largely coincide. Both halves of the second test are needed and neither is enough on its own:
 * two neighbouring lines of a paragraph share a span along the page but sit in different bands
 * across it, and two columns side by side share a band but never a span.
 *
 * Measured along the longer run's own reading direction rather than the frame's axes, because the
 * longer run is the better estimate of where the line goes and a page held askew has no horizontal.
 */
private fun sameRun(a: TextQuad, b: TextQuad): Boolean {
    if (quadOverlapRatio(a, b) >= MERGE_OVERLAP_RATIO) return true
    val along = readingAxis(dominant(listOf(a, b)))
    val across = FitPoint(-along.y, along.x)
    return spanOverlapRatio(a, b, across) >= SAME_LINE_BAND_RATIO &&
        spanOverlapRatio(a, b, along) >= SAME_RUN_SPAN_RATIO
}

/**
 * One block covering every one of [parts]: their characters in reading order, the rectangle along
 * the longest part's baseline that holds all of them, and a confidence weighted by how many
 * characters each part put into the result.
 *
 * Weighted by characters because that is what a reading's confidence already is, the reader's mean
 * over the characters it emitted, so the mean over the fused text is the same quantity rather than a
 * new one. A part whose characters another already carries adds none of its own and only lifts the
 * score of those characters when it was the surer of the two readings of them, which is how one
 * heading found twice keeps the better score and gains nothing else.
 */
private fun fused(parts: List<RecognizedTextBlock>): RecognizedTextBlock {
    val along = readingAxis(dominant(parts.map { it.quad }))
    val across = FitPoint(-along.y, along.x)
    val ordered = parts.sortedWith(
        compareBy(
            { spanAlong(it.quad, along).first },
            { spanAlong(it.quad, across).first },
            { it.text },
        ),
    )
    val segments = segmentsOf(ordered)
    val characters = segments.sumOf { it.text.length }
    return RecognizedTextBlock(
        text = segments.joinToString(" ") { it.text },
        confidence = if (characters <= 0) {
            parts.maxOf { it.confidence }
        } else {
            (segments.sumOf { it.text.length * it.confidence.toDouble() } / characters).toFloat()
        },
        quad = coveringQuad(parts.map { it.quad }, along, across),
    )
}

/** One entry per distinct piece of text in [ordered], each holding the surer reading of it. */
private fun segmentsOf(ordered: List<RecognizedTextBlock>): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    for (part in ordered) {
        val text = part.text.trim()
        if (text.isEmpty()) continue
        val carried = segments.indexOfFirst { it.text.contains(text, ignoreCase = true) }
        if (carried >= 0) {
            segments[carried] = segments[carried].surerOf(part.confidence)
            continue
        }
        val widened = segments.indexOfFirst { text.contains(it.text, ignoreCase = true) }
        if (widened >= 0) {
            segments[widened] = TextSegment(text, max(segments[widened].confidence, part.confidence))
            continue
        }
        segments += TextSegment(text, part.confidence)
    }
    return segments
}

private data class TextSegment(val text: String, val confidence: Float) {

    fun surerOf(other: Float): TextSegment = TextSegment(text, max(confidence, other))
}

/**
 * How much of the smaller of [a] and [b] the other one covers, from nothing to all of it.
 *
 * Measured against the smaller area rather than against the union: a reading found twice is usually
 * one detection sitting almost inside a slightly larger one, where the union ratio stays middling
 * even though one region says nothing the other does not.
 *
 * Area alone cannot answer the whole question. A narrow sliver standing across a wider run covers
 * every character in its own width, but the parts of it above and below the run count against it in
 * the denominator, so the taller the sliver the lower this ratio falls, which is the opposite of
 * what it is being asked.
 */
fun quadOverlapRatio(a: TextQuad, b: TextQuad): Float {
    val areaA = polygonArea(a.corners)
    val areaB = polygonArea(b.corners)
    val smaller = min(areaA, areaB)
    if (smaller <= EPSILON) return 0f
    return (intersectionArea(a.corners, b.corners) / smaller).coerceIn(0f, 1f)
}

// ─── what counts as text at all ─────────────────────────────────────────────

/**
 * Whether a reading is worth an outline on the photo.
 *
 * The detector answers how sure it is that something is written somewhere, which a row of status
 * icons, a logo or a barcode satisfies as well as a word does, so those regions reach the reader and
 * come back as one or two characters that resemble their shape. Confidence alone does not catch
 * them: it is the mean over the characters actually emitted, so a single glyph the network is
 * moderately sure of carries the whole score, while a real word has to hold up over every character
 * it emitted.
 *
 * Two rules, and neither is the global threshold raised until the symptom goes away, which would
 * take the house number on a door with it. A run with no letter and no digit anywhere in it is not
 * something to read or copy, whatever the reader thinks of it. And a run of one or two characters is
 * held to [SHORT_TEXT_MIN_CONFIDENCE] instead of [MIN_READ_CONFIDENCE], because one probability is
 * not the evidence that a whole word's worth of them is; a real short label is read at well over
 * that, an icon that happened to resemble a glyph rarely is.
 */
fun looksReadable(text: String, confidence: Float): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (confidence < MIN_READ_CONFIDENCE) return false
    val readable = trimmed.count { it.isLetterOrDigit() }
    if (readable == 0) return false
    return readable > SHORT_TEXT_LENGTH || confidence >= SHORT_TEXT_MIN_CONFIDENCE
}

// ─── the thresholds ─────────────────────────────────────────────────────────

/**
 * How far apart a fitted rectangle's two ends are pulled by the blob's own outline, as a fraction of
 * the run's height. Six tenths of a line's height is one ascender or one descender's worth, and
 * across a twenty-character line, whose box is about ten times as long as it is tall, it works out
 * at three degrees, which is where a rectangle fitted to a full-width line actually sits.
 */
private const val FIT_WOBBLE = 0.6f

/**
 * Ceiling on the tilt any run is laid flat from, in degrees. Four characters make a box about twice
 * as long as it is tall, where the wobble alone would allow fifteen degrees and a slanted one-word
 * sign would be squared up along with the artefacts. Ten is where a slant stops being deniable:
 * under it a level label reads as a badly drawn box, over it the angle is something the photograph
 * has.
 */
const val MAX_DESKEW_DEGREES = 10f

/**
 * How much of the smaller quad the other has to cover before the two count as one detection whatever
 * their proportions. Two neighbouring lines of a paragraph share only the margins they were grown by
 * and stay well below six tenths; a whole reading sitting inside another is at nine or above.
 */
const val MERGE_OVERLAP_RATIO = 0.6f

/**
 * How much of the shorter run's height two readings have to share across the line before they can be
 * one run. Two neighbouring lines of a tightly set paragraph share the margins the detector grew
 * them by, which comes to about a third of a line; six tenths sits clear of that, while a sliver
 * standing across a heading covers the heading's whole height.
 */
const val SAME_LINE_BAND_RATIO = 0.6f

/**
 * How much of the shorter run's length two readings have to share along the line before they can be
 * one run. Half means one of them is largely inside the other rather than merely running up against
 * it: two words with a space between them meet in their margins alone, a fifth at most, while a
 * piece of a run that was found twice covers nearly all of the shorter reading.
 */
const val SAME_RUN_SPAN_RATIO = 0.5f

/** Confidence a reading has to reach before its outline is drawn at all. */
const val MIN_READ_CONFIDENCE = 0.5f

/** Longest reading held to the short-text bar, in letters and digits. */
const val SHORT_TEXT_LENGTH = 2

/** Confidence a one or two character reading has to reach instead. */
const val SHORT_TEXT_MIN_CONFIDENCE = 0.8f

private const val EPSILON = 1e-6f

// ─── quad arithmetic ────────────────────────────────────────────────────────

/** The mean of [quad]'s two edges running with the text. */
private fun quadLength(quad: TextQuad): Float =
    (edgeLength(quad.topLeft, quad.topRight) + edgeLength(quad.bottomLeft, quad.bottomRight)) / 2f

/** The mean of [quad]'s two edges running across the text. */
private fun quadHeight(quad: TextQuad): Float =
    (edgeLength(quad.topLeft, quad.bottomLeft) + edgeLength(quad.topRight, quad.bottomRight)) / 2f

/** The unit vector [quad]'s words run along, from both of its long edges. */
private fun readingAxis(quad: TextQuad): FitPoint {
    val dx = (quad.topRight.x - quad.topLeft.x) + (quad.bottomRight.x - quad.bottomLeft.x)
    val dy = (quad.topRight.y - quad.topLeft.y) + (quad.bottomRight.y - quad.bottomLeft.y)
    val length = hypot(dx, dy)
    if (length < EPSILON) return FitPoint(1f, 0f)
    return FitPoint(dx / length, dy / length)
}

/**
 * The one of [quads] whose reading direction the rest are judged along: the longest, since the same
 * corner landing badly turns a short run much further than a long one. The remaining tests only ever
 * come up for quads that are the same size, and they settle it by something every caller can see, so
 * the answer never depends on which order the pair was handed over in.
 */
private fun dominant(quads: List<TextQuad>): TextQuad =
    quads.reduce { best, quad -> if (BY_DOMINANCE.compare(quad, best) < 0) quad else best }

private val BY_DOMINANCE: Comparator<TextQuad> = compareByDescending<TextQuad> { quadLength(it) }
    .thenBy { quadTiltDegrees(it) }
    .thenBy { it.topLeft.x }
    .thenBy { it.topLeft.y }

/** Where [quad] starts and ends along [direction]. */
private fun spanAlong(quad: TextQuad, direction: FitPoint): Pair<Float, Float> {
    var lowest = Float.MAX_VALUE
    var highest = -Float.MAX_VALUE
    for (corner in quad.corners) {
        val projected = corner.x * direction.x + corner.y * direction.y
        if (projected < lowest) lowest = projected
        if (projected > highest) highest = projected
    }
    return lowest to highest
}

/** How much of the shorter of [a] and [b]'s extents along [direction] the two of them share. */
private fun spanOverlapRatio(a: TextQuad, b: TextQuad, direction: FitPoint): Float {
    val (aStart, aEnd) = spanAlong(a, direction)
    val (bStart, bEnd) = spanAlong(b, direction)
    val shorter = min(aEnd - aStart, bEnd - bStart)
    if (shorter <= EPSILON) return 0f
    return ((min(aEnd, bEnd) - max(aStart, bStart)) / shorter).coerceIn(0f, 1f)
}

/**
 * The rectangle holding every corner of [quads], built in the frame [along] and [across] give rather
 * than the photo's own, so a fused run keeps the angle its words sit at instead of growing to the
 * upright box around them.
 */
private fun coveringQuad(quads: List<TextQuad>, along: FitPoint, across: FitPoint): TextQuad {
    var minAlong = Float.MAX_VALUE
    var maxAlong = -Float.MAX_VALUE
    var minAcross = Float.MAX_VALUE
    var maxAcross = -Float.MAX_VALUE
    for (quad in quads) {
        val (startAlong, endAlong) = spanAlong(quad, along)
        val (startAcross, endAcross) = spanAlong(quad, across)
        minAlong = min(minAlong, startAlong)
        maxAlong = max(maxAlong, endAlong)
        minAcross = min(minAcross, startAcross)
        maxAcross = max(maxAcross, endAcross)
    }
    fun corner(atAlong: Float, atAcross: Float) = FitPoint(
        along.x * atAlong + across.x * atAcross,
        along.y * atAlong + across.y * atAcross,
    )
    return TextQuad(
        topLeft = corner(minAlong, minAcross),
        topRight = corner(maxAlong, minAcross),
        bottomRight = corner(maxAlong, maxAcross),
        bottomLeft = corner(minAlong, maxAcross),
    )
}

// ─── polygon arithmetic ─────────────────────────────────────────────────────

private fun edgeLength(a: FitPoint, b: FitPoint): Float = hypot(b.x - a.x, b.y - a.y)

/** The area [polygon] encloses, whichever way round its corners were given. */
private fun polygonArea(polygon: List<FitPoint>): Float = abs(signedArea(polygon))

private fun signedArea(polygon: List<FitPoint>): Float {
    if (polygon.size < 3) return 0f
    var total = 0f
    for (i in polygon.indices) {
        val next = polygon[(i + 1) % polygon.size]
        total += polygon[i].x * next.y - next.x * polygon[i].y
    }
    return total / 2f
}

/**
 * The area two convex polygons share. Sutherland and Hodgman's clip: cut the first polygon against
 * each edge of the second in turn, and what is left is the overlap. Both are wound the same way
 * first, so which side of an edge counts as inside is the same question every time.
 */
private fun intersectionArea(a: List<FitPoint>, b: List<FitPoint>): Float {
    if (a.size < 3 || b.size < 3) return 0f
    val clip = if (signedArea(b) < 0f) b.reversed() else b
    var output = if (signedArea(a) < 0f) a.reversed() else a

    for (i in clip.indices) {
        if (output.size < 3) return 0f
        val edgeStart = clip[i]
        val edgeEnd = clip[(i + 1) % clip.size]
        val input = output
        val next = ArrayList<FitPoint>(input.size + 1)
        for (k in input.indices) {
            val current = input[k]
            val previous = input[(k - 1 + input.size) % input.size]
            val currentInside = sideOf(edgeStart, edgeEnd, current) >= 0f
            val previousInside = sideOf(edgeStart, edgeEnd, previous) >= 0f
            if (currentInside) {
                if (!previousInside) crossing(previous, current, edgeStart, edgeEnd)?.let { next += it }
                next += current
            } else if (previousInside) {
                crossing(previous, current, edgeStart, edgeEnd)?.let { next += it }
            }
        }
        output = next
    }
    return polygonArea(output)
}

/** Which side of the line through [edgeStart] and [edgeEnd] the point sits on. */
private fun sideOf(edgeStart: FitPoint, edgeEnd: FitPoint, point: FitPoint): Float =
    (edgeEnd.x - edgeStart.x) * (point.y - edgeStart.y) -
        (edgeEnd.y - edgeStart.y) * (point.x - edgeStart.x)

/** Where the segment from [from] to [to] meets the line through [edgeStart] and [edgeEnd]. */
private fun crossing(
    from: FitPoint,
    to: FitPoint,
    edgeStart: FitPoint,
    edgeEnd: FitPoint,
): FitPoint? {
    val segmentX = to.x - from.x
    val segmentY = to.y - from.y
    val edgeX = edgeEnd.x - edgeStart.x
    val edgeY = edgeEnd.y - edgeStart.y
    val denominator = segmentX * edgeY - segmentY * edgeX
    if (abs(denominator) < EPSILON) return null
    val t = ((edgeStart.x - from.x) * edgeY - (edgeStart.y - from.y) * edgeX) / denominator
    return FitPoint(from.x + segmentX * t, from.y + segmentY * t)
}
