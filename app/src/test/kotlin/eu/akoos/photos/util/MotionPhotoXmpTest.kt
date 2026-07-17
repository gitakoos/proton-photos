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

package eu.akoos.photos.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pins [MotionPhotoUtil.hasMotionXmp], the cheap prefix screen the photo editor uses to recognise a
 * Motion Photo from a content URI before deciding to overwrite it. All three XMP encodings must be
 * recognised, and a plain image must not be a false positive.
 */
class MotionPhotoXmpTest {

    private fun streamWith(marker: String) = ByteArrayInputStream(
        // Some leading binary bytes then the XMP marker, as it sits inside the JPEG's APP1 segment.
        (byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
            "<x:xmpmeta $marker >".toByteArray(Charsets.ISO_8859_1)),
    )

    @Test
    fun modern_camera_motion_photo_flag_is_recognised() {
        assertTrue(MotionPhotoUtil.hasMotionXmp(streamWith("Camera:MotionPhoto=\"1\"")))
    }

    @Test
    fun legacy_gcamera_microvideo_flag_is_recognised() {
        assertTrue(MotionPhotoUtil.hasMotionXmp(streamWith("GCamera:MicroVideo=\"1\"")))
    }

    @Test
    fun container_semantic_motion_photo_is_recognised() {
        assertTrue(MotionPhotoUtil.hasMotionXmp(streamWith("Item:Semantic=\"MotionPhoto\"")))
    }

    @Test
    fun a_plain_image_without_the_flag_is_not_a_motion_photo() {
        assertFalse(MotionPhotoUtil.hasMotionXmp(streamWith("dc:description=\"just a normal photo\"")))
    }

    @Test
    fun an_empty_stream_is_not_a_motion_photo() {
        assertFalse(MotionPhotoUtil.hasMotionXmp(ByteArrayInputStream(ByteArray(0))))
    }
}
