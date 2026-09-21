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

package eu.akoos.photos.data.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceScanNetworkResumeTest {

    @Test
    fun `resumes on the off-to-unmetered edge`() {
        assertTrue(shouldResumeFaceScanOnUnmetered(wasUnmetered = false, nowUnmetered = true))
    }

    @Test
    fun `does not kick when already unmetered`() {
        // The StateFlow replays its current value first; this must not fire a redundant scan at startup.
        assertFalse(shouldResumeFaceScanOnUnmetered(wasUnmetered = true, nowUnmetered = true))
    }

    @Test
    fun `does not kick while still metered`() {
        assertFalse(shouldResumeFaceScanOnUnmetered(wasUnmetered = false, nowUnmetered = false))
    }

    @Test
    fun `does not kick when the connection drops to metered`() {
        assertFalse(shouldResumeFaceScanOnUnmetered(wasUnmetered = true, nowUnmetered = false))
    }
}
