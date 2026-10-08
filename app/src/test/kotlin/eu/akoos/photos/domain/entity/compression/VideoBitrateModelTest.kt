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

import eu.akoos.photos.domain.entity.UploadCompressionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBitrateModelTest {

    private fun target(codec: VideoCodec, tier: UploadCompressionTier, width: Int = 1920, height: Int = 1080) =
        VideoBitrateModel.targetBitrate(codec, tier, width, height, 30f, keepsHdr = false)

    @Test
    fun the_table_gives_the_documented_bitrates_at_the_reference_sizes() {
        fun mbps(codec: VideoCodec, tier: UploadCompressionTier) = target(codec, tier) / 1_000_000.0
        assertEquals(8.1, mbps(VideoCodec.AVC, UploadCompressionTier.LIGHT), 0.1)
        assertEquals(5.0, mbps(VideoCodec.AVC, UploadCompressionTier.BALANCED), 0.1)
        assertEquals(5.5, mbps(VideoCodec.HEVC, UploadCompressionTier.LIGHT), 0.1)
        assertEquals(3.5, mbps(VideoCodec.HEVC, UploadCompressionTier.BALANCED), 0.1)
        assertEquals(4.5, mbps(VideoCodec.AV1, UploadCompressionTier.LIGHT), 0.1)
        assertEquals(3.0, mbps(VideoCodec.AV1, UploadCompressionTier.BALANCED), 0.1)
        assertEquals(2.5, target(VideoCodec.AVC, UploadCompressionTier.SPACE_SAVER, 1280, 720) / 1_000_000.0, 0.1)
    }

    @Test
    fun tiers_and_codecs_stay_in_order_at_every_size() {
        val sizes = listOf(320 to 240, 640 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        VideoCodec.entries.forEach { codec ->
            sizes.forEach { (w, h) ->
                fun t(tier: UploadCompressionTier) = target(codec, tier, w, h)
                assertTrue("$codec ${w}x$h", t(UploadCompressionTier.SPACE_SAVER) < t(UploadCompressionTier.BALANCED))
                assertTrue("$codec ${w}x$h", t(UploadCompressionTier.BALANCED) < t(UploadCompressionTier.LIGHT))
            }
        }
        UploadCompressionTier.entries.forEach { tier ->
            assertTrue(target(VideoCodec.AV1, tier) < target(VideoCodec.HEVC, tier))
            assertTrue(target(VideoCodec.HEVC, tier) < target(VideoCodec.AVC, tier))
        }
    }

    @Test
    fun smaller_frames_get_more_bits_per_pixel() {
        val p480 = target(VideoCodec.AVC, UploadCompressionTier.BALANCED, 640, 480)
        val p1080 = target(VideoCodec.AVC, UploadCompressionTier.BALANCED)
        assertTrue(p480.toDouble() / (640 * 480) > p1080.toDouble() / (1920 * 1080))
    }

    @Test
    fun the_size_based_estimate_subtracts_the_audio() {
        // 12 MB over 12 s is 8 Mbps in total.
        assertEquals(7_872_000, VideoBitrateModel.estimateVideoBitrate(12_000_000L, 12_000L, 128_000))
        assertEquals(8_000_000, VideoBitrateModel.estimateVideoBitrate(12_000_000L, 12_000L, 0))
        assertNull(VideoBitrateModel.estimateVideoBitrate(0L, 12_000L, 128_000))
        assertNull(VideoBitrateModel.estimateVideoBitrate(1_000L, 12_000L, 128_000))
    }

    @Test
    fun bytes_per_minute_includes_the_audio() {
        assertEquals(37_500_000L, VideoBitrateModel.bytesPerMinute(4_872_000))
    }
}
