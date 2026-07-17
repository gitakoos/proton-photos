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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the safety behaviour of the downloads-only name+date re-pair gate. It exists so a downloaded
 * file whose bytes were rewritten (date/GPS) still re-pairs to its cloud twin after a reinstall
 * instead of re-uploading a duplicate, while never mislabelling a genuine local as backed up.
 */
class ReconcileRepairTwinTest {

    @Test
    fun `rescues an unclaimed name+date twin when nothing stronger settled it`() {
        assertEquals(
            "cloudX",
            repairTwinLinkId(
                strong = false,
                hasLegacyNameMatch = false,
                nameDateTwinLinkId = "cloudX",
                claimedTwinLinkIds = emptySet(),
            ),
        )
    }

    @Test
    fun `refuses when a direct id or content-hash match already settled it`() {
        assertNull(
            repairTwinLinkId(
                strong = true,
                hasLegacyNameMatch = false,
                nameDateTwinLinkId = "cloudX",
                claimedTwinLinkIds = emptySet(),
            ),
        )
    }

    @Test
    fun `refuses when the legacy name matcher already applied`() {
        assertNull(
            repairTwinLinkId(
                strong = false,
                hasLegacyNameMatch = true,
                nameDateTwinLinkId = "cloudX",
                claimedTwinLinkIds = emptySet(),
            ),
        )
    }

    @Test
    fun `refuses a twin already claimed by another local this install`() {
        assertNull(
            repairTwinLinkId(
                strong = false,
                hasLegacyNameMatch = false,
                nameDateTwinLinkId = "cloudX",
                claimedTwinLinkIds = setOf("cloudX"),
            ),
        )
    }

    @Test
    fun `no twin means no re-pair`() {
        assertNull(
            repairTwinLinkId(
                strong = false,
                hasLegacyNameMatch = false,
                nameDateTwinLinkId = null,
                claimedTwinLinkIds = emptySet(),
            ),
        )
    }

    @Test
    fun `an unrelated claim does not block the rescue`() {
        assertEquals(
            "cloudX",
            repairTwinLinkId(
                strong = false,
                hasLegacyNameMatch = false,
                nameDateTwinLinkId = "cloudX",
                claimedTwinLinkIds = setOf("cloudY"),
            ),
        )
    }
}
