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

package eu.akoos.photos.data.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * After a failed SDK upload only a photo that was not on Drive before can be the one it made. A photo
 * already there with the same name and content (the original a metadata correction re-uploads) must never
 * be taken for it, or the correction would trash the original as if it were the old copy.
 */
class SdkUploadAfterFailureTest {

    private val original = SdkUploadResult("original", "rev-1")
    private val fresh = SdkUploadResult("fresh", "rev-2")

    @Test
    fun `the original that was already there is not taken for the upload`() {
        assertEquals(
            SdkUploadAfterFailure.NothingCommitted,
            sdkUploadAfterFailure(before = listOf(original), after = listOf(original)),
        )
    }

    @Test
    fun `a photo that appeared during the upload is the one it made`() {
        assertEquals(
            SdkUploadAfterFailure.Committed(fresh),
            sdkUploadAfterFailure(before = listOf(original), after = listOf(original, fresh)),
        )
        assertEquals(SdkUploadAfterFailure.Committed(fresh), sdkUploadAfterFailure(before = emptyList(), after = listOf(fresh)))
    }

    @Test
    fun `nothing on Drive before or after lets the app's own upload run`() {
        assertEquals(SdkUploadAfterFailure.NothingCommitted, sdkUploadAfterFailure(before = emptyList(), after = emptyList()))
        assertEquals(SdkUploadAfterFailure.NothingCommitted, sdkUploadAfterFailure(before = null, after = emptyList()))
    }

    @Test
    fun `an answer that cannot be read is unknown, never a second upload`() {
        assertEquals(SdkUploadAfterFailure.Unknown, sdkUploadAfterFailure(before = listOf(original), after = null))
        assertEquals(SdkUploadAfterFailure.Unknown, sdkUploadAfterFailure(before = null, after = listOf(original)))
        assertEquals(
            SdkUploadAfterFailure.Unknown,
            sdkUploadAfterFailure(before = emptyList(), after = listOf(original, fresh)),
        )
    }
}
