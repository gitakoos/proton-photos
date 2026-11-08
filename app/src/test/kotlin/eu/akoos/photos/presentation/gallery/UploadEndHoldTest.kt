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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.usecase.UploadProgress
import eu.akoos.photos.domain.usecase.UploadStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The back-up pill stays put between two photos. The pipeline reports Done after each photo and then the
 * next one starting, so an end is applied only once nothing has started for [UPLOAD_END_HOLD_MS]; a start
 * within it drops the pending end, and the pill never blinks off and back on.
 */
class UploadEndHoldTest {

    private fun evt(status: UploadStatus, done: Int = 0, total: Int = 3) =
        UploadProgress(uri = "content://1", displayName = "photo.jpg", status = status, doneIdx = done, totalCount = total)

    private fun TestScope.hold(applied: MutableList<Boolean>) =
        UploadEndHold(this, UPLOAD_END_HOLD_MS) { syncing, _ -> applied += syncing }

    @Test
    fun `a start is shown at once`() = runTest {
        val applied = mutableListOf<Boolean>()
        hold(applied).onProgress(true, evt(UploadStatus.Uploading))
        assertEquals(listOf(true), applied)
    }

    @Test
    fun `the done between two photos does not take the pill away`() = runTest {
        val applied = mutableListOf<Boolean>()
        val hold = hold(applied)
        hold.onProgress(true, evt(UploadStatus.Uploading, done = 0))
        hold.onProgress(false, evt(UploadStatus.Done, done = 1))
        advanceTimeBy(UPLOAD_END_HOLD_MS - 500)
        hold.onProgress(true, evt(UploadStatus.Encrypting, done = 1))
        advanceTimeBy(UPLOAD_END_HOLD_MS * 4)
        runCurrent()

        assertEquals(listOf(true, true), applied)
    }

    @Test
    fun `the end is applied once nothing has started for the hold`() = runTest {
        val applied = mutableListOf<Boolean>()
        val hold = hold(applied)
        hold.onProgress(true, evt(UploadStatus.Uploading))
        hold.onProgress(false, evt(UploadStatus.Done))
        advanceTimeBy(UPLOAD_END_HOLD_MS - 1)
        runCurrent()
        assertEquals(listOf(true), applied)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `further end reports do not push the end back or apply it twice`() = runTest {
        val applied = mutableListOf<Boolean>()
        val hold = hold(applied)
        hold.onProgress(true, evt(UploadStatus.Uploading))
        hold.onProgress(false, evt(UploadStatus.Done))
        advanceTimeBy(UPLOAD_END_HOLD_MS - 100)
        hold.onProgress(false, evt(UploadStatus.Idle))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(true, false), applied)

        advanceTimeBy(UPLOAD_END_HOLD_MS * 2)
        runCurrent()
        assertEquals(listOf(true, false), applied)
    }
}
