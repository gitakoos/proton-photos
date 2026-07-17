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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for [UploadImageCompressor.needsStripTranscode], the gate that decides whether a
 * strip-on-upload must transcode a JPEG to actually erase metadata. The trigger set is exactly the
 * EXIF-capable-but-unwritable containers (HEIC / HEIF / AVIF): a writable container strips in place, a
 * format with no EXIF GPS or one a single JPEG frame would damage (GIF / BMP) must never be transcoded,
 * a running compressor already rebuilds a gated EXIF block so a separate transcode is moot, and a motion
 * photo is spared so its appended clip survives. No Android, no Context, no ExifInterface, no
 * Robolectric: plain JVM assertions on the inputs and a caller-supplied motion probe.
 */
class UploadStripTranscodeTest {

    private fun decide(
        mime: String,
        strip: Boolean = true,
        compress: Boolean = false,
        motion: Boolean = false,
    ) = UploadImageCompressor.needsStripTranscode(mime, strip, compress) { motion }

    // Writable containers: the in-place strip already works, so never transcode.

    @Test
    fun `jpeg with strip on and no compression does not transcode`() {
        assertFalse(decide("image/jpeg"))
        assertFalse(decide("image/jpg"))
    }

    @Test
    fun `png and webp with strip on do not transcode`() {
        assertFalse(decide("image/png"))
        assertFalse(decide("image/webp"))
    }

    // Unwritable-but-EXIF-capable containers: the strip needs a transcode.

    @Test
    fun `heic with strip on and no compression transcodes`() {
        assertTrue(decide("image/heic"))
    }

    @Test
    fun `heif and avif with strip on and no compression transcode`() {
        assertTrue(decide("image/heif"))
        assertTrue(decide("image/avif"))
    }

    @Test
    fun `heic and heif sequences transcode`() {
        assertTrue(decide("image/heic-sequence"))
        assertTrue(decide("image/heif-sequence"))
    }

    // Compression running suppresses the transcode (the compressor gates the EXIF itself).

    @Test
    fun `heic with strip on but compression running does not transcode`() {
        assertFalse(decide("image/heic", strip = true, compress = true))
    }

    // Strip off is byte-identical to today: never transcode.

    @Test
    fun `heic with strip off does not transcode`() {
        assertFalse(decide("image/heic", strip = false))
        assertFalse(decide("image/heic", strip = false, compress = true))
    }

    // A motion photo keeps its appended clip: re-encoding the primary frame would drop the motion.

    @Test
    fun `a motion photo is never transcoded`() {
        assertFalse(decide("image/heic", motion = true))
        assertFalse(decide("image/heif", motion = true))
        assertTrue(decide("image/heic", motion = false))
    }

    /**
     * The probe reads the file, so it must never run for an upload that cannot be transcoded anyway.
     * It is consulted only once the container, the strip flag, and the compress flag all say transcode.
     */
    @Test
    fun `the motion probe runs only for a container that would be transcoded`() {
        var probes = 0
        val probe = { probes++; false }

        UploadImageCompressor.needsStripTranscode("image/jpeg", true, false, probe)
        UploadImageCompressor.needsStripTranscode("image/gif", true, false, probe)
        UploadImageCompressor.needsStripTranscode("image/heic", true, true, probe)
        UploadImageCompressor.needsStripTranscode("image/heic", false, false, probe)
        assertEquals(0, probes)

        UploadImageCompressor.needsStripTranscode("image/heic", true, false, probe)
        assertEquals(1, probes)
    }

    // Formats a JPEG frame would damage or that carry no EXIF GPS: never transcode.

    @Test
    fun `gif with strip on does not transcode`() {
        assertFalse(decide("image/gif"))
    }

    @Test
    fun `bmp with strip on does not transcode`() {
        assertFalse(decide("image/bmp"))
    }

    // Video mimes are never an image strip.

    @Test
    fun `video mimes never transcode`() {
        assertFalse(decide("video/mp4"))
        assertFalse(decide("video/quicktime"))
        assertFalse(decide("video/x-matroska"))
    }

    // Case- and parameter-robustness.

    @Test
    fun `mime match is case insensitive`() {
        assertTrue(decide("image/HEIC"))
        assertTrue(decide("IMAGE/Heif"))
        assertTrue(decide("image/AVIF"))
    }

    @Test
    fun `mime with a parameter suffix still matches`() {
        assertTrue(decide("image/heic; codecs=hvc1"))
        assertTrue(decide("  image/heic  "))
    }

    @Test
    fun `blank or unknown mime does not transcode`() {
        assertFalse(decide(""))
        assertFalse(decide("application/octet-stream"))
        assertFalse(decide("image/x-adobe-dng"))
    }
}
