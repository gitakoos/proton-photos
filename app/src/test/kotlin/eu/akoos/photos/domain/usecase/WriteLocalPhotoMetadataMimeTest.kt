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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the mime gates in [WriteLocalPhotoMetadataUseCase] that decide the write path: a
 * video carries no EXIF (location edit refused) and only JPEG / PNG / WebP take the embedded EXIF
 * datetime leg, while an ISO base media video takes the mvhd stamp instead. Case- and
 * parameter-insensitive. No Android is instantiated.
 */
class WriteLocalPhotoMetadataMimeTest {

    @Test
    fun `video mimes are detected across case and parameters`() {
        assertTrue(WriteLocalPhotoMetadataUseCase.isVideoMime("video/mp4"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isVideoMime("video/quicktime"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isVideoMime("VIDEO/MP4"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isVideoMime("video/mp4; codecs=avc1"))
    }

    @Test
    fun `images and blanks are not videos`() {
        assertFalse(WriteLocalPhotoMetadataUseCase.isVideoMime("image/jpeg"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isVideoMime(""))
    }

    @Test
    fun `writable image containers are the jpeg png webp set`() {
        assertTrue(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/jpeg"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/png"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/webp"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("IMAGE/JPEG"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/jpeg; charset=binary"))
    }

    @Test
    fun `unwritable containers and videos are excluded from the EXIF leg`() {
        assertFalse(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/heic"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("image/gif"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime("video/mp4"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(""))
    }

    @Test
    fun `ISO base media videos take the mvhd stamp`() {
        assertTrue(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/mp4"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/quicktime"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/3gpp"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("VIDEO/MP4"))
        assertTrue(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/mp4; codecs=avc1"))
    }

    @Test
    fun `other containers keep the column-only path`() {
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/x-matroska"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/webm"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("video/avi"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("image/heic"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime("image/jpeg"))
        assertFalse(WriteLocalPhotoMetadataUseCase.isMvhdStampableMime(""))
    }

    @Test
    fun `durable date edit covers writable images and any video, not heic heif avif`() {
        // The exact predicate the metadata editor uses to keep the date field: a write durably holds on
        // an EXIF-writable image (the embedded datetime) or any video (the mvhd or the column). An
        // EXIF-unwritable image has no durable leg, so the editor locks its date rather than accepting an
        // edit the next scan reverts.
        fun takesDurableDateEdit(mime: String) =
            WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(mime) ||
                WriteLocalPhotoMetadataUseCase.isVideoMime(mime)

        assertTrue(takesDurableDateEdit("image/jpeg"))
        assertTrue(takesDurableDateEdit("image/png"))
        assertTrue(takesDurableDateEdit("image/webp"))
        assertTrue(takesDurableDateEdit("video/mp4"))
        assertFalse(takesDurableDateEdit("image/heic"))
        assertFalse(takesDurableDateEdit("image/heif"))
        assertFalse(takesDurableDateEdit("image/avif"))
    }
}
