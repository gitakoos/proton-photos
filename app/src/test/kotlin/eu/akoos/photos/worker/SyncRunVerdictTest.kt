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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a sync run reports to WorkManager ([syncRunVerdict]). */
class SyncRunVerdictTest {

    @Test
    fun `a run whose upload did not fail succeeds`() {
        assertEquals(SyncRunVerdict.SUCCESS, syncRunVerdict(uploadFailed = false, retryWorthwhile = false, runAttemptCount = 0))
    }

    @Test
    fun `a failed run a retry can help is retried`() {
        assertEquals(SyncRunVerdict.RETRY, syncRunVerdict(uploadFailed = true, retryWorthwhile = true, runAttemptCount = 2))
    }

    @Test
    fun `a failed run stops retrying after the third run`() {
        assertEquals(SyncRunVerdict.FAILURE, syncRunVerdict(uploadFailed = true, retryWorthwhile = true, runAttemptCount = 3))
    }

    @Test
    fun `a run that failed only on files Drive refused is not retried`() {
        assertEquals(SyncRunVerdict.FAILURE, syncRunVerdict(uploadFailed = true, retryWorthwhile = false, runAttemptCount = 0))
    }

    @Test
    fun `a run whose upload did not fail records the last sync time`() {
        assertTrue(syncRunStampsLastSync(uploadFailed = false, retryWorthwhile = false))
    }

    @Test
    fun `a run that failed only on files Drive refused still records the last sync time`() {
        assertTrue(syncRunStampsLastSync(uploadFailed = true, retryWorthwhile = false))
    }

    @Test
    fun `a run that failed in a way a retry can fix leaves the last sync time alone`() {
        assertFalse(syncRunStampsLastSync(uploadFailed = true, retryWorthwhile = true))
    }
}
