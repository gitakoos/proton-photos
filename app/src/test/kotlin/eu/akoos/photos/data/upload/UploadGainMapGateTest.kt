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
 * Pure-value coverage for the two Ultra HDR gates in [UploadImageCompressor]:
 * [UploadImageCompressor.skipsCompressionForGainMap], which keeps the compressor's re-encode from
 * dropping an appended gain map, and [UploadImageCompressor.attemptsGainMapPreservingStrip], which
 * decides whether the strip may try the gain-map-preserving route at all.
 *
 * Both are value decisions over a mime, the two upload options, an SDK level, and a caller-supplied
 * probe, so the whole branch matrix is pinned here with no Android, no Context, and no real file. The
 * API floor is the safety property worth the most: the preserving route rebuilds the file and may only
 * ship bytes the platform decoder has confirmed, so below the API that can confirm them it must never
 * be attempted.
 */
class UploadGainMapGateTest {

    private companion object {
        /** First SDK level exposing `Bitmap.hasGainmap`, the only way to verify a rebuilt file. */
        const val SDK_WITH_GAIN_MAP_QUERY = 34
        const val SDK_BELOW_GAIN_MAP_QUERY = 33
    }

    private fun skipsCompression(
        mime: String = "image/jpeg",
        compress: Boolean = true,
        gainMap: Boolean = true,
    ) = UploadImageCompressor.skipsCompressionForGainMap(mime, compress) { gainMap }

    private fun attemptsStrip(
        mime: String = "image/jpeg",
        strip: Boolean = true,
        sdkInt: Int = SDK_WITH_GAIN_MAP_QUERY,
        gainMap: Boolean = true,
    ) = UploadImageCompressor.attemptsGainMapPreservingStrip(mime, strip, sdkInt) { gainMap }

    // Compression gate: an Ultra HDR still is never re-encoded.

    @Test
    fun `a gain map with compression on skips compression`() {
        assertTrue(skipsCompression())
        assertTrue(skipsCompression(mime = "image/jpg"))
    }

    @Test
    fun `a gain map with compression off does not skip anything`() {
        assertFalse(skipsCompression(compress = false))
    }

    @Test
    fun `an ordinary still without a gain map still compresses`() {
        assertFalse(skipsCompression(gainMap = false))
        assertFalse(skipsCompression(mime = "image/heic", gainMap = false))
        assertFalse(skipsCompression(mime = "image/png", gainMap = false))
    }

    @Test
    fun `a non-image mime never skips compression`() {
        assertFalse(skipsCompression(mime = "video/mp4"))
        assertFalse(skipsCompression(mime = "application/octet-stream"))
        assertFalse(skipsCompression(mime = ""))
    }

    /**
     * A motion photo has its own compression skip and its own strip route. Neither gain-map gate may
     * claim one on its behalf, so with no gain map both stay false and the motion handling is reached
     * exactly as it is.
     */
    @Test
    fun `a motion photo without a gain map is left to the motion path`() {
        assertFalse(skipsCompression(gainMap = false))
        assertFalse(attemptsStrip(gainMap = false))
        assertFalse(UploadImageCompressor.needsStripTranscode("image/heic", true, false) { true })
    }

    // Strip gate: the preserving route needs a platform that can verify the rebuilt file.

    @Test
    fun `strip on with a gain map on a verifying platform attempts the preserving route`() {
        assertTrue(attemptsStrip())
    }

    @Test
    fun `a platform below the gain-map query never attempts the preserving route`() {
        assertFalse(attemptsStrip(sdkInt = SDK_BELOW_GAIN_MAP_QUERY))
        assertFalse(attemptsStrip(sdkInt = 26))
        assertTrue(attemptsStrip(sdkInt = SDK_WITH_GAIN_MAP_QUERY + 1))
    }

    @Test
    fun `strip off never attempts the preserving route`() {
        assertFalse(attemptsStrip(strip = false))
        assertFalse(attemptsStrip(strip = false, sdkInt = SDK_WITH_GAIN_MAP_QUERY + 1))
    }

    @Test
    fun `no gain map never attempts the preserving route`() {
        assertFalse(attemptsStrip(gainMap = false))
    }

    @Test
    fun `a non-image mime never attempts the preserving route`() {
        assertFalse(attemptsStrip(mime = "video/mp4"))
        assertFalse(attemptsStrip(mime = ""))
    }

    /**
     * The probe reads the file's header, so it must never run for an upload the cheap conditions
     * already decide. Both gates consult it only once the option and the mime (and, for the strip, the
     * SDK level) all point at the branch.
     */
    @Test
    fun `neither gate probes when the cheap conditions already decide`() {
        var probes = 0
        val probe = { probes++; true }

        UploadImageCompressor.skipsCompressionForGainMap("image/jpeg", false, probe)
        UploadImageCompressor.skipsCompressionForGainMap("video/mp4", true, probe)
        UploadImageCompressor.attemptsGainMapPreservingStrip("image/jpeg", false, SDK_WITH_GAIN_MAP_QUERY, probe)
        UploadImageCompressor.attemptsGainMapPreservingStrip("image/jpeg", true, SDK_BELOW_GAIN_MAP_QUERY, probe)
        UploadImageCompressor.attemptsGainMapPreservingStrip("video/mp4", true, SDK_WITH_GAIN_MAP_QUERY, probe)
        assertEquals(0, probes)

        UploadImageCompressor.skipsCompressionForGainMap("image/jpeg", true, probe)
        assertEquals(1, probes)
        UploadImageCompressor.attemptsGainMapPreservingStrip("image/jpeg", true, SDK_WITH_GAIN_MAP_QUERY, probe)
        assertEquals(2, probes)
    }

    // Case- and parameter-robustness, matching the sibling gates.

    @Test
    fun `the mime match tolerates case and a parameter suffix`() {
        assertTrue(skipsCompression(mime = "IMAGE/JPEG"))
        assertTrue(skipsCompression(mime = "  image/jpeg  "))
        assertTrue(attemptsStrip(mime = "image/jpeg; charset=binary"))
        assertTrue(attemptsStrip(mime = "Image/Jpeg"))
    }
}
