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

import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.entity.CloudPhoto
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The photos compression in place leaves alone before anything is downloaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompressCloudPhotoUseCaseTest {

    private val useCase = CompressCloudPhotoUseCase(
        ApplicationProvider.getApplicationContext(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
    )

    private fun photo(mime: String = "image/jpeg", tags: Set<Int> = emptySet()) = CloudPhoto(
        linkId = "l",
        shareId = "s",
        volumeId = "v",
        captureTime = 1_600_000_000L,
        displayName = "IMG.jpg",
        mimeType = mime,
        sizeBytes = 4_000_000L,
        thumbnailUrl = null,
        revisionId = "r",
        tags = tags,
    )

    @Test
    fun `a Live Photo, Motion Photo, burst, panorama or RAW is left as it is`() = runTest {
        for (tag in listOf(3, 4, 7, 8, 9)) {
            assertEquals("tag $tag", CompressCloudPhotoUseCase.Result.Skipped, useCase(photo(tags = setOf(tag))))
        }
        assertEquals(CompressCloudPhotoUseCase.Result.Skipped, useCase(photo(mime = "image/x-adobe-dng")))
    }

    @Test
    fun `a copy replaces the original only when it is at least a tenth smaller`() {
        assertTrue(worthReplacing(1_000_000L, 250_000L))
        assertTrue(worthReplacing(1_000_000L, 900_000L))
        assertFalse(worthReplacing(1_000_000L, 900_001L))
        assertFalse(worthReplacing(10_240L, 10_173L))
        assertFalse(worthReplacing(1_000L, 1_200L))
        assertFalse(worthReplacing(1_000L, 0L))
        assertFalse(worthReplacing(0L, 0L))
    }

    @Test
    fun `a file that is neither a photo nor a video is left as it is`() = runTest {
        assertEquals(CompressCloudPhotoUseCase.Result.Skipped, useCase(photo(mime = "application/pdf")))
    }
}
