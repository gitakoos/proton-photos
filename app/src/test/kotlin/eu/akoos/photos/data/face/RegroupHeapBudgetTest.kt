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

package eu.akoos.photos.data.face

import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the full regroup's heap check: what a library of a given size is estimated to need, and when that
 * fits the heap still free, so a regroup that would exhaust the heap is skipped rather than run.
 */
class RegroupHeapBudgetTest {

    private val mib = 1024L * 1024L

    @Test
    fun `each face is budgeted at least its unpacked vector`() {
        assertTrue(REGROUP_HEAP_BYTES_PER_FACE >= FACE_EMBEDDING_DIM * Float.SIZE_BYTES + 16L)
    }

    @Test
    fun `the need grows with the face count on top of a fixed reserve`() {
        assertEquals(REGROUP_HEAP_RESERVE_BYTES, regroupHeapNeedBytes(0))
        assertEquals(REGROUP_HEAP_RESERVE_BYTES + 3L * 1024L, regroupHeapNeedBytes(1))
        // Thirty thousand faces: about 88 MiB of faces on the 48 MiB reserve.
        assertEquals(30_000L * 3L * 1024L + 48L * mib, regroupHeapNeedBytes(30_000))
    }

    @Test
    fun `a failed count reads as no faces, never a negative need`() {
        assertEquals(REGROUP_HEAP_RESERVE_BYTES, regroupHeapNeedBytes(-5))
    }

    @Test
    fun `a regroup runs when the free heap holds its need`() {
        val need = regroupHeapNeedBytes(30_000)
        assertTrue(regroupFitsHeap(need, maxHeapBytes = 512L * mib, usedHeapBytes = 200L * mib))
        assertTrue("exactly the need still fits", regroupFitsHeap(need, maxHeapBytes = need + 100L * mib, usedHeapBytes = 100L * mib))
    }

    @Test
    fun `a regroup is skipped when the free heap falls short`() {
        val need = regroupHeapNeedBytes(30_000)
        assertFalse(regroupFitsHeap(need, maxHeapBytes = 256L * mib, usedHeapBytes = 150L * mib))
        assertFalse("one byte short", regroupFitsHeap(need, maxHeapBytes = need + 100L * mib, usedHeapBytes = 100L * mib + 1L))
    }

    @Test
    fun `a large library on a small heap is skipped while a small one runs`() {
        val max = 256L * mib
        val used = 80L * mib
        assertTrue(regroupFitsHeap(regroupHeapNeedBytes(20_000), max, used))
        assertFalse(regroupFitsHeap(regroupHeapNeedBytes(50_000), max, used))
    }
}
