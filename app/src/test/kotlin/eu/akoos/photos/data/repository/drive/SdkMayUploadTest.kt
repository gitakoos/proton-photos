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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive SDK files a photo with no capture time under the upload moment, so such a photo, and one dated
 * before 1970, uploads through the app's own path, which keeps its date; so does one whose own upload left
 * a draft on Drive.
 */
class SdkMayUploadTest {

    @Test
    fun `a dated photo with no pending draft may go through the SDK`() {
        assertTrue(sdkMayUpload(dateTakenMs = 1_700_000_000_000, ownDraftPending = false))
    }

    @Test
    fun `a photo with no date or one before 1970 keeps the app's own path`() {
        assertFalse(sdkMayUpload(dateTakenMs = 0, ownDraftPending = false))
        assertFalse(sdkMayUpload(dateTakenMs = -157_766_400_000, ownDraftPending = false))
    }

    @Test
    fun `a pending draft of the app's own keeps the app's own path`() {
        assertFalse(sdkMayUpload(dateTakenMs = 1_700_000_000_000, ownDraftPending = true))
    }
}
