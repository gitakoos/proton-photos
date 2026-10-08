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

package eu.akoos.photos.presentation.settings.compression

import eu.akoos.photos.data.upload.compression.FakeEncoderCapabilities
import eu.akoos.photos.data.upload.compression.without
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecOptionsTest {

    private val flagship = FakeEncoderCapabilities.flagship()
    private val emulator = FakeEncoderCapabilities.emulator()

    @Test
    fun a_flagship_offers_every_codec_in_hardware_and_auto_means_hevc() {
        val opts = CodecOptions.options(flagship).associateBy { it.choice }
        assertEquals(VideoCodec.HEVC, opts.getValue(VideoCodecChoice.AUTO).resolves)
        assertEquals(CodecBadge.HARDWARE, opts.getValue(VideoCodecChoice.HEVC).badge)
        assertEquals(CodecBadge.HARDWARE, opts.getValue(VideoCodecChoice.AV1).badge)
        assertTrue(opts.values.all { it.selectable })
    }

    @Test
    fun the_emulator_greys_out_software_only_hevc_and_av1_with_the_reason() {
        val opts = CodecOptions.options(emulator).associateBy { it.choice }
        assertEquals(VideoCodec.AVC, opts.getValue(VideoCodecChoice.AUTO).resolves)
        listOf(VideoCodecChoice.HEVC, VideoCodecChoice.AV1).forEach { choice ->
            val option = opts.getValue(choice)
            assertFalse(option.selectable)
            assertEquals(UnavailableReason.NEEDS_HARDWARE, option.reason)
        }
    }

    @Test
    fun a_missing_codec_is_unavailable_as_not_supported() {
        val noAv1 = emulator.without(VideoCodec.AV1)
        val av1 = CodecOptions.option(noAv1, VideoCodecChoice.AV1)
        assertEquals(CodecBadge.UNAVAILABLE, av1.badge)
        assertEquals(UnavailableReason.NOT_SUPPORTED, av1.reason)
    }

    @Test
    fun options_keep_their_display_order() {
        assertEquals(
            listOf(VideoCodecChoice.AUTO, VideoCodecChoice.KEEP_SOURCE, VideoCodecChoice.AVC, VideoCodecChoice.HEVC, VideoCodecChoice.AV1),
            CodecOptions.options(flagship).map { it.choice },
        )
    }

    @Test
    fun an_unavailable_choice_estimates_with_its_fallback() {
        assertEquals(VideoCodec.AVC, CodecOptions.effectiveCodec(emulator, VideoCodecChoice.HEVC))
        assertEquals(VideoCodec.AVC, CodecOptions.effectiveCodec(emulator, VideoCodecChoice.AV1))
        val noAv1 = flagship.without(VideoCodec.AV1)
        assertEquals(VideoCodec.HEVC, CodecOptions.effectiveCodec(noAv1, VideoCodecChoice.AV1))
    }

    @Test
    fun the_per_minute_estimate_follows_codec_tier_and_caps() {
        val balancedHevc = CodecOptions.perMinute(flagship, VideoCompressionProfile(codec = VideoCodecChoice.AUTO))!!
        assertEquals(1080, balancedHevc.shortSide)
        // About 3.5 Mbps video plus 128 kbps audio is roughly 27 MB a minute.
        assertEquals(27.2, balancedHevc.bytes / 1_000_000.0, 0.5)
        val small = CodecOptions.perMinute(flagship, VideoCompressionProfile(tier = UploadCompressionTier.SPACE_SAVER))!!
        assertEquals(720, small.shortSide)
        assertTrue(small.bytes < balancedHevc.bytes)
        val avcLight = CodecOptions.perMinute(flagship, VideoCompressionProfile(VideoCodecChoice.AVC, UploadCompressionTier.LIGHT))!!
        assertTrue(avcLight.bytes > balancedHevc.bytes)
    }
}
