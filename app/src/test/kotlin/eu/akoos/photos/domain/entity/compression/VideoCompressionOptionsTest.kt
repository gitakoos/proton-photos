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

package eu.akoos.photos.domain.entity.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionOptionsTest {

    @Test
    fun the_persisted_codec_keys_never_change() {
        // These strings are on users' devices; changing one silently resets their setting.
        assertEquals(listOf("auto", "avc", "hevc", "av1", "keep"), VideoCodecChoice.entries.map { it.key })
        VideoCodecChoice.entries.forEach { assertEquals(it, VideoCodecChoice.fromKey(it.key)) }
        assertNull(VideoCodecChoice.fromKey("vp9"))
        assertNull(VideoCodecChoice.fromKey(null))
    }

    @Test
    fun codec_from_mime_tolerates_case_and_parameters() {
        assertEquals(VideoCodec.HEVC, VideoCodec.fromMime("Video/HEVC; profile=main10"))
        assertEquals(VideoCodec.AV1, VideoCodec.fromMime("video/av01"))
        assertNull(VideoCodec.fromMime("video/mp4v-es"))
        assertNull(VideoCodec.fromMime(null))
    }

    @Test
    fun encoder_fit_accepts_either_orientation_and_finds_the_largest_short_side() {
        val capped = EncoderInfo("hw.1080", hardware = true, maxWidth = 1920, maxHeight = 1080)
        assertTrue(capped.fits(1920, 1080))
        assertFalse(capped.fits(2560, 1440))
        assertEquals(1080, capped.largestFittingShortSide(3840, 2160))
        val tiny = EncoderInfo("sw.512", hardware = false, maxWidth = 512, maxHeight = 512)
        assertEquals(288, tiny.largestFittingShortSide(1920, 1080))
    }
}
