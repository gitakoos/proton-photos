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

package eu.akoos.photos.presentation.lock

import eu.akoos.photos.data.localcrypto.SettingsHold
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * While the settings are held back the stored lock flag may be the empty file's default, not the user's
 * choice, so the app lock is on whatever the file says.
 */
class AppLockHeldBackTest {

    private val off = AppLockSettings(enabled = false, timeoutMs = 60_000L)

    @Test
    fun `settings read as they are keep the stored lock`() {
        assertEquals(off, heldBackLockSettings(off, SettingsHold.NONE))
    }

    @Test
    fun `settings held back for either reason turn the lock on`() {
        assertEquals(off.copy(enabled = true), heldBackLockSettings(off, SettingsHold.SET_ASIDE))
        assertEquals(off.copy(enabled = true), heldBackLockSettings(off, SettingsHold.UNREADABLE))
    }
}
