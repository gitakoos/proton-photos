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

package eu.akoos.photos.data.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for [UploadImageCompressor.canOverwriteLocalWithCompressedJpeg], the guard that
 * decides whether the mirror-compress option may replace the photo on the device. The compressor only
 * ever encodes JPEG, so replacing any other container leaves a file whose bytes contradict its name and
 * its MediaStore mime. Only a JPEG original may be replaced; everything else keeps its bytes untouched.
 * No Android, no Context: plain JVM assertions on the mime string.
 */
class MirrorCompressTargetTest {

    @Test
    fun `a jpeg original may be replaced with the compressed copy`() {
        assertTrue(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/jpeg"))
        assertTrue(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/jpg"))
    }

    @Test
    fun `a png original is never replaced with jpeg bytes`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/png"))
    }

    @Test
    fun `a webp original is never replaced with jpeg bytes`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/webp"))
    }

    @Test
    fun `a heic original is never replaced with jpeg bytes`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/heic"))
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/heif"))
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/avif"))
    }

    @Test
    fun `a gif or bmp original is never replaced with jpeg bytes`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/gif"))
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/bmp"))
    }

    @Test
    fun `a video is never a mirror-compress target for the image compressor`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("video/mp4"))
    }

    @Test
    fun `the mime match tolerates case and a parameter suffix`() {
        assertTrue(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("IMAGE/JPEG"))
        assertTrue(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("  image/jpeg  "))
        assertTrue(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("image/jpeg; charset=binary"))
    }

    @Test
    fun `a blank or unknown mime is never replaced`() {
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg(""))
        assertFalse(UploadImageCompressor.canOverwriteLocalWithCompressedJpeg("application/octet-stream"))
    }
}
