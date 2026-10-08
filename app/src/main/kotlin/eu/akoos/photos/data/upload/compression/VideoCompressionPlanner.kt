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

import eu.akoos.photos.domain.entity.compression.CompressionConditions
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.EncoderInfo
import eu.akoos.photos.domain.entity.compression.HdrHandling
import eu.akoos.photos.domain.entity.compression.VideoBitrateModel
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo
import eu.akoos.photos.domain.entity.compression.evenFloor
import kotlin.math.roundToInt

/**
 * Decides whether and how to transcode a clip from its probe, the user's profile and this device's
 * encoders. Pure, so every rule is pinned by a JVM test. The rules, in order:
 *
 *  1. Input guards (unreadable, under a second, over twenty minutes or 4 GB, above 4K), then the
 *     thermal veto.
 *  2. The tier's short-side and frame-rate caps; nothing is ever scaled up.
 *  3. The codec: the first candidate with an encoder that takes the frame. H.265 and AV1 need a
 *     hardware encoder. An encoder that tops out below the frame is still used, at a reduced size,
 *     down to [MIN_CLAMPED_SHORT_SIDE].
 *  4. HDR: H.264 always tone-maps to SDR, since Media3 would otherwise switch an HDR clip to H.265.
 *     Other codecs keep HDR when the encoder can edit it.
 *  5. The bitrate target, floored. A source already at or near it is skipped, and otherwise the
 *     target is capped below the source's own bitrate.
 *  6. Free space for a temp copy as large as the source.
 */
object VideoCompressionPlanner {

    const val MAX_INPUT_BYTES = 4L * 1024L * 1024L * 1024L
    const val MAX_INPUT_DURATION_MS = 20L * 60L * 1000L
    const val MIN_INPUT_DURATION_MS = 1_000L

    /** 8K frames and up risk an out-of-memory crash while decoding. */
    const val MAX_INPUT_LONG_EDGE = 4096

    /** The smallest short side a capped encoder may reduce a clip to before the next codec is tried. */
    const val MIN_CLAMPED_SHORT_SIDE = 720

    /** OpenGL HDR-to-SDR tone-mapping needs Android 10. */
    const val TONE_MAP_MIN_SDK = 29

    const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L

    /** A 30.02 fps camera clip is not "above" a 30 fps cap. */
    private const val FPS_TOLERANCE = 1.5f

    fun plan(
        source: VideoSourceInfo,
        profile: VideoCompressionProfile,
        capabilities: EncoderCapabilities,
        conditions: CompressionConditions = CompressionConditions(),
    ): VideoCompressionPlan {
        guard(source, conditions)?.let { return VideoCompressionPlan.Skip(it) }

        val capShort = profile.tier.videoMaxShortEdgePx
        val plannedShort = if (capShort in 1 until source.shortSide) capShort else source.shortSide
        val plannedLong = longSideFor(source, plannedShort)

        val sourceFps = source.frameRate?.takeIf { it > 0f }
        val fpsCap = profile.tier.videoMaxFps?.takeIf { cap -> sourceFps != null && sourceFps > cap + FPS_TOLERANCE }
        val outputFps = fpsCap ?: sourceFps ?: VideoBitrateModel.DEFAULT_FPS

        val resolved = resolveCodec(profile.codec, source, capabilities, plannedLong, plannedShort)
            ?: return VideoCompressionPlan.Skip(CompressionSkipReason.CODEC_UNAVAILABLE)

        val hdr = when {
            !source.isHdr -> HdrHandling.SDR_SOURCE
            resolved.codec == VideoCodec.AVC -> HdrHandling.TONE_MAP_TO_SDR
            capabilities[resolved.codec]?.keepsHdr(
                source.hdrTransfer, resolved.longSide, resolved.shortSide, hardwareOnly = resolved.codec != VideoCodec.AVC,
            ) == true -> HdrHandling.KEEP_HDR
            else -> HdrHandling.TONE_MAP_TO_SDR
        }
        if (hdr == HdrHandling.TONE_MAP_TO_SDR && capabilities.sdkInt < TONE_MAP_MIN_SDK) {
            return VideoCompressionPlan.Skip(CompressionSkipReason.HDR_UNSUPPORTED)
        }

        val target = VideoBitrateModel.targetBitrate(
            codec = resolved.codec,
            tier = profile.tier,
            width = resolved.longSide,
            height = resolved.shortSide,
            fps = outputFps,
            keepsHdr = hdr == HdrHandling.KEEP_HDR,
        ).coerceAtLeast(VideoBitrateModel.FLOOR_BPS)
        val sourceBitrate = source.effectiveVideoBitrate
        if (sourceBitrate != null && sourceBitrate <= target * VideoBitrateModel.ALREADY_EFFICIENT_MARGIN) {
            return VideoCompressionPlan.Skip(CompressionSkipReason.ALREADY_EFFICIENT)
        }

        val free = conditions.freeBytes
        if (free != null && free < source.sizeBytes + FREE_SPACE_MARGIN_BYTES) {
            return VideoCompressionPlan.Skip(CompressionSkipReason.NO_SPACE)
        }

        return VideoCompressionPlan.Transcode(
            codec = resolved.codec,
            plannedFallback = resolved.fallbackFrom,
            hardware = resolved.encoder.hardware,
            scaleToShortSide = if (resolved.shortSide < source.shortSide) resolved.shortSide else 0,
            frameRateCap = fpsCap,
            hdr = hdr,
            targetBitrate = sourceBitrate
                ?.let { minOf(target, (it * VideoBitrateModel.SOURCE_CEILING_FRACTION).toInt()) }
                ?: target,
        )
    }

    private fun guard(source: VideoSourceInfo, conditions: CompressionConditions): CompressionSkipReason? = when {
        source.sizeBytes <= 0L || source.width <= 0 || source.height <= 0 || source.durationMs <= 0L ->
            CompressionSkipReason.UNREADABLE
        source.sizeBytes > MAX_INPUT_BYTES -> CompressionSkipReason.TOO_LARGE
        source.durationMs < MIN_INPUT_DURATION_MS -> CompressionSkipReason.TOO_SHORT
        source.durationMs > MAX_INPUT_DURATION_MS -> CompressionSkipReason.TOO_LONG
        source.longSide > MAX_INPUT_LONG_EDGE -> CompressionSkipReason.FRAME_TOO_LARGE
        conditions.thermalThrottled -> CompressionSkipReason.THERMAL
        else -> null
    }

    /** The long side for [shortSide] at the source's aspect ratio, rounded to an even number. */
    private fun longSideFor(source: VideoSourceInfo, shortSide: Int): Int {
        if (shortSide == source.shortSide) return source.longSide
        val exact = source.longSide.toDouble() * shortSide / source.shortSide
        return ((exact / 2.0).roundToInt() * 2).coerceAtLeast(2)
    }

    private class Resolved(
        val codec: VideoCodec,
        val encoder: EncoderInfo,
        val shortSide: Int,
        val longSide: Int,
        val fallbackFrom: VideoCodec?,
    )

    /** The codecs to try for [choice], best first; the same order Media3 falls back in at run time. */
    private fun candidates(choice: VideoCodecChoice, source: VideoSourceInfo): List<VideoCodec> = when (choice) {
        VideoCodecChoice.AUTO, VideoCodecChoice.HEVC -> listOf(VideoCodec.HEVC, VideoCodec.AVC)
        VideoCodecChoice.AVC -> listOf(VideoCodec.AVC)
        VideoCodecChoice.AV1 -> listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.AVC)
        VideoCodecChoice.KEEP_SOURCE -> listOfNotNull(VideoCodec.fromMime(source.mimeType), VideoCodec.AVC).distinct()
    }

    private fun resolveCodec(
        choice: VideoCodecChoice,
        source: VideoSourceInfo,
        capabilities: EncoderCapabilities,
        longSide: Int,
        shortSide: Int,
    ): Resolved? {
        val list = candidates(choice, source)
        // AUTO has no single wish, so landing on H.264 is not a fallback.
        val wished = if (choice == VideoCodecChoice.AUTO) null else list.first()
        for (codec in list) {
            val capability = capabilities[codec]?.takeIf { it.available } ?: continue
            val hardwareOnly = codec != VideoCodec.AVC
            val fallbackFrom = wished?.takeIf { it != codec }
            capability.encoderFor(longSide, shortSide, hardwareOnly)?.let {
                return Resolved(codec, it, shortSide, longSide, fallbackFrom)
            }
            // Both sides floored to even, so the reduced frame never exceeds the limit it was computed from.
            val clamped = capability.largestFittingShortSide(longSide, shortSide, hardwareOnly)
            if (clamped < MIN_CLAMPED_SHORT_SIDE) continue
            val clampedLong = evenFloor((longSide.toDouble() * clamped / shortSide).toInt())
            val encoder = capability.encoderFor(clampedLong, clamped, hardwareOnly) ?: continue
            return Resolved(codec, encoder, clamped, clampedLong, fallbackFrom)
        }
        return null
    }
}
