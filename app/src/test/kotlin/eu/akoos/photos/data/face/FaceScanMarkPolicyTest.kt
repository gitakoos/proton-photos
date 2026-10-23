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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure rule that decides whether a photo whose source failed to load is recorded scanned (so a
 * later pass skips it) or left pending. The watchdog hardening leans on it: a deterministic decode /
 * decrypt wedge is marked, and so is a photo the load reports permanently unreadable (a cloud original
 * gone, or a device file that opens but will not decode), but a plain defer, a device file that could not
 * be opened or a transient failure is left pending so a later pass retries it.
 */
class FaceScanMarkPolicyTest {

    @Test
    fun a_device_file_that_opens_but_will_not_decode_is_marked() {
        // The load names it PERMANENT once the file opened and the decoder made no image of it.
        assertTrue(shouldMarkScannedOnNullSource(loadTimedOut = false, miss = SourceMiss.PERMANENT))
    }

    @Test
    fun a_device_file_that_could_not_be_opened_stays_pending() {
        // An unmounted card or a provider that answered nothing is a transient miss, retried after a backoff.
        assertFalse(shouldMarkScannedOnNullSource(loadTimedOut = false, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_load_that_merely_deferred_stays_pending() {
        assertFalse(shouldMarkScannedOnNullSource(loadTimedOut = false, miss = SourceMiss.DEFERRED))
        assertFalse(shouldMarkScannedOnNullSource(loadTimedOut = false, miss = SourceMiss.OVER_BUDGET))
    }

    @Test
    fun a_load_that_wedged_is_marked() {
        // A decode or decrypt that ran past its watchdog is deterministic and would re-wedge next pass.
        assertTrue(shouldMarkScannedOnNullSource(loadTimedOut = true, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_model_that_would_not_open_leaves_the_photo_pending() {
        assertEquals(MlScanVerdict.STAY_PENDING, mlScanVerdict(openFailed = true, runFailed = false))
        assertEquals(MlScanVerdict.STAY_PENDING, mlScanVerdict(openFailed = true, runFailed = true))
    }

    @Test
    fun a_detection_that_threw_is_still_recorded() {
        assertEquals(MlScanVerdict.RECORD_THEN_RETHROW, mlScanVerdict(openFailed = false, runFailed = true))
        assertEquals(MlScanVerdict.RECORD, mlScanVerdict(openFailed = false, runFailed = false))
    }
}
