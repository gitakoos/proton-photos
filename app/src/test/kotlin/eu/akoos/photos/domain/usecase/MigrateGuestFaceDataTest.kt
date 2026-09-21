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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrateGuestFaceDataTest {

    @Test
    fun `no guest data is a no-op regardless of the account`() {
        assertEquals(GuestFaceMigration.NONE, guestFaceMigrationAction(hasGuestData = false, accountHasData = false))
        assertEquals(GuestFaceMigration.NONE, guestFaceMigrationAction(hasGuestData = false, accountHasData = true))
    }

    @Test
    fun `guest data into a fresh account is adopted`() {
        // The owner's scenario: scan + name people as a guest, then sign into a new account.
        assertEquals(GuestFaceMigration.ADOPT, guestFaceMigrationAction(hasGuestData = true, accountHasData = false))
    }

    @Test
    fun `guest data into an account that already has faces is discarded`() {
        // The face table is single-row-per-photo, so the two cannot merge; drop the guest rows so no
        // stale biometric vectors linger under the signed-in session.
        assertEquals(GuestFaceMigration.DISCARD, guestFaceMigrationAction(hasGuestData = true, accountHasData = true))
    }
}
