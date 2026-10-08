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

/** A codec the upload transcoder can produce, by the sample MIME type encoders and the muxer use. */
enum class VideoCodec(val mimeType: String) {
    AVC("video/avc"),
    HEVC("video/hevc"),
    AV1("video/av01");

    companion object {
        fun fromMime(mime: String?): VideoCodec? {
            val normalized = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.mimeType == normalized }
        }
    }
}

/**
 * The codec the user picked for compressed videos, persisted by [key]. The planner resolves it per
 * clip against this device's encoders:
 *
 *  - [AUTO]: H.265 with a hardware encoder, otherwise H.264.
 *  - [HEVC], [AV1]: only with a hardware encoder; otherwise the next codec down.
 *  - [KEEP_SOURCE]: the clip's own codec, which is what video compression did before this choice.
 */
enum class VideoCodecChoice(val key: String) {
    AUTO("auto"),
    AVC("avc"),
    HEVC("hevc"),
    AV1("av1"),
    KEEP_SOURCE("keep");

    companion object {
        fun fromKey(key: String?): VideoCodecChoice? = entries.firstOrNull { it.key == key }
    }
}

/** The video compression settings: the codec choice, and the quality tier that also caps size and frame rate. */
data class VideoCompressionProfile(
    val codec: VideoCodecChoice = VideoCodecChoice.AUTO,
    val tier: UploadCompressionTier = UploadCompressionTier.BALANCED,
)
