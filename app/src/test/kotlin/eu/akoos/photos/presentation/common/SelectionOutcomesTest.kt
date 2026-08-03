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

import eu.akoos.photos.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the two decisions every multi-select surface (the timeline, search, album detail, location
 * detail, device folders) reads its result wording from, so the four of them can never disagree.
 *
 * Share: a batch that resolved everything owes no message because the chooser opening says it; one
 * that resolved some owes the partial count; one that resolved nothing owes a failure, since no
 * chooser opens and the selection clears regardless.
 *
 * Strip: a write that was attempted and did not work counts as failed, never as skipped, so "3
 * skipped" can no longer stand in for "3 could not be written".
 *
 * No Android, no Context, no Robolectric: plain JVM assertions over counts and resource ids.
 */
class SelectionOutcomesTest {

    // ── Share ───────────────────────────────────────────────────────────────────

    @Test
    fun `every item resolved needs no message`() {
        assertEquals(ShareOutcome.AllShared, shareOutcome(shared = 5, failed = 0))
        assertNull(shareOutcome(shared = 5, failed = 0).message())
    }

    @Test
    fun `some items resolved reports both counts`() {
        val outcome = shareOutcome(shared = 3, failed = 7)
        assertEquals(ShareOutcome.SomeShared(shared = 3, failed = 7), outcome)
        val message = outcome.message()
        assertEquals(R.string.share_selection_partial, message?.res)
        assertEquals(listOf<Any>(3, 7), message?.args)
    }

    @Test
    fun `nothing resolved reports a failure`() {
        val outcome = shareOutcome(shared = 0, failed = 4)
        assertEquals(ShareOutcome.NoneShared, outcome)
        assertEquals(R.string.share_selection_failed, outcome.message()?.res)
        assertEquals(emptyList<Any>(), outcome.message()?.args)
    }

    @Test
    fun `an empty batch stays quiet`() {
        assertNull(shareOutcome(shared = 0, failed = 0).message())
    }

    // ── Strip ───────────────────────────────────────────────────────────────────

    @Test
    fun `a clean pass is done`() {
        assertEquals(
            StripOutcome.Done(stripped = 9, skipped = 0),
            stripOutcome(stripped = 9, skipped = 0, failed = 0),
        )
    }

    @Test
    fun `skipped items alone keep the pass done`() {
        assertEquals(
            StripOutcome.Done(stripped = 4, skipped = 2),
            stripOutcome(stripped = 4, skipped = 2, failed = 0),
        )
    }

    @Test
    fun `a single failure turns the pass into a failure and keeps the counts apart`() {
        assertEquals(
            StripOutcome.Failed(stripped = 4, skipped = 2, failed = 1),
            stripOutcome(stripped = 4, skipped = 2, failed = 1),
        )
    }

    @Test
    fun `failures without skips report stripped and failed`() {
        val message = StripOutcome.Failed(stripped = 7, skipped = 0, failed = 3).message()
        assertEquals(R.string.gallery_stripped_with_failed, message.res)
        assertEquals(listOf<Any>(7, 3), message.args)
    }

    @Test
    fun `failures alongside skips report all three counts`() {
        val message = StripOutcome.Failed(stripped = 5, skipped = 2, failed = 3).message()
        assertEquals(R.string.gallery_stripped_with_skipped_and_failed, message.res)
        assertEquals(listOf<Any>(5, 2, 3), message.args)
    }

    @Test
    fun `nothing stripped reports only the failure count`() {
        val message = StripOutcome.Failed(stripped = 0, skipped = 2, failed = 3).message()
        assertEquals(R.string.gallery_strip_all_failed, message.res)
        assertEquals(listOf<Any>(3), message.args)
    }
}
