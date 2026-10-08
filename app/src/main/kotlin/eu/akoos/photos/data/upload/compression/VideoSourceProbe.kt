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

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import eu.akoos.photos.data.upload.UploadImageCompressor
import eu.akoos.photos.domain.entity.compression.HdrTransfer
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo
import java.util.concurrent.TimeUnit

private const val TAG = "VideoSourceProbe"

private const val MEDIA3_TIMEOUT_SECONDS = 10L

/**
 * Reads what the planner needs from a clip without decoding a frame. Media3's [MetadataRetriever]
 * parses the container the way the transcoder will, so it goes first; the platform
 * [MediaMetadataRetriever] fills in only when Media3 fails or leaves a field unknown. Returns null
 * for an unreadable clip. Blocking.
 */
object VideoSourceProbe {

    fun probe(context: Context, uri: Uri): VideoSourceInfo? {
        val appContext = context.applicationContext
        val size = UploadImageCompressor.readSourceSize(appContext, uri)
        if (size <= 0L) return null
        val media3 = runCatching { probeMedia3(appContext, uri) }
            .onFailure { Log.d(TAG, "Media3 probe failed: ${it.message}") }
            .getOrNull()
        val platform = if (media3?.isComplete == true) {
            null
        } else {
            runCatching { probePlatform(appContext, uri) }
                .onFailure { Log.d(TAG, "platform probe failed: ${it.message}") }
                .getOrNull()
        }
        return merge(media3, platform, size)
    }

    /** What one reader found; a null field means it doesn't know. */
    private data class Partial(
        val hasVideo: Boolean,
        val mimeType: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Float? = null,
        val durationMs: Long? = null,
        val videoBitrate: Int? = null,
        val audioBitrate: Int? = null,
        val hasAudio: Boolean? = null,
        val hdrTransfer: HdrTransfer? = null,
    ) {
        /** Everything the planner needs. A missing colour transfer means SDR to Media3 as well. */
        val isComplete: Boolean
            get() = hasVideo && mimeType != null && width != null && height != null &&
                frameRate != null && durationMs != null
    }

    private fun merge(primary: Partial?, secondary: Partial?, size: Long): VideoSourceInfo? {
        if (primary?.hasVideo != true && secondary?.hasVideo != true) return null
        return VideoSourceInfo(
            mimeType = primary?.mimeType ?: secondary?.mimeType,
            width = primary?.width ?: secondary?.width ?: 0,
            height = primary?.height ?: secondary?.height ?: 0,
            frameRate = primary?.frameRate ?: secondary?.frameRate,
            durationMs = primary?.durationMs ?: secondary?.durationMs ?: 0L,
            sizeBytes = size,
            videoBitrate = primary?.videoBitrate ?: secondary?.videoBitrate,
            audioBitrate = primary?.audioBitrate ?: secondary?.audioBitrate,
            hasAudio = primary?.hasAudio ?: secondary?.hasAudio ?: false,
            hdrTransfer = primary?.hdrTransfer ?: secondary?.hdrTransfer ?: HdrTransfer.SDR,
        )
    }

    @OptIn(UnstableApi::class)
    private fun probeMedia3(context: Context, uri: Uri): Partial {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(MEDIA3_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val durationUs = runCatching {
                retriever.retrieveDurationUs().get(MEDIA3_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            val formats = (0 until groups.length).map { groups[it].getFormat(0) }
            val video: Format = formats.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }
                ?: return Partial(hasVideo = false)
            val audio = formats.firstOrNull { MimeTypes.isAudio(it.sampleMimeType) }
            return Partial(
                hasVideo = true,
                mimeType = video.sampleMimeType,
                width = video.width.takeIf { it > 0 },
                height = video.height.takeIf { it > 0 },
                frameRate = video.frameRate.takeIf { it > 0f },
                durationMs = durationUs?.takeIf { it > 0L && it != C.TIME_UNSET }?.let { it / 1000L },
                videoBitrate = video.averageBitrate.takeIf { it > 0 },
                audioBitrate = audio?.averageBitrate?.takeIf { it > 0 } ?: audio?.bitrate?.takeIf { it > 0 },
                hasAudio = audio != null,
                hdrTransfer = video.colorInfo?.colorTransfer?.let(::transferFor),
            )
        }
    }

    private fun probePlatform(context: Context, uri: Uri): Partial {
        val retriever = MediaMetadataRetriever()
        val partial = try {
            retriever.setDataSource(context, uri)
            fun key(k: Int): String? = runCatching { retriever.extractMetadata(k) }.getOrNull()
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
            val frames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()?.takeIf { it > 0 }
            } else {
                null
            }
            Partial(
                hasVideo = key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 },
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 },
                frameRate = if (frames != null && duration != null) frames * 1000f / duration else null,
                durationMs = duration,
                hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                hdrTransfer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    key(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()?.let(::transferFor)
                } else {
                    null
                },
            )
        } finally {
            runCatching { retriever.release() }
        }
        // The retriever doesn't name the codec; the extractor reads it from the track header.
        return partial.copy(mimeType = runCatching { videoMime(context, uri) }.getOrNull())
    }

    private fun videoMime(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Media3 and MediaFormat share the colour-transfer constants. */
    @OptIn(UnstableApi::class)
    private fun transferFor(transfer: Int): HdrTransfer? = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> HdrTransfer.PQ
        C.COLOR_TRANSFER_HLG -> HdrTransfer.HLG
        C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_SRGB, C.COLOR_TRANSFER_LINEAR -> HdrTransfer.SDR
        else -> null
    }
}
