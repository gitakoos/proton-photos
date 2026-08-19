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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the delete-confirm row structure both the viewer and the multi-select sheet build from. The
 * rule these protect is the one the two sheets drifted on: a partial removal (the other copy stays)
 * is neutral, and only the row that takes the last copy is red. The multi-select "Remove from cloud"
 * row used to be hard-coded red for a photo that also lives on the device, disagreeing with the
 * viewer's own sheet for the same photo.
 */
class DeleteConfirmRowsTest {

    @Test
    fun `a both-sides selection offers three rows, only the everywhere one red`() {
        val rows = deleteConfirmRows(hasLocal = true, hasCloud = true)
        assertEquals(3, rows.size)
        assertEquals(
            listOf(DeleteRowKind.RemoveDevice, DeleteRowKind.RemoveCloud, DeleteRowKind.RemoveEverywhere),
            rows.map { it.kind },
        )
        assertEquals("exactly one destructive row", 1, rows.count { it.destructive })
        assertTrue("the everywhere row is the destructive one", rows.last().destructive)
    }

    @Test
    fun `both partial removals are neutral`() {
        val rows = deleteConfirmRows(hasLocal = true, hasCloud = true)
        val removeDevice = rows.first { it.kind == DeleteRowKind.RemoveDevice }
        val removeCloud = rows.first { it.kind == DeleteRowKind.RemoveCloud }
        // The green-cloud regression: removing just the cloud copy leaves the device copy, so it is
        // not a full delete and must not be red.
        assertEquals(false, removeDevice.destructive)
        assertEquals(false, removeCloud.destructive)
    }

    @Test
    fun `each row asks for the right delete`() {
        val rows = deleteConfirmRows(hasLocal = true, hasCloud = true)
        val removeDevice = rows.first { it.kind == DeleteRowKind.RemoveDevice }
        val removeCloud = rows.first { it.kind == DeleteRowKind.RemoveCloud }
        val everywhere = rows.first { it.kind == DeleteRowKind.RemoveEverywhere }
        assertEquals(true to false, removeDevice.freeUpSpace to removeDevice.deleteFromCloud)
        assertEquals(false to true, removeCloud.freeUpSpace to removeCloud.deleteFromCloud)
        assertEquals(true to true, everywhere.freeUpSpace to everywhere.deleteFromCloud)
    }

    @Test
    fun `a device-only selection is one red trash row`() {
        val rows = deleteConfirmRows(hasLocal = true, hasCloud = false)
        assertEquals(1, rows.size)
        assertEquals(DeleteRowKind.TrashLocal, rows.single().kind)
        assertTrue(rows.single().destructive)
        assertEquals(true to false, rows.single().freeUpSpace to rows.single().deleteFromCloud)
    }

    @Test
    fun `a cloud-only selection is one red trash row`() {
        val rows = deleteConfirmRows(hasLocal = false, hasCloud = true)
        assertEquals(1, rows.size)
        assertEquals(DeleteRowKind.TrashCloud, rows.single().kind)
        assertTrue(rows.single().destructive)
        assertEquals(false to true, rows.single().freeUpSpace to rows.single().deleteFromCloud)
    }

    @Test
    fun `an empty selection offers no rows`() {
        assertTrue(deleteConfirmRows(hasLocal = false, hasCloud = false).isEmpty())
    }
}
