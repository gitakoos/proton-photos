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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins that only the latest pull-to-refresh may clear the album's refresh indicator. */
class PullRefreshTrackerTest {

    @Test
    fun a_single_pull_clears_its_own_indicator() {
        val tracker = PullRefreshTracker()
        assertTrue(tracker.isLatest(tracker.begin()))
    }

    @Test
    fun an_overlapped_older_pull_does_not_clear_the_newer_ones_indicator() {
        val tracker = PullRefreshTracker()
        val first = tracker.begin()
        val second = tracker.begin()
        assertFalse(tracker.isLatest(first))
        assertTrue(tracker.isLatest(second))
    }
}
