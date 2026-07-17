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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decisions behind a downloaded photo's date (#34). The key rule: DATE_TAKEN is written
 * ONLY when the capture date is actually known. When it is unknown the column must be LEFT UNSET so the
 * media scanner reads the file's embedded EXIF / mvhd date, instead of being forced to the download
 * moment, which stock AOSP (Pixel / GrapheneOS) treats as authoritative and shows as "today".
 */
class DownloadDateDecisionTest {

    @Test
    fun date_taken_column_is_omitted_when_the_capture_time_is_unknown() {
        assertNull("no known capture time must leave DATE_TAKEN unset", DownloadPhotosUseCase.dateTakenColumnMs(0L))
        assertNull("a negative capture time is also unknown", DownloadPhotosUseCase.dateTakenColumnMs(-5L))
    }

    @Test
    fun date_taken_column_is_the_capture_time_in_millis_when_known() {
        assertEquals(1_700_000_000_000L, DownloadPhotosUseCase.dateTakenColumnMs(1_700_000_000L))
    }

    @Test
    fun date_modified_falls_back_to_the_file_time_only_when_the_capture_time_is_unknown() {
        assertEquals(
            "a known capture time wins",
            1_700_000_000_000L,
            DownloadPhotosUseCase.downloadTimestampMs(1_700_000_000L, 999L),
        )
        assertEquals(
            "an unknown capture time falls back to the file's last-modified time",
            999L,
            DownloadPhotosUseCase.downloadTimestampMs(0L, 999L),
        )
    }

    @Test
    fun exif_date_stamp_applies_only_to_a_writable_image_with_a_known_date() {
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg", 100L))
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/png", 100L))
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/webp", 100L))
        // HEIC and other containers ExifInterface cannot write get no stamp, so they lean on the
        // embedded date the scanner reads (which is why the column must not override it).
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/heic", 100L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/heif", 100L))
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("video/mp4", 100L))
        // Case and parameters normalise to the base type.
        assertTrue(DownloadPhotosUseCase.shouldStampExifDate("image/JPEG; codecs=x", 100L))
        // No known date means no stamp regardless of format.
        assertFalse(DownloadPhotosUseCase.shouldStampExifDate("image/jpeg", 0L))
    }
}
