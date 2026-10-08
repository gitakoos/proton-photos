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
import kotlin.math.pow

/**
 * The bitrate a compressed video aims for: a bits-per-pixel figure per codec and tier, at each tier's
 * reference size (1080p, or 720p for Space saver).
 *
 * Away from the reference size the pixel count scales with [PIXEL_EXPONENT] rather than linearly,
 * because small frames need more bits per pixel. A linear model would also give a 480p clip more bits
 * on Space saver than on Balanced. Frame rates count up to [MAX_FPS_FOR_BITRATE]: slow-motion frames
 * are nearly identical.
 */
object VideoBitrateModel {

    /** HDR keeps 10-bit colour, so it gets a quarter more bits. */
    const val HDR_MULTIPLIER = 1.25

    /** Below this even 480p falls apart. */
    const val FLOOR_BPS = 400_000

    /** The target never exceeds this share of the source's own bitrate. */
    const val SOURCE_CEILING_FRACTION = 0.8

    /** A source at or under the target times this is skipped as already efficient. */
    const val ALREADY_EFFICIENT_MARGIN = 1.1

    const val MAX_FPS_FOR_BITRATE = 60f
    const val DEFAULT_FPS = 30f
    const val DEFAULT_AUDIO_BPS = 128_000
    const val PIXEL_EXPONENT = 0.75

    private const val PIXELS_1080P = 1920.0 * 1080.0
    private const val PIXELS_720P = 1280.0 * 720.0

    fun bitsPerPixel(codec: VideoCodec, tier: UploadCompressionTier): Double = when (tier) {
        UploadCompressionTier.LIGHT -> when (codec) {
            VideoCodec.AVC -> 0.13
            VideoCodec.HEVC -> 0.088
            VideoCodec.AV1 -> 0.072
        }
        UploadCompressionTier.BALANCED -> when (codec) {
            VideoCodec.AVC -> 0.08
            VideoCodec.HEVC -> 0.056
            VideoCodec.AV1 -> 0.048
        }
        UploadCompressionTier.SPACE_SAVER -> when (codec) {
            VideoCodec.AVC -> 0.09
            VideoCodec.HEVC -> 0.065
            VideoCodec.AV1 -> 0.054
        }
    }

    /** `bpp × ref × (pixels / ref)^0.75 × fps`, before any clamp; exactly `bpp × pixels × fps` at the reference size. */
    fun targetBitrate(
        codec: VideoCodec,
        tier: UploadCompressionTier,
        width: Int,
        height: Int,
        fps: Float,
        keepsHdr: Boolean,
    ): Int {
        val fpsTerm = fps.takeIf { it > 0f }?.coerceAtMost(MAX_FPS_FOR_BITRATE) ?: DEFAULT_FPS
        val reference = if (tier == UploadCompressionTier.SPACE_SAVER) PIXELS_720P else PIXELS_1080P
        val pixels = reference * (width.toDouble() * height / reference).pow(PIXEL_EXPONENT)
        val bits = bitsPerPixel(codec, tier) * pixels * fpsTerm * (if (keepsHdr) HDR_MULTIPLIER else 1.0)
        return bits.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** The video bitrate implied by a file's size and duration, net of [audioBitrate]; null when unusable. */
    fun estimateVideoBitrate(sizeBytes: Long, durationMs: Long, audioBitrate: Int): Int? {
        if (sizeBytes <= 0L || durationMs <= 0L) return null
        val video = sizeBytes * 8_000.0 / durationMs - audioBitrate
        return if (video > 0) video.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null
    }

    /** The clip's audio bitrate: 0 without audio, [DEFAULT_AUDIO_BPS] when the container doesn't say. */
    fun audioBitrateOf(source: VideoSourceInfo): Int = when {
        !source.hasAudio -> 0
        source.audioBitrate != null && source.audioBitrate > 0 -> source.audioBitrate
        else -> DEFAULT_AUDIO_BPS
    }

    /** Bytes a minute of video at [videoBitrate] takes, with [DEFAULT_AUDIO_BPS] audio. */
    fun bytesPerMinute(videoBitrate: Int): Long = (videoBitrate.toLong() + DEFAULT_AUDIO_BPS) * 60L / 8L
}
