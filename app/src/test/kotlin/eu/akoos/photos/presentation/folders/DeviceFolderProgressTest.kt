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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFolderProgressTest {

    @Test
    fun `an idle folder reports nothing`() {
        assertNull(
            DeviceFolderProgress.operation(
                vaultRunning = false, vaultRestoring = false, backupRunning = false,
            ),
        )
    }

    @Test
    fun `a back-up alone is the back-up`() {
        assertEquals(
            DeviceFolderOperation.BackingUp,
            DeviceFolderProgress.operation(
                vaultRunning = false, vaultRestoring = false, backupRunning = true,
            ),
        )
    }

    @Test
    fun `a hide alone is the hide`() {
        assertEquals(
            DeviceFolderOperation.Hiding,
            DeviceFolderProgress.operation(
                vaultRunning = true, vaultRestoring = false, backupRunning = false,
            ),
        )
    }

    @Test
    fun `a restore alone is the restore`() {
        assertEquals(
            DeviceFolderOperation.Restoring,
            DeviceFolderProgress.operation(
                vaultRunning = true, vaultRestoring = true, backupRunning = false,
            ),
        )
    }

    @Test
    fun `a hide running beside a back-up is the one on show`() {
        assertEquals(
            DeviceFolderOperation.Hiding,
            DeviceFolderProgress.operation(
                vaultRunning = true, vaultRestoring = false, backupRunning = true,
            ),
        )
    }

    @Test
    fun `a restore running beside a back-up is the one on show`() {
        assertEquals(
            DeviceFolderOperation.Restoring,
            DeviceFolderProgress.operation(
                vaultRunning = true, vaultRestoring = true, backupRunning = true,
            ),
        )
    }

    @Test
    fun `the restoring direction only counts while the vault runs`() {
        assertNull(
            DeviceFolderProgress.operation(
                vaultRunning = false, vaultRestoring = true, backupRunning = false,
            ),
        )
        assertEquals(
            DeviceFolderOperation.BackingUp,
            DeviceFolderProgress.operation(
                vaultRunning = false, vaultRestoring = true, backupRunning = true,
            ),
        )
    }
}
