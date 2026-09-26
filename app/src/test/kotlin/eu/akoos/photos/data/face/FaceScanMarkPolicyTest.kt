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

import eu.akoos.photos.util.SourceMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure rule that decides whether a photo whose source failed to load is recorded scanned (so a
 * later pass skips it) or left pending. The watchdog hardening leans on it: a device file with no frame
 * and a deterministic decode / decrypt wedge are marked, and so is a cloud photo the load reports
 * permanently unavailable, but a plain cloud defer or a transient failure is left pending so a later pass
 * retries it.
 */
class FaceScanMarkPolicyTest {

    @Test
    fun a_device_file_with_no_decodable_frame_is_marked() {
        // A corrupt or unsupported local file has no cloud original to retry, so mark it (never re-hang).
        for (miss in SourceMiss.entries) {
            assertTrue(shouldMarkScannedOnNullSource(isLocalOnly = true, loadTimedOut = false, miss = miss))
            assertTrue(shouldMarkScannedOnNullSource(isLocalOnly = true, loadTimedOut = true, miss = miss))
        }
    }

    @Test
    fun a_cloud_item_that_merely_deferred_or_failed_transiently_stays_pending() {
        // Off Wi-Fi, out of budget, or a transient failure: no wedge flag set, so leave it pending.
        assertFalse(shouldMarkScannedOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.DEFERRED))
        assertFalse(shouldMarkScannedOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.OVER_BUDGET))
        assertFalse(shouldMarkScannedOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_cloud_item_the_load_reports_permanently_unavailable_is_marked() {
        // The link, its keys or its revision are gone, or the original will not decode: retrying only
        // spends the pass's budget on it again, so mark it and let the walk move past it.
        assertTrue(shouldMarkScannedOnNullSource(isLocalOnly = false, loadTimedOut = false, miss = SourceMiss.PERMANENT))
    }

    @Test
    fun a_cloud_item_whose_load_wedged_is_marked() {
        // A decode or decrypt that ran past its watchdog is deterministic and would re-wedge next pass.
        assertTrue(shouldMarkScannedOnNullSource(isLocalOnly = false, loadTimedOut = true, miss = SourceMiss.TRANSIENT))
    }
}
