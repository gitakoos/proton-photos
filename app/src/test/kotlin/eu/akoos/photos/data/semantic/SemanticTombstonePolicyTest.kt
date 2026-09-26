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

package eu.akoos.photos.data.semantic

import eu.akoos.photos.util.SourceMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the semantic walk's copy of the settle rule: which photos with no source get a zero-vector
 * tombstone (so a later pass skips them) and which stay pending for a retry.
 */
class SemanticTombstonePolicyTest {

    @Test
    fun a_device_file_with_no_decodable_frame_is_tombstoned() {
        for (miss in SourceMiss.entries) {
            assertTrue(shouldTombstoneOnNullSource(isLocalOnly = true, loadTimedOut = false, miss = miss))
        }
    }

    @Test
    fun a_cloud_item_the_load_reports_permanently_unavailable_is_tombstoned() {
        assertTrue(shouldTombstoneOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.PERMANENT))
    }

    @Test
    fun a_cloud_item_whose_load_wedged_is_tombstoned() {
        assertTrue(shouldTombstoneOnNullSource(isLocalOnly = false, loadTimedOut = true, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_cloud_item_that_deferred_or_failed_transiently_stays_pending() {
        assertFalse(shouldTombstoneOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.TRANSIENT))
        assertFalse(shouldTombstoneOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.OVER_BUDGET))
        assertFalse(shouldTombstoneOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.DEFERRED))
    }
}
