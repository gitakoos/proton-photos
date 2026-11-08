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

package eu.akoos.photos.presentation.folders

import eu.akoos.photos.domain.usecase.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder page's back-up progress clears itself once the back-up is over, and its countdown starts only
 * once there is something to upload. The idle event of the last run, replayed to a page that has just
 * opened, arms nothing on an empty target and does not cut short the longer wait a back-up asked for just
 * now is given for its first photo to start.
 */
class BackupTargetEndTest {

    private val photos = setOf("content://1", "content://2")

    @Test
    fun `an idle report on an opened page does not start a countdown for a later back-up`() = runTest {
        val target = MutableStateFlow<Set<String>>(emptySet())
        val end = BackupTargetEnd(this, target)
        end.onStatus(UploadStatus.Idle)
        advanceTimeBy(BACKUP_TARGET_HOLD_MS - 1_000)
        target.value = photos
        end.arm(BACKUP_TARGET_START_MS)
        advanceTimeBy(BACKUP_TARGET_HOLD_MS)
        runCurrent()

        assertEquals(photos, target.value)
        end.onStatus(UploadStatus.Uploading)
    }

    @Test
    fun `a replayed idle report does not shorten the start wait`() = runTest {
        val target = MutableStateFlow<Set<String>>(emptySet())
        val end = BackupTargetEnd(this, target)
        target.value = photos
        end.arm(BACKUP_TARGET_START_MS)
        // The last run's idle event, replayed to the page right after the back-up was asked for.
        end.onStatus(UploadStatus.Idle)

        advanceTimeBy(BACKUP_TARGET_HOLD_MS + 1_000)
        runCurrent()
        assertEquals(photos, target.value)

        advanceTimeBy(BACKUP_TARGET_START_MS)
        runCurrent()
        assertTrue(target.value.isEmpty())
    }

    @Test
    fun `a back-up whose first photo never starts is dropped after the start wait`() = runTest {
        val target = MutableStateFlow(photos)
        val end = BackupTargetEnd(this, target)
        end.arm(BACKUP_TARGET_START_MS)

        advanceTimeBy(BACKUP_TARGET_START_MS - 1)
        runCurrent()
        assertEquals(photos, target.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(target.value.isEmpty())
    }

    @Test
    fun `a photo starting keeps the target and the pipeline idling then clears it`() = runTest {
        val target = MutableStateFlow(photos)
        val end = BackupTargetEnd(this, target)
        end.arm(BACKUP_TARGET_START_MS)
        end.onStatus(UploadStatus.Uploading)
        advanceTimeBy(BACKUP_TARGET_START_MS * 2)
        runCurrent()
        assertEquals(photos, target.value)

        end.onStatus(UploadStatus.Done)
        end.onStatus(UploadStatus.Idle)
        advanceTimeBy(BACKUP_TARGET_HOLD_MS - 1)
        runCurrent()
        assertEquals(photos, target.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(target.value.isEmpty())
    }

    @Test
    fun `a photo starting within the idle hold keeps the target`() = runTest {
        val target = MutableStateFlow(photos)
        val end = BackupTargetEnd(this, target)
        end.onStatus(UploadStatus.WaitingForWifi)
        advanceTimeBy(BACKUP_TARGET_HOLD_MS / 2)
        end.onStatus(UploadStatus.Queued)
        advanceTimeBy(BACKUP_TARGET_HOLD_MS * 2)
        runCurrent()

        assertEquals(photos, target.value)
        end.onStatus(UploadStatus.Uploading)
    }
}
