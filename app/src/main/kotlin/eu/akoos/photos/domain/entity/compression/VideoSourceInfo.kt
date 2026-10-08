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

/** A clip's colour transfer. PQ covers HDR10 and most Dolby Vision; HLG is what phones record as HDR. */
enum class HdrTransfer { SDR, PQ, HLG }

/**
 * What the probe read from a source clip. [width] and [height] are the coded size, before rotation;
 * the planner works on the short side, which rotation doesn't change. A null frame rate or bitrate
 * means the container doesn't say.
 */
data class VideoSourceInfo(
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float? = null,
    val durationMs: Long,
    val sizeBytes: Long,
    val videoBitrate: Int? = null,
    val audioBitrate: Int? = null,
    val hasAudio: Boolean = true,
    val hdrTransfer: HdrTransfer = HdrTransfer.SDR,
) {
    val shortSide: Int get() = minOf(width, height)
    val longSide: Int get() = maxOf(width, height)
    val isHdr: Boolean get() = hdrTransfer != HdrTransfer.SDR

    /** The container's video bitrate, else an estimate from the file size and duration. */
    val effectiveVideoBitrate: Int?
        get() = videoBitrate?.takeIf { it > 0 }
            ?: VideoBitrateModel.estimateVideoBitrate(sizeBytes, durationMs, VideoBitrateModel.audioBitrateOf(this))
}

/**
 * One platform encoder. [maxWidth] and [maxHeight] are the top of its supported ranges. Frames reach
 * the encoder in landscape (rotation stays in the container), but some encoders swap the ranges, so
 * a frame fits either way round.
 */
data class EncoderInfo(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Transfers this encoder can keep through an edit; empty below Android 13. */
    val hdrEditing: Set<HdrTransfer> = emptySet(),
) {
    fun fits(longSide: Int, shortSide: Int): Boolean =
        (longSide <= maxWidth && shortSide <= maxHeight) || (longSide <= maxHeight && shortSide <= maxWidth)

    /** The largest even short side, at the clip's aspect ratio, that still fits. */
    fun largestFittingShortSide(longSide: Int, shortSide: Int): Int {
        if (longSide <= 0 || shortSide <= 0) return 0
        val landscape = minOf(1.0, maxWidth.toDouble() / longSide, maxHeight.toDouble() / shortSide)
        val swapped = minOf(1.0, maxHeight.toDouble() / longSide, maxWidth.toDouble() / shortSide)
        return evenFloor((shortSide * maxOf(landscape, swapped)).toInt())
    }
}

/** One codec on this device: its encoders, hardware first, and whether the MP4 muxer can write it. */
data class CodecCapability(
    val codec: VideoCodec,
    val encoders: List<EncoderInfo>,
    val muxable: Boolean,
) {
    val available: Boolean get() = encoders.isNotEmpty() && muxable
    val hasHardware: Boolean get() = available && encoders.any { it.hardware }

    fun encoderFor(longSide: Int, shortSide: Int, hardwareOnly: Boolean): EncoderInfo? =
        encoders.filter { !hardwareOnly || it.hardware }.firstOrNull { it.fits(longSide, shortSide) }

    fun largestFittingShortSide(longSide: Int, shortSide: Int, hardwareOnly: Boolean): Int =
        encoders.filter { !hardwareOnly || it.hardware }
            .maxOfOrNull { it.largestFittingShortSide(longSide, shortSide) } ?: 0

    /** Whether any usable encoder keeps [transfer] at this size. Phones often have a separate HDR
     *  encoder, which Media3 picks itself when asked to keep HDR. */
    fun keepsHdr(transfer: HdrTransfer, longSide: Int, shortSide: Int, hardwareOnly: Boolean): Boolean =
        encoders.any { (!hardwareOnly || it.hardware) && it.fits(longSide, shortSide) && transfer in it.hdrEditing }
}

/** This device's video encoders. [sdkInt] rides along because HDR tone-mapping depends on it. */
data class EncoderCapabilities(
    val codecs: Map<VideoCodec, CodecCapability>,
    val sdkInt: Int,
) {
    operator fun get(codec: VideoCodec): CodecCapability? = codecs[codec]

    fun isAvailable(codec: VideoCodec): Boolean = codecs[codec]?.available == true

    fun hasHardware(codec: VideoCodec): Boolean = codecs[codec]?.hasHardware == true
}

/** Rounds down to an even number; encoders want even dimensions for 4:2:0 chroma. */
internal fun evenFloor(value: Int): Int = if (value <= 0) 0 else value - (value % 2)
