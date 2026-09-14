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

package eu.akoos.photos.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decision every "add to album" surface (the timeline selection controller, location detail,
 * device folders) reads its result wording from, so a full success stays quiet, a partial or full
 * reject is always surfaced, and a thrown error can never read as a silent success.
 *
 * No Android, no Context, no Robolectric: plain JVM assertions over the counts.
 */
class AddToAlbumOutcomeTest {

    @Test
    fun `every photo added owes no failure`() {
        val outcome = addToAlbumOutcome(requested = 5, succeededLinkIds = 5, failedLinkIds = 0, threw = false)
        assertEquals(AddToAlbumOutcome(added = 5, failed = 0), outcome)
        assertFalse(outcome.isPartialOrFullFailure)
    }

    @Test
    fun `a partial reject keeps both counts and reports a failure`() {
        val outcome = addToAlbumOutcome(requested = 13, succeededLinkIds = 10, failedLinkIds = 3, threw = false)
        assertEquals(10, outcome.added)
        assertEquals(3, outcome.failed)
        assertTrue(outcome.isPartialOrFullFailure)
    }

    @Test
    fun `a total server reject added nothing and reports a failure`() {
        val outcome = addToAlbumOutcome(requested = 4, succeededLinkIds = 0, failedLinkIds = 4, threw = false)
        assertEquals(0, outcome.added)
        assertEquals(4, outcome.failed)
        assertTrue(outcome.isPartialOrFullFailure)
    }

    @Test
    fun `a thrown error counts every requested photo as failed`() {
        val outcome = addToAlbumOutcome(requested = 7, succeededLinkIds = 0, failedLinkIds = 0, threw = true)
        assertEquals(0, outcome.added)
        assertEquals(7, outcome.failed)
        assertTrue(outcome.isPartialOrFullFailure)
    }
}
