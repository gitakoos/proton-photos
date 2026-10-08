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

/**
 * Why a file was not compressed, or why its compressed copy was thrown away. Logged by name to the
 * sync diagnostics, so release builds keep the reason without naming the file.
 */
enum class CompressionSkipReason {
    ALREADY_EFFICIENT,
    TOO_LONG,
    TOO_SHORT,
    TOO_LARGE,
    /** Larger than 4K, which risks running out of memory while decoding. */
    FRAME_TOO_LARGE,
    NOT_SMALLER,
    SAVING_TOO_SMALL,
    ULTRA_HDR,
    MOTION_PHOTO,
    ANIMATED,
    TRANSPARENT,
    ENCODER_ERROR,
    THERMAL,
    UNREADABLE,
    NO_SPACE,
    CODEC_UNAVAILABLE,
    /** HDR that this Android version can neither keep nor tone-map to SDR. */
    HDR_UNSUPPORTED,
}

/** How a compression attempt ended. [FELL_BACK] means compressed, but with another codec than asked for. */
enum class CompressionOutcome { COMPRESSED, FELL_BACK, SKIPPED, FAILED }

enum class HdrHandling { SDR_SOURCE, KEEP_HDR, TONE_MAP_TO_SDR }

/** The planner's decision for one clip: leave it alone, or transcode it exactly like this. */
sealed interface VideoCompressionPlan {

    data class Skip(val reason: CompressionSkipReason) : VideoCompressionPlan

    /**
     * @property plannedFallback the codec the user asked for when this device can't produce it, else null.
     * @property hardware whether the planned encoder is a hardware one.
     * @property scaleToShortSide the short side to scale down to, or 0 to keep the size.
     * @property frameRateCap the frame rate to drop to, or null to keep the source's.
     * @property targetBitrate the requested video bitrate in bits per second.
     */
    data class Transcode(
        val codec: VideoCodec,
        val plannedFallback: VideoCodec?,
        val hardware: Boolean,
        val scaleToShortSide: Int,
        val frameRateCap: Float?,
        val hdr: HdrHandling,
        val targetBitrate: Int,
    ) : VideoCompressionPlan
}

/** Device conditions that can veto a transcode, read just before planning. */
data class CompressionConditions(
    /** The OS reports SEVERE thermal status or worse. */
    val thermalThrottled: Boolean = false,
    /** Free bytes where the temporary output goes, or null when unknown. */
    val freeBytes: Long? = null,
)
