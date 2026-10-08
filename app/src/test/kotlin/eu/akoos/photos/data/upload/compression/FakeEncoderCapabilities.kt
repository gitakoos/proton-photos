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

import eu.akoos.photos.domain.entity.compression.CodecCapability
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.EncoderInfo
import eu.akoos.photos.domain.entity.compression.HdrTransfer
import eu.akoos.photos.domain.entity.compression.VideoCodec

/**
 * Devices for tests. [flagship] has hardware H.264, H.265 and AV1 up to 4K plus the software encoders;
 * [emulator] is the API 36 emulator as its scan reports it: software only, with H.265 capped at 512.
 */
object FakeEncoderCapabilities {

    fun flagship(sdkInt: Int = 36, hdrEditing: Boolean = true): EncoderCapabilities {
        val hdr = if (hdrEditing) setOf(HdrTransfer.PQ, HdrTransfer.HLG) else emptySet()
        return EncoderCapabilities(
            codecs = mapOf(
                codec(
                    VideoCodec.AVC,
                    EncoderInfo("c2.vendor.avc.encoder", hardware = true, maxWidth = 4096, maxHeight = 2176),
                    EncoderInfo("c2.android.avc.encoder", hardware = false, maxWidth = 2048, maxHeight = 2048),
                ),
                codec(
                    VideoCodec.HEVC,
                    EncoderInfo("c2.vendor.hevc.encoder", hardware = true, maxWidth = 4096, maxHeight = 2176, hdrEditing = hdr),
                    EncoderInfo("c2.android.hevc.encoder", hardware = false, maxWidth = 512, maxHeight = 512),
                ),
                codec(
                    VideoCodec.AV1,
                    EncoderInfo("c2.vendor.av1.encoder", hardware = true, maxWidth = 4096, maxHeight = 2176, hdrEditing = hdr),
                    EncoderInfo("c2.android.av1.encoder", hardware = false, maxWidth = 1920, maxHeight = 1080),
                ),
            ),
            sdkInt = sdkInt,
        )
    }

    fun emulator(): EncoderCapabilities = EncoderCapabilities(
        codecs = mapOf(
            codec(VideoCodec.AVC, EncoderInfo("c2.android.avc.encoder", hardware = false, maxWidth = 2048, maxHeight = 2048)),
            codec(VideoCodec.HEVC, EncoderInfo("c2.android.hevc.encoder", hardware = false, maxWidth = 512, maxHeight = 512)),
            codec(VideoCodec.AV1, EncoderInfo("c2.android.av1.encoder", hardware = false, maxWidth = 1920, maxHeight = 1920)),
        ),
        sdkInt = 36,
    )

    private fun codec(codec: VideoCodec, vararg encoders: EncoderInfo) =
        codec to CodecCapability(codec, encoders.toList(), muxable = true)
}

fun EncoderCapabilities.without(codec: VideoCodec) = copy(codecs = codecs - codec)

fun EncoderCapabilities.notMuxable(codec: VideoCodec) =
    copy(codecs = codecs.mapValues { (c, cap) -> if (c == codec) cap.copy(muxable = false) else cap })
