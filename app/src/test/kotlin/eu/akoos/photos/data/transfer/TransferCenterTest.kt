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

package eu.akoos.photos.data.transfer

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Activity screen's live rows, and what its cancel button does to them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferCenterTest {

    private val center = TransferCenter(ApplicationProvider.getApplicationContext())

    @Test
    fun `a batch that stops at once leaves the list on cancel`() {
        var stopped = false
        val id = center.start(TransferCenter.Kind.DOWNLOAD, total = 5, onCancel = { stopped = true })

        center.cancel(id)

        assertTrue(stopped)
        assertTrue(center.active.value.none { it.id == id })
    }

    @Test
    fun `a batch that finishes its running items stays, without a cancel, until it finishes`() {
        var stopped = false
        val id = center.start(
            TransferCenter.Kind.UPLOAD,
            total = 20,
            onCancel = { stopped = true },
            cancelFinishesRunning = true,
        )
        center.progress(id, 8)

        center.cancel(id)

        assertTrue(stopped)
        val row = center.active.value.single { it.id == id }
        assertFalse(row.cancelable)
        center.progress(id, 10)
        assertEquals(10, center.active.value.single { it.id == id }.done)

        center.finish(id)
        assertTrue(center.active.value.none { it.id == id })
    }
}
