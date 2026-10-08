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

import eu.akoos.photos.data.upload.compression.VideoCompressionPlanner
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.VideoBitrateModel
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo

enum class CodecBadge { HARDWARE, SOFTWARE, UNAVAILABLE }

enum class UnavailableReason { NEEDS_HARDWARE, NOT_SUPPORTED }

/**
 * A codec chip: the [choice], the codec it produces here ([resolves] is null for "Same as original",
 * which depends on each clip), its [badge] and, when greyed out, the [reason].
 */
data class CodecOption(
    val choice: VideoCodecChoice,
    val resolves: VideoCodec?,
    val badge: CodecBadge,
    val reason: UnavailableReason? = null,
) {
    val selectable: Boolean get() = badge != CodecBadge.UNAVAILABLE
}

/**
 * The codec chips and the size estimate for this device. What a choice produces comes from the
 * planner, run on a typical 1080p30 camera clip, so the screen and the uploads can't disagree.
 */
object CodecOptions {

    /** A minute of 1080p30 H.264 at a bitrate high enough never to count as already efficient. */
    private val REFERENCE_CLIP = VideoSourceInfo(
        mimeType = VideoCodec.AVC.mimeType,
        width = 1920,
        height = 1080,
        frameRate = 30f,
        durationMs = 60_000L,
        sizeBytes = 300_000_000L,
        videoBitrate = 40_000_000,
    )

    fun options(caps: EncoderCapabilities): List<CodecOption> {
        val auto = effectiveCodec(caps, VideoCodecChoice.AUTO)
        return listOf(
            CodecOption(VideoCodecChoice.AUTO, auto, badgeFor(caps, auto)),
            CodecOption(VideoCodecChoice.KEEP_SOURCE, null, badgeFor(caps, VideoCodec.AVC)),
            CodecOption(VideoCodecChoice.AVC, VideoCodec.AVC, badgeFor(caps, VideoCodec.AVC)),
            hardwareOnlyOption(caps, VideoCodecChoice.HEVC, VideoCodec.HEVC),
            hardwareOnlyOption(caps, VideoCodecChoice.AV1, VideoCodec.AV1),
        )
    }

    fun option(caps: EncoderCapabilities, choice: VideoCodecChoice): CodecOption =
        options(caps).first { it.choice == choice }

    /** The codec [choice] produces for a typical clip here; "Same as original" counts as H.264. */
    fun effectiveCodec(caps: EncoderCapabilities, choice: VideoCodecChoice): VideoCodec =
        plan(caps, VideoCompressionProfile(codec = choice))?.codec ?: VideoCodec.AVC

    /** About how many bytes a minute of 1080p video takes under [profile], and the short side it comes out at. */
    data class PerMinute(val bytes: Long, val shortSide: Int)

    fun perMinute(caps: EncoderCapabilities, profile: VideoCompressionProfile): PerMinute? {
        val plan = plan(caps, profile) ?: return null
        val shortSide = plan.scaleToShortSide.takeIf { it > 0 } ?: REFERENCE_CLIP.shortSide
        return PerMinute(VideoBitrateModel.bytesPerMinute(plan.targetBitrate), shortSide)
    }

    private fun plan(caps: EncoderCapabilities, profile: VideoCompressionProfile): VideoCompressionPlan.Transcode? =
        VideoCompressionPlanner.plan(REFERENCE_CLIP, profile, caps) as? VideoCompressionPlan.Transcode

    /** H.265 and AV1 are only offered with a hardware encoder; the software ones are too slow or too limited. */
    private fun hardwareOnlyOption(caps: EncoderCapabilities, choice: VideoCodecChoice, codec: VideoCodec): CodecOption =
        when {
            caps.hasHardware(codec) -> CodecOption(choice, codec, CodecBadge.HARDWARE)
            caps.isAvailable(codec) -> CodecOption(choice, codec, CodecBadge.UNAVAILABLE, UnavailableReason.NEEDS_HARDWARE)
            else -> CodecOption(choice, codec, CodecBadge.UNAVAILABLE, UnavailableReason.NOT_SUPPORTED)
        }

    private fun badgeFor(caps: EncoderCapabilities, codec: VideoCodec): CodecBadge = when {
        caps.hasHardware(codec) -> CodecBadge.HARDWARE
        caps.isAvailable(codec) -> CodecBadge.SOFTWARE
        else -> CodecBadge.UNAVAILABLE
    }
}
