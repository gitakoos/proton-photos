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

package eu.akoos.photos.data.upload.compression

import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.CompressionConditions
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.EncoderInfo
import eu.akoos.photos.domain.entity.compression.HdrHandling
import eu.akoos.photos.domain.entity.compression.HdrTransfer
import eu.akoos.photos.domain.entity.compression.VideoBitrateModel
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionPlannerTest {

    /** A twelve-second 1080p30 H.264 camera clip at 16 Mbps with AAC audio. */
    private fun clip(
        width: Int = 1920,
        height: Int = 1080,
        fps: Float? = 30f,
        bitrate: Int? = 16_000_000,
        durationMs: Long = 12_000L,
        sizeBytes: Long = 24_000_000L,
        mime: String = "video/avc",
        hdr: HdrTransfer = HdrTransfer.SDR,
    ) = VideoSourceInfo(
        mimeType = mime,
        width = width,
        height = height,
        frameRate = fps,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        videoBitrate = bitrate,
        audioBitrate = 128_000,
        hdrTransfer = hdr,
    )

    private val uhd = clip(width = 3840, height = 2160, bitrate = 45_000_000, sizeBytes = 70_000_000L)

    private fun profile(codec: VideoCodecChoice = VideoCodecChoice.AUTO, tier: UploadCompressionTier = UploadCompressionTier.BALANCED) =
        VideoCompressionProfile(codec, tier)

    private fun plan(
        source: VideoSourceInfo = clip(),
        profile: VideoCompressionProfile = profile(),
        caps: EncoderCapabilities = FakeEncoderCapabilities.flagship(),
        conditions: CompressionConditions = CompressionConditions(),
    ) = VideoCompressionPlanner.plan(source, profile, caps, conditions)

    private fun transcode(
        source: VideoSourceInfo = clip(),
        profile: VideoCompressionProfile = profile(),
        caps: EncoderCapabilities = FakeEncoderCapabilities.flagship(),
    ): VideoCompressionPlan.Transcode {
        val result = plan(source, profile, caps)
        assertTrue("expected a transcode but got $result", result is VideoCompressionPlan.Transcode)
        return result as VideoCompressionPlan.Transcode
    }

    private fun skipReason(
        source: VideoSourceInfo = clip(),
        profile: VideoCompressionProfile = profile(),
        caps: EncoderCapabilities = FakeEncoderCapabilities.flagship(),
        conditions: CompressionConditions = CompressionConditions(),
    ): CompressionSkipReason {
        val result = plan(source, profile, caps, conditions)
        assertTrue("expected a skip but got $result", result is VideoCompressionPlan.Skip)
        return (result as VideoCompressionPlan.Skip).reason
    }

    // ---- Codec ------------------------------------------------------------------------------

    @Test
    fun auto_picks_hardware_hevc_and_never_reports_a_fallback() {
        val plan = transcode()
        assertEquals(VideoCodec.HEVC, plan.codec)
        assertTrue(plan.hardware)
        assertNull(plan.plannedFallback)
        assertEquals(VideoCodec.AVC, transcode(caps = FakeEncoderCapabilities.emulator()).codec)
        assertNull(transcode(caps = FakeEncoderCapabilities.emulator()).plannedFallback)
    }

    @Test
    fun auto_never_picks_av1_or_hevc_the_muxer_cannot_write() {
        assertEquals(VideoCodec.AVC, transcode(caps = FakeEncoderCapabilities.flagship().without(VideoCodec.HEVC)).codec)
        assertEquals(VideoCodec.AVC, transcode(caps = FakeEncoderCapabilities.flagship().notMuxable(VideoCodec.HEVC)).codec)
    }

    @Test
    fun explicit_avc_is_used_as_is() {
        val plan = transcode(profile = profile(VideoCodecChoice.AVC))
        assertEquals(VideoCodec.AVC, plan.codec)
        assertNull(plan.plannedFallback)
    }

    @Test
    fun explicit_hevc_never_uses_the_software_encoder() {
        val small = clip(width = 480, height = 360, bitrate = 4_000_000, sizeBytes = 6_000_000L)
        val plan = transcode(source = small, profile = profile(VideoCodecChoice.HEVC), caps = FakeEncoderCapabilities.emulator())
        assertEquals(VideoCodec.AVC, plan.codec)
        assertEquals(VideoCodec.HEVC, plan.plannedFallback)
    }

    @Test
    fun explicit_av1_uses_hardware_av1_then_hardware_hevc_then_avc() {
        val av1 = profile(VideoCodecChoice.AV1)
        assertEquals(VideoCodec.AV1, transcode(profile = av1).codec)
        assertNull(transcode(profile = av1).plannedFallback)

        val hevc = transcode(profile = av1, caps = FakeEncoderCapabilities.flagship().without(VideoCodec.AV1))
        assertEquals(VideoCodec.HEVC, hevc.codec)
        assertEquals(VideoCodec.AV1, hevc.plannedFallback)

        val avc = transcode(profile = av1, caps = FakeEncoderCapabilities.emulator())
        assertEquals(VideoCodec.AVC, avc.codec)
        assertEquals(VideoCodec.AV1, avc.plannedFallback)
    }

    @Test
    fun keep_source_keeps_the_clip_codec_when_the_device_can_encode_it() {
        val hevcClip = clip(mime = "video/hevc", bitrate = 12_000_000)
        val keep = profile(VideoCodecChoice.KEEP_SOURCE)
        assertEquals(VideoCodec.HEVC, transcode(source = hevcClip, profile = keep).codec)

        val fallback = transcode(source = hevcClip, profile = keep, caps = FakeEncoderCapabilities.emulator())
        assertEquals(VideoCodec.AVC, fallback.codec)
        assertEquals(VideoCodec.HEVC, fallback.plannedFallback)

        val mpeg4 = transcode(source = clip(mime = "video/mp4v-es"), profile = keep)
        assertEquals(VideoCodec.AVC, mpeg4.codec)
        assertNull(mpeg4.plannedFallback)
    }

    @Test
    fun a_capped_hardware_encoder_takes_a_4k_clip_at_its_own_ceiling() {
        val flagship = FakeEncoderCapabilities.flagship()
        val hevc1080 = flagship.codecs.getValue(VideoCodec.HEVC).copy(
            encoders = listOf(EncoderInfo("hw.hevc.1080", hardware = true, maxWidth = 1920, maxHeight = 1088)),
        )
        val caps = flagship.copy(codecs = flagship.codecs + (VideoCodec.HEVC to hevc1080))
        val plan = transcode(source = uhd, profile = profile(VideoCodecChoice.HEVC, UploadCompressionTier.LIGHT), caps = caps)
        assertEquals(VideoCodec.HEVC, plan.codec)
        assertEquals(1080, plan.scaleToShortSide)
    }

    @Test
    fun no_usable_codec_skips() {
        val caps = FakeEncoderCapabilities.flagship().without(VideoCodec.AVC).without(VideoCodec.HEVC).without(VideoCodec.AV1)
        assertEquals(CompressionSkipReason.CODEC_UNAVAILABLE, skipReason(caps = caps))
    }

    // ---- Size and frame rate ------------------------------------------------------------------

    @Test
    fun the_tier_caps_the_short_side_and_never_upscales() {
        assertEquals(1080, transcode(source = uhd).scaleToShortSide)
        assertEquals(1080, transcode(source = clip(width = 2160, height = 3840, bitrate = 45_000_000, sizeBytes = 70_000_000L)).scaleToShortSide)
        assertEquals(0, transcode(source = uhd, profile = profile(tier = UploadCompressionTier.LIGHT)).scaleToShortSide)
        val hd = clip(width = 1280, height = 720, bitrate = 8_000_000, sizeBytes = 12_000_000L)
        assertEquals(0, transcode(source = hd).scaleToShortSide)
    }

    @Test
    fun space_saver_caps_60fps_to_30_and_the_others_keep_it() {
        val sixty = clip(fps = 60f, bitrate = 20_000_000, sizeBytes = 30_000_000L)
        assertEquals(30f, transcode(source = sixty, profile = profile(tier = UploadCompressionTier.SPACE_SAVER)).frameRateCap)
        assertNull(transcode(source = sixty).frameRateCap)
        assertNull(transcode(source = sixty, profile = profile(tier = UploadCompressionTier.LIGHT)).frameRateCap)
        assertNull(transcode(source = clip(fps = 30.02f), profile = profile(tier = UploadCompressionTier.SPACE_SAVER)).frameRateCap)
    }

    @Test
    fun the_bitrate_counts_at_most_60_frames_a_second() {
        val slowMo = clip(fps = 240f, bitrate = 80_000_000, sizeBytes = 120_000_000L)
        val at60 = VideoBitrateModel.targetBitrate(VideoCodec.AVC, UploadCompressionTier.BALANCED, 1920, 1080, 60f, false)
        assertEquals(at60, transcode(source = slowMo, profile = profile(VideoCodecChoice.AVC)).targetBitrate)
    }

    // ---- HDR --------------------------------------------------------------------------------

    @Test
    fun hdr_into_avc_is_tone_mapped_so_the_output_really_is_avc() {
        val hdr = clip(mime = "video/hevc", hdr = HdrTransfer.PQ, bitrate = 20_000_000)
        val plan = transcode(source = hdr, profile = profile(VideoCodecChoice.AVC))
        assertEquals(VideoCodec.AVC, plan.codec)
        assertEquals(HdrHandling.TONE_MAP_TO_SDR, plan.hdr)
    }

    @Test
    fun hdr_into_hevc_is_kept_when_the_encoder_can_edit_it_and_gets_more_bits() {
        val hdr = clip(mime = "video/hevc", hdr = HdrTransfer.HLG, bitrate = 20_000_000)
        val plan = transcode(source = hdr, profile = profile(VideoCodecChoice.HEVC))
        assertEquals(HdrHandling.KEEP_HDR, plan.hdr)
        val sdrTarget = VideoBitrateModel.targetBitrate(VideoCodec.HEVC, UploadCompressionTier.BALANCED, 1920, 1080, 30f, false)
        assertEquals(sdrTarget * VideoBitrateModel.HDR_MULTIPLIER, plan.targetBitrate.toDouble(), 2.0)

        val noEditing = FakeEncoderCapabilities.flagship(hdrEditing = false)
        assertEquals(HdrHandling.TONE_MAP_TO_SDR, transcode(source = hdr, profile = profile(VideoCodecChoice.HEVC), caps = noEditing).hdr)
    }

    @Test
    fun hdr_is_kept_when_a_separate_hdr_encoder_can_edit_it() {
        // Like the Galaxy S23: the first hardware H.265 encoder can't edit HDR, a second one can.
        val flagship = FakeEncoderCapabilities.flagship()
        val hevc = flagship.codecs.getValue(VideoCodec.HEVC).copy(
            encoders = listOf(
                EncoderInfo("c2.qti.hevc.encoder", hardware = true, maxWidth = 8192, maxHeight = 8192),
                EncoderInfo("c2.qti.hevc.encoder.hdr", hardware = true, maxWidth = 4096, maxHeight = 2176, hdrEditing = setOf(HdrTransfer.PQ, HdrTransfer.HLG)),
            ),
        )
        val caps = flagship.copy(codecs = flagship.codecs + (VideoCodec.HEVC to hevc))
        val hdr = clip(width = 3840, height = 2160, mime = "video/hevc", hdr = HdrTransfer.PQ, bitrate = 80_000_000, sizeBytes = 170_000_000L)
        assertEquals(HdrHandling.KEEP_HDR, transcode(source = hdr, profile = profile(VideoCodecChoice.HEVC), caps = caps).hdr)
    }

    @Test
    fun hdr_that_needs_tone_mapping_below_android_10_is_skipped() {
        val hdr = clip(mime = "video/hevc", hdr = HdrTransfer.PQ, bitrate = 20_000_000)
        assertEquals(
            CompressionSkipReason.HDR_UNSUPPORTED,
            skipReason(source = hdr, profile = profile(VideoCodecChoice.AVC), caps = FakeEncoderCapabilities.flagship(sdkInt = 28)),
        )
        assertEquals(HdrHandling.SDR_SOURCE, transcode().hdr)
    }

    // ---- Bitrate ----------------------------------------------------------------------------

    @Test
    fun a_source_already_near_the_target_is_skipped_as_already_efficient() {
        // The Balanced H.264 target at 720p30 is about 2.2 Mbps.
        val small = clip(width = 1280, height = 720, bitrate = 1_000_000, sizeBytes = 1_000_000L, durationMs = 8_000L)
        assertEquals(CompressionSkipReason.ALREADY_EFFICIENT, skipReason(source = small, profile = profile(VideoCodecChoice.AVC)))
        // Without a stated bitrate, 1 MB over 8 s estimates the same.
        assertEquals(CompressionSkipReason.ALREADY_EFFICIENT, skipReason(source = small.copy(videoBitrate = null)))
    }

    @Test
    fun the_target_follows_the_table_within_the_floor_and_the_source_ceiling() {
        assertEquals(0.08 * 1920 * 1080 * 30, transcode(profile = profile(VideoCodecChoice.AVC)).targetBitrate.toDouble(), 2.0)

        // Light H.264 aims at about 8.1 Mbps, but 80% of a 9.5 Mbps source is lower.
        val plan = transcode(source = clip(bitrate = 9_500_000), profile = profile(VideoCodecChoice.AVC, UploadCompressionTier.LIGHT))
        assertEquals((9_500_000 * VideoBitrateModel.SOURCE_CEILING_FRACTION).toInt(), plan.targetBitrate)

        val tiny = clip(width = 160, height = 120, bitrate = 3_000_000, sizeBytes = 4_500_000L)
        assertEquals(VideoBitrateModel.FLOOR_BPS, transcode(source = tiny, profile = profile(VideoCodecChoice.AVC)).targetBitrate)
    }

    // ---- Guards and conditions --------------------------------------------------------------

    @Test
    fun input_guards_skip_with_their_own_reason() {
        assertEquals(CompressionSkipReason.TOO_SHORT, skipReason(source = clip(durationMs = 500L)))
        assertEquals(CompressionSkipReason.TOO_LONG, skipReason(source = clip(durationMs = 21L * 60_000L)))
        assertEquals(CompressionSkipReason.TOO_LARGE, skipReason(source = clip(sizeBytes = 5L * 1024 * 1024 * 1024)))
        assertEquals(CompressionSkipReason.FRAME_TOO_LARGE, skipReason(source = clip(width = 7680, height = 4320)))
        assertEquals(CompressionSkipReason.UNREADABLE, skipReason(source = clip(width = 0)))
        assertEquals(CompressionSkipReason.UNREADABLE, skipReason(source = clip(sizeBytes = 0L)))
    }

    @Test
    fun a_hot_phone_or_a_full_disk_vetoes_the_transcode() {
        assertEquals(CompressionSkipReason.THERMAL, skipReason(conditions = CompressionConditions(thermalThrottled = true)))
        assertEquals(CompressionSkipReason.NO_SPACE, skipReason(conditions = CompressionConditions(freeBytes = 10_000_000L)))
        assertTrue(plan(conditions = CompressionConditions(freeBytes = 10L * 1024 * 1024 * 1024)) is VideoCompressionPlan.Transcode)
    }
}
