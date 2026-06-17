/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.presentation.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "VideoMetadataStripper"

/**
 * Drops container-level GPS metadata (udta `©xyz`/`loci`, QuickTime ISO6709 atoms — outside the
 * elementary streams, so EXIF wiping can't reach them) by re-muxing: every track is stream-copied
 * verbatim into a fresh [MediaMuxer], which omits the location atom since we never call setLocation.
 * No decode/encode, so quality is untouched.
 */
object VideoMetadataStripper {

    /**
     * Stream-copies every track of [uri] into [outFile] with no location atom; preserves orientation
     * via the muxer hint. Returns false (and deletes the partial output) on failure so the caller
     * can fall back to the original bytes.
     */
    fun remuxWithoutLocation(context: Context, uri: String, outFile: File): Boolean {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var success = false
        try {
            pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                ?: return false
            extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
            val trackCount = extractor.trackCount
            if (trackCount == 0) return false

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Carry the source rotation forward, else a portrait capture plays back sideways.
            val rotation = runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(context, Uri.parse(uri))
                val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                mmr.release()
                r
            }.getOrDefault(0)
            muxer.setOrientationHint(((rotation % 360) + 360) % 360)

            // Map every source track to an output track so nothing is dropped.
            val muxTrackByExtractorTrack = HashMap<Int, Int>(trackCount)
            var maxInputSize = 1 shl 20 // 1 MiB floor for the sample buffer.
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                muxTrackByExtractorTrack[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }

            muxer.start()
            var buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            for (i in 0 until trackCount) {
                extractor.selectTrack(i)
                val outTrack = muxTrackByExtractorTrack.getValue(i)
                while (true) {
                    buffer.clear()
                    // 4K/HEVC keyframes can exceed the 1 MiB floor; readSampleData throws rather than
                    // truncate. Grow and retry instead of failing the strip (which would upload unstripped).
                    val read = try {
                        extractor.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        // Cap growth so a non-size IllegalArgumentException can't loop forever.
                        val grown = nextSampleBufferCapacity(buffer.capacity()) ?: throw e
                        buffer = ByteBuffer.allocate(grown)
                        continue
                    }
                    if (read < 0) break
                    info.offset = 0
                    info.size = read
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractorFlagsToBufferFlags(extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(i)
            }
            success = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "remuxWithoutLocation failed for $uri: ${e.message}", e)
            return false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
            if (!success) runCatching { outFile.delete() }
        }
    }

    internal fun extractorFlagsToBufferFlags(extractorFlags: Int): Int {
        var flags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    /** Hard ceiling for the sample-buffer grow-on-retry loop; above this the exception is rethrown. */
    internal const val MAX_SAMPLE_BUFFER_BYTES = 64 shl 20

    /** Next (doubled) buffer capacity for the retry loop, or null at the cap. Extracted for testability. */
    internal fun nextSampleBufferCapacity(currentCapacity: Int): Int? {
        if (currentCapacity >= MAX_SAMPLE_BUFFER_BYTES) return null
        return currentCapacity * 2
    }
}
