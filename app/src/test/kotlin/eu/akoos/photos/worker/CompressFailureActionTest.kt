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

package eu.akoos.photos.worker

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the compression drain does with a failed photo: retry, take its copy back, or leave the copy. */
class CompressFailureActionTest {

    @Test
    fun `a transient failure is kept until the photo's own tries run out`() {
        assertEquals(CompressFailureAction.KEEP, compressFailureAction(true, 0, null, false))
        assertEquals(CompressFailureAction.KEEP, compressFailureAction(true, MAX_PHOTO_ATTEMPTS - 2, "copy", false))
        assertEquals(CompressFailureAction.ROLL_BACK_AND_DROP, compressFailureAction(true, MAX_PHOTO_ATTEMPTS - 1, "copy", false))
    }

    @Test
    fun `a given-up copy is taken back only while nothing moved to it`() {
        assertEquals(CompressFailureAction.ROLL_BACK_AND_DROP, compressFailureAction(false, 0, "copy", false))
        assertEquals(CompressFailureAction.DROP, compressFailureAction(false, 0, "copy", true))
        assertEquals(CompressFailureAction.DROP, compressFailureAction(true, MAX_PHOTO_ATTEMPTS, "copy", true))
    }

    @Test
    fun `a failure before any upload is dropped with nothing to take back`() {
        assertEquals(CompressFailureAction.DROP, compressFailureAction(false, 0, null, false))
    }

    @Test
    fun `a compression held by another edit counts against the photo only after a day`() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(false, heldTooLong(enqueuedAt = 1_000L, now = 1_000L + day - 1))
        assertEquals(true, heldTooLong(enqueuedAt = 1_000L, now = 1_000L + day + 1))
    }

    @Test
    fun `the summary counts the photos a cancel left as they were`() {
        // 20 queued, cancelled after 10 were compressed: the other 10 are left, none failed.
        assertEquals(10, leftAsTheyWere(total = 20, compressed = 10, skipped = 0, failed = 0, stopped = true))
        // No cancel: only the skipped and failed ones.
        assertEquals(3, leftAsTheyWere(total = 20, compressed = 17, skipped = 2, failed = 1, stopped = false))
        // A cancel never reports fewer than were skipped or failed.
        assertEquals(4, leftAsTheyWere(total = 5, compressed = 5, skipped = 3, failed = 1, stopped = true))
    }
}
