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

package eu.akoos.photos.presentation.settings

import eu.akoos.photos.data.sharedbackup.DriveCopiesMemory
import eu.akoos.photos.data.sharedbackup.DriveDeviceCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the list of the backups on Drive shows after a read, and the list kept for the next open. */
class DriveCopiesReadTest {

    private val phone = DriveDeviceCopies("phone", "Phone", 2_000L, listOf("a"))
    private val pc = DriveDeviceCopies("pc", "PC", 1_000L, listOf("b"))

    @Test
    fun a_fresh_list_wins_over_the_kept_one() {
        assertEquals(
            SharedBackupViewModel.Copies.Loaded("own", listOf(phone, pc)),
            copiesAfterRead(kept = listOf(phone), fresh = listOf(phone, pc), ownDeviceId = "own"),
        )
    }

    @Test
    fun a_read_that_failed_or_timed_out_keeps_the_list_read_before_without_the_spinner() {
        assertEquals(
            SharedBackupViewModel.Copies.Loaded("own", listOf(phone)),
            copiesAfterRead(kept = listOf(phone), fresh = null, ownDeviceId = "own"),
        )
    }

    @Test
    fun a_read_that_failed_with_nothing_kept_ends_in_the_failure() {
        assertEquals(SharedBackupViewModel.Copies.Failed, copiesAfterRead(kept = null, fresh = null, ownDeviceId = "own"))
    }

    @Test
    fun a_trashed_device_leaves_the_kept_list() {
        val memory = DriveCopiesMemory()
        memory.put("user", listOf(phone, pc))
        memory.drop("user", "pc")
        assertEquals(listOf(phone), memory.get("user"))
        assertNull(memory.get("someone else"))
    }
}
