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

import android.media.MediaCodecInfo
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.InAppMp4Muxer
import eu.akoos.photos.domain.entity.compression.CodecCapability
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.EncoderInfo
import eu.akoos.photos.domain.entity.compression.HdrTransfer
import eu.akoos.photos.domain.entity.compression.VideoCodec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VideoEncoderCaps"

/**
 * This device's video encoders per codec, read through Media3's [EncoderUtil], and whether
 * [InAppMp4Muxer] (the transcoder's muxer) can write each codec. Scanned once per process.
 */
@Singleton
class VideoEncoderCapabilities @Inject constructor() {

    private val cached: EncoderCapabilities by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { probe() }

    /** Blocking on first call (a codec-list scan), cached after; call it off the main thread. */
    fun snapshot(): EncoderCapabilities = cached

    @OptIn(UnstableApi::class)
    private fun probe(): EncoderCapabilities {
        val muxable = runCatching {
            InAppMp4Muxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO).toSet()
        }.getOrDefault(emptySet())
        val codecs = VideoCodec.entries.associateWith { codec ->
            val encoders = runCatching { encodersFor(codec) }
                .onFailure { Log.w(TAG, "encoder scan failed for ${codec.mimeType}: ${it.message}") }
                .getOrDefault(emptyList())
            CodecCapability(codec, encoders, muxable = codec.mimeType in muxable)
        }
        return EncoderCapabilities(codecs, Build.VERSION.SDK_INT).also { Log.d(TAG, describe(it)) }
    }

    @OptIn(UnstableApi::class)
    private fun encodersFor(codec: VideoCodec): List<EncoderInfo> =
        EncoderUtil.getSupportedEncoders(codec.mimeType).map { info ->
            val ranges = EncoderUtil.getSupportedResolutionRanges(info, codec.mimeType)
            EncoderInfo(
                name = info.name,
                hardware = EncoderUtil.isHardwareAccelerated(info, codec.mimeType),
                maxWidth = ranges.first.upper,
                maxHeight = ranges.second.upper,
                hdrEditing = hdrEditingFor(info, codec),
            )
        }.sortedByDescending { it.hardware }

    /** HDR editing is an Android 13 feature, and Media3 never keeps HDR in H.264. */
    @OptIn(UnstableApi::class)
    private fun hdrEditingFor(info: MediaCodecInfo, codec: VideoCodec): Set<HdrTransfer> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || codec == VideoCodec.AVC) return emptySet()
        return mapOf(HdrTransfer.PQ to C.COLOR_TRANSFER_ST2084, HdrTransfer.HLG to C.COLOR_TRANSFER_HLG)
            .filterValues { transfer ->
                val color = ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(transfer)
                    .build()
                runCatching { EncoderUtil.isHdrEditingSupported(info, codec.mimeType, color) }.getOrDefault(false)
            }
            .keys
    }

    /** One log line, e.g. `api=36 AVC[sw c2.android.avc.encoder 2048x2048] HEVC[...]`. */
    private fun describe(snapshot: EncoderCapabilities): String = buildString {
        append("api=").append(snapshot.sdkInt)
        for ((codec, capability) in snapshot.codecs) {
            append(' ').append(codec.name).append(if (capability.muxable) "" else "(no-mux)").append('[')
            append(
                capability.encoders.joinToString(", ") { encoder ->
                    val kind = if (encoder.hardware) "hw" else "sw"
                    val hdr = if (encoder.hdrEditing.isEmpty()) "" else " hdr=" + encoder.hdrEditing.joinToString("+")
                    "$kind ${encoder.name} ${encoder.maxWidth}x${encoder.maxHeight}$hdr"
                }
            )
            append(']')
        }
    }
}
