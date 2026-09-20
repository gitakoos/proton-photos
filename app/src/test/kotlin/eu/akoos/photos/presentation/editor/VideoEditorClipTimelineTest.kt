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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordered clip-list maths behind the CapCut-style video track: how a source clip resolves, splits
 * at the edited-time playhead, trims each clip's own edges, reorders, and how the played (edited)
 * timeline maps back to source positions. These decisions drive what the timeline draws, what the
 * preview plays in order, and what the export writes.
 */
class VideoEditorClipTimelineTest {

    private fun clip(id: Long, s: Long, e: Long, removed: Boolean = false) = VideoClip(id, s, e, removed)

    /** A clip carrying an explicit FIXED slot (its trim territory), the way a split produces its halves. */
    private fun slotClip(id: Long, s: Long, e: Long, slotStart: Long, slotEnd: Long) =
        VideoClip(id, s, e, slotStartMs = slotStart, slotEndMs = slotEnd)

    /** A single primary source of the given duration, for the common single-source resolveClips cases. */
    private fun sources(durationMs: Long) = listOf(VideoSource(PRIMARY_SOURCE_ID, "", durationMs))

    // ── resolveClips ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty list resolves to one full-length clip`() {
        assertEquals(listOf(VideoClip(0L, 0L, 10_000L)), resolveClips(emptyList(), sources(10_000L)))
    }

    @Test
    fun `clips are clamped to the source and order is preserved, never sorted`() {
        val clips = listOf(clip(2, 6_000L, 12_000L), clip(1, 0L, 3_000L))
        assertEquals(
            listOf(clip(2, 6_000L, 10_000L), clip(1, 0L, 3_000L)),
            resolveClips(clips, sources(10_000L)),
        )
    }

    @Test
    fun `a clip shorter than the minimum is dropped`() {
        val clips = listOf(clip(1, 0L, 5_000L), clip(2, 5_000L, 5_050L))
        assertEquals(listOf(clip(1, 0L, 5_000L)), resolveClips(clips, sources(10_000L)))
    }

    // ── edited duration + export windows ─────────────────────────────────────────────────────────

    @Test
    fun `edited duration sums only the kept clips`() {
        val clips = listOf(clip(1, 0L, 3_000L), clip(2, 3_000L, 6_000L, removed = true), clip(3, 6_000L, 10_000L))
        assertEquals(7_000L, editedDurationMs(clips))
    }

    @Test
    fun `contiguous in-order clips export as one merged window`() {
        val clips = listOf(clip(1, 0L, 4_000L), clip(2, 4_000L, 10_000L))
        assertEquals(listOf(VideoSegment(0L, 10_000L)), clipWindows(clips))
    }

    @Test
    fun `a removed clip splits the export into two windows`() {
        val clips = listOf(clip(1, 0L, 3_000L), clip(2, 3_000L, 6_000L, removed = true), clip(3, 6_000L, 10_000L))
        assertEquals(listOf(VideoSegment(0L, 3_000L), VideoSegment(6_000L, 10_000L)), clipWindows(clips))
    }

    @Test
    fun `reordered clips export in play order, not source order`() {
        val clips = listOf(clip(2, 6_000L, 10_000L), clip(1, 0L, 3_000L))
        assertEquals(listOf(VideoSegment(6_000L, 10_000L), VideoSegment(0L, 3_000L)), clipWindows(clips))
    }

    // ── split at the edited playhead ─────────────────────────────────────────────────────────────

    @Test
    fun `a split carves the clip under the edited playhead into two and divides its slot at the cut`() {
        val out = splitClipsAtEdited(listOf(clip(0, 0L, 10_000L)), 4_000L, 1L)
        assertEquals(2, out.size)
        // The head keeps [0,4000] and its slot's far edge drops to the cut; the tail owns [4000, ..] with
        // its slot floored at the cut. The cut (4000) is now a fixed boundary neither half can cross.
        assertEquals(slotClip(0, 0L, 4_000L, slotStart = 0L, slotEnd = 4_000L), out[0])
        assertEquals(slotClip(1, 4_000L, 10_000L, slotStart = 4_000L, slotEnd = Long.MAX_VALUE), out[1])
    }

    @Test
    fun `a split maps edited time across a removed clip to the right source point`() {
        // Clip A [0,3000] kept, B [3000,6000] removed, C [6000,10000] kept. Edited 4000 falls 1000 into C.
        val clips = listOf(clip(1, 0L, 3_000L), clip(2, 3_000L, 6_000L, removed = true), clip(3, 6_000L, 10_000L))
        val out = splitClipsAtEdited(clips, 4_000L, 9L)
        // C splits at source 6000 + (4000 - 3000 edited-before-C) = 7000; its slot divides at 7000 too.
        assertEquals(slotClip(3, 6_000L, 7_000L, slotStart = 0L, slotEnd = 7_000L), out[2])
        assertEquals(slotClip(9, 7_000L, 10_000L, slotStart = 7_000L, slotEnd = Long.MAX_VALUE), out[3])
    }

    @Test
    fun `a split too close to a boundary is a no-op`() {
        val one = listOf(clip(0, 0L, 10_000L))
        assertEquals(one, splitClipsAtEdited(one, 100L, 1L))
    }

    @Test
    fun `canSplit reflects whether a split would take`() {
        val one = listOf(clip(0, 0L, 10_000L))
        assertTrue(canSplitClipsAt(one, 5_000L))
        assertFalse(canSplitClipsAt(one, 100L))
    }

    // ── per-clip edge trims (order-independent) ──────────────────────────────────────────────────

    @Test
    fun `a clip start clamps to zero and to its own end`() {
        val clips = listOf(clip(1, 2_000L, 8_000L))
        assertEquals(0L, clampClipStart(clips, 0, -500L))
        assertEquals(8_000L - MIN_SEGMENT_MS, clampClipStart(clips, 0, 9_000L))
    }

    @Test
    fun `a clip end clamps to the source duration and to its own start`() {
        val clips = listOf(clip(1, 2_000L, 8_000L))
        assertEquals(10_000L, clampClipEnd(clips, 0, 99_000L, 10_000L))
        assertEquals(2_000L + MIN_SEGMENT_MS, clampClipEnd(clips, 0, 0L, 10_000L))
    }

    @Test
    fun `a split point is a hard boundary each half's own slot enforces`() {
        // Split of [0,10000] at 4000 -> head slot [0,4000] + tail slot [4000,10000]. Each half is clamped
        // to its OWN fixed slot, so neither may re-extend over 4000.
        val split = listOf(
            slotClip(1, 0L, 4_000L, slotStart = 0L, slotEnd = 4_000L),
            slotClip(2, 4_000L, 10_000L, slotStart = 4_000L, slotEnd = 10_000L),
        )
        // The head's end is capped at its slot end (the split), not the source duration.
        assertEquals(4_000L, clampClipEnd(split, 0, 9_000L, 10_000L))
        // The tail's start is floored at its slot start (the split), not 0.
        assertEquals(4_000L, clampClipStart(split, 1, 500L))
        // But trimming inward and back out to the boundary stays reversible.
        assertEquals(3_000L, clampClipEnd(split, 0, 3_000L, 10_000L))
        assertEquals(6_000L, clampClipStart(split, 1, 6_000L))
    }

    @Test
    fun `each half clamps to its own fixed slot regardless of play order`() {
        // Same two halves, but stored in reverse play order (tail first). The clamp keys off each clip's
        // own slot, not its neighbour, so play order does not matter.
        val reordered = listOf(
            slotClip(2, 4_000L, 10_000L, slotStart = 4_000L, slotEnd = 10_000L),
            slotClip(1, 0L, 4_000L, slotStart = 0L, slotEnd = 4_000L),
        )
        // The [0,4000] half (index 1) still cannot end past its slot end at 4000.
        assertEquals(4_000L, clampClipEnd(reordered, 1, 9_000L, 10_000L))
        // The [4000,10000] half (index 0) still cannot start before its slot start.
        assertEquals(4_000L, clampClipStart(reordered, 0, 500L))
    }

    @Test
    fun `trimming one split half never moves the other half or reflows the track`() {
        // The reflow bug: head slot [0,4000], tail slot [4000,10000]. Trimming the TAIL's start inward must
        // not change what the HEAD can do — under the old sibling-derived slot, the head's max end tracked
        // the tail's start and the whole track shifted. With fixed slots it is pinned to the cut.
        val head = slotClip(1, 0L, 4_000L, slotStart = 0L, slotEnd = 4_000L)
        val tail = slotClip(2, 4_000L, 10_000L, slotStart = 4_000L, slotEnd = 10_000L)
        val tailStart = clampClipStart(listOf(head, tail), 1, 6_000L)
        assertEquals(6_000L, tailStart)
        // After the tail moved its start to 6000, the head's end is STILL capped at its own slot end (4000),
        // not the tail's new start (6000). So the head neither grows nor shifts.
        val afterTailTrim = listOf(head, tail.copy(startMs = tailStart))
        assertEquals(4_000L, clampClipEnd(afterTailTrim, 0, 9_000L, 10_000L))
        assertEquals(4_000L, head.slotEndMs)
        assertEquals(4_000L, afterTailTrim[0].slotEndMs)
    }

    // ── reorder + edited-to-source mapping ───────────────────────────────────────────────────────

    @Test
    fun `moving a clip changes play order`() {
        val clips = listOf(clip(1, 0L, 3_000L), clip(2, 3_000L, 6_000L), clip(3, 6_000L, 10_000L))
        assertEquals(listOf(3L, 1L, 2L), moveClipAt(clips, 2, 0).map { it.id })
    }

    @Test
    fun `edited time maps to the clip and source ms holding it`() {
        val kept = listOf(clip(1, 0L, 3_000L), clip(3, 6_000L, 10_000L))
        // Edited 4000 = 1000 into the second clip -> source 6000 + 1000 = 7000.
        assertEquals(1 to 7_000L, editedToClipPos(kept, 4_000L))
        // Edited 1000 is inside the first clip -> source 1000.
        assertEquals(0 to 1_000L, editedToClipPos(kept, 1_000L))
    }

    // ── removeClipResult: grey a primary clip, drop an added source's clip ─────────────────────────

    /** Two sources: the primary (id 0) and one added via the "+". */
    private fun twoSources() = listOf(VideoSource(0, "", 10_000L), VideoSource(1, "", 5_000L))
    private fun addedClip(id: Long, s: Long, e: Long) = VideoClip(id, s, e, sourceId = 1)

    @Test
    fun `removing a primary clip greys it and leaves the sources`() {
        val clips = listOf(clip(1, 0L, 4_000L), clip(2, 4_000L, 10_000L))
        val src = sources(10_000L)
        val (out, outSrc) = removeClipResult(clips, src, id = 1L, primaryId = PRIMARY_SOURCE_ID)!!
        assertEquals(2, out.size) // greyed, not dropped
        assertTrue(out.first { it.id == 1L }.removed)
        assertEquals(src, outSrc)
    }

    @Test
    fun `removing the last playing primary clip is a no-op`() {
        assertEquals(null, removeClipResult(listOf(clip(1, 0L, 10_000L)), sources(10_000L), 1L, PRIMARY_SOURCE_ID))
    }

    @Test
    fun `removing an added source's only clip drops the clip and the source`() {
        val clips = listOf(clip(1, 0L, 10_000L), addedClip(2, 0L, 5_000L))
        val (out, outSrc) = removeClipResult(clips, twoSources(), id = 2L, primaryId = 0)!!
        assertEquals(listOf(1L), out.map { it.id }) // added clip gone
        assertEquals(listOf(0), outSrc.map { it.id }) // added source gone -> single source again
    }

    @Test
    fun `removing one of an added source's clips keeps the source`() {
        val clips = listOf(clip(1, 0L, 10_000L), addedClip(2, 0L, 2_500L), addedClip(3, 2_500L, 5_000L))
        val (out, outSrc) = removeClipResult(clips, twoSources(), id = 2L, primaryId = 0)!!
        assertEquals(listOf(1L, 3L), out.map { it.id }) // only clip 2 gone
        assertEquals(listOf(0, 1), outSrc.map { it.id }) // source 1 still used by clip 3 -> kept
    }

    @Test
    fun `removing an unknown clip id is a no-op`() {
        assertEquals(null, removeClipResult(listOf(clip(1, 0L, 10_000L)), sources(10_000L), 99L, 0))
    }
}
