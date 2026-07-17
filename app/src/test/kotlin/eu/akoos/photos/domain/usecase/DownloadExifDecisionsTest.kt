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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the three download EXIF decisions extracted from [DownloadPhotosUseCase]:
 * the DATE_TAKEN timestamp a save stamps, whether the capture-date EXIF applies, and whether GPS EXIF
 * should be written. The GPS gate never fires without coordinates, which is how the privacy invariant
 * is pinned here: a photo with no known location (its cloud Location was stripped at upload) can never
 * gain an invented one. Both EXIF gates share one case- and parameter-insensitive mime test: JPEG / PNG
 * / WebP are the writable containers; HEIC / HEIF / AVIF / GIF / DNG and every video mime are not. No
 * Android, no Context, no ExifInterface, no Robolectric: plain JVM assertions on the inputs.
 */
class DownloadExifDecisionsTest {

    // downloadTimestampMs: capture time wins, else the on-disk last-modified fallback.

    @Test
    fun `capture time wins and promotes seconds to milliseconds`() {
        assertEquals(1_700_000_000_000L, DownloadPhotosUseCase.downloadTimestampMs(1_700_000_000L, 42L))
    }

    @Test
    fun `falls back to last-modified when capture time is zero`() {
        assertEquals(9_999L, DownloadPhotosUseCase.downloadTimestampMs(0L, 9_999L))
    }

    @Test
    fun `falls back to last-modified when capture time is negative`() {
        assertEquals(9_999L, DownloadPhotosUseCase.downloadTimestampMs(-5L, 9_999L))
    }

    @Test
    fun `returns non-positive when both inputs are non-positive so the caller maps it to now`() {
        assertEquals(0L, DownloadPhotosUseCase.downloadTimestampMs(0L, 0L))
        assertEquals(0L, DownloadPhotosUseCase.downloadTimestampMs(-1L, 0L))
    }

    // shouldStampExifDate: writable image mime AND a real capture time.

    @Test
    fun `stamps date on writable image mimes with a real capture time`() {
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg", 1L))
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/png", 1L))
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/webp", 1L))
    }

    @Test
    fun `does not stamp date when capture time is zero or negative`() {
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg", 0L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg", -1L))
    }

    @Test
    fun `does not stamp date on unwritable image containers`() {
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/heic", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/heif", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/avif", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/gif", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/x-adobe-dng", 1L))
    }

    @Test
    fun `does not stamp date on video mimes`() {
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("video/mp4", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("video/quicktime", 1L))
    }

    // shouldWriteExifGps: writable image mime AND coordinates present. The privacy invariant.

    @Test
    fun `writes gps on writable image mimes when coordinates exist`() {
        assertTrue(DownloadPhotosUseCase.shouldWriteExifGps("image/jpeg", true))
        assertTrue(DownloadPhotosUseCase.shouldWriteExifGps("image/png", true))
        assertTrue(DownloadPhotosUseCase.shouldWriteExifGps("image/webp", true))
    }

    @Test
    fun `never writes gps without coordinates`() {
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/jpeg", false))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/png", false))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/webp", false))
    }

    @Test
    fun `does not write gps on unwritable image containers even with coordinates`() {
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/heic", true))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/heif", true))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/avif", true))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/gif", true))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("image/x-adobe-dng", true))
    }

    @Test
    fun `does not write gps on video mimes even with coordinates`() {
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("video/mp4", true))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("video/quicktime", true))
    }

    // Mime normalisation shared by both EXIF gates.

    @Test
    fun `mime match is case insensitive`() {
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/JPEG", 1L))
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("IMAGE/Png", 1L))
        assertTrue(DownloadPhotosUseCase.shouldWriteExifGps("image/WEBP", true))
    }

    @Test
    fun `mime with a parameter suffix or surrounding space still matches`() {
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg; charset=binary", 1L))
        assertTrue(DownloadPhotosUseCase.shouldWriteExifGps("  image/png  ", true))
    }

    @Test
    fun `blank or unknown mime is never writable`() {
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("", 1L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("application/octet-stream", 1L))
        assertFalse(DownloadPhotosUseCase.shouldWriteExifGps("", true))
    }
}
