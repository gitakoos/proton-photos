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
 * Drops container-level location metadata from a video without re-encoding.
 *
 * MP4/MOV captures embed GPS coordinates as container atoms (udta `©xyz` / `loci`,
 * com.apple.quicktime.location.ISO6709) that sit outside every elementary stream, so
 * EXIF-style tag wiping can't reach them and a byte-for-byte upload would leak them even
 * with GPS stripping requested. Re-muxing solves this structurally: every track's encoded
 * samples are stream-copied verbatim into a fresh [MediaMuxer] output, and the muxer only
 * writes a location atom when [MediaMuxer.setLocation] is called — which this never does —
 * so the moov/udta it emits carries no coordinates. Picture quality is untouched because
 * no decode/encode happens; the bitstream is identical.
 */
object VideoMetadataStripper {

    /**
     * Stream-copies every track of [uri] into [outFile], producing a video with no
     * container location atom. Source orientation is preserved via the muxer's
     * orientation hint (read from [MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION]).
     *
     * @return true on success; false (with the partial output deleted) on any failure, so
     *   the caller can fall back to uploading the original bytes.
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

            // Carry the source's display rotation forward — without the hint a portrait
            // capture would play back sideways even though the pixels are untouched.
            val rotation = runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(context, Uri.parse(uri))
                val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                mmr.release()
                r
            }.getOrDefault(0)
            muxer.setOrientationHint(((rotation % 360) + 360) % 360)

            // Map every source track (video, audio, timed text, …) to an output track so
            // nothing is dropped on the way through.
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
                    // Tracks without KEY_MAX_INPUT_SIZE can carry single samples larger
                    // than the 1 MiB floor (4K/HEVC keyframes) — readSampleData then
                    // throws instead of truncating. Grow and retry rather than failing
                    // the whole strip, which would silently upload the unstripped file.
                    val read = try {
                        extractor.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        // Cap the retry growth — a non-size IllegalArgumentException
                        // would otherwise loop forever.
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

    /** Hard ceiling for the sample-buffer grow-on-retry loop: at or above this the
     *  [IllegalArgumentException] is rethrown instead of doubling again, so a non-size
     *  IllegalArgumentException can't loop forever. */
    internal const val MAX_SAMPLE_BUFFER_BYTES = 64 shl 20

    /**
     * Buffer-growth cap decision for the [readSampleData] retry loop, factored out of the
     * inline catch so the branch is unit-testable. Given the current buffer [currentCapacity],
     * returns the next (doubled) capacity to retry with, or null when the cap is reached and
     * the caller must rethrow. Pure — no framework types.
     */
    internal fun nextSampleBufferCapacity(currentCapacity: Int): Int? {
        if (currentCapacity >= MAX_SAMPLE_BUFFER_BYTES) return null
        return currentCapacity * 2
    }
}
