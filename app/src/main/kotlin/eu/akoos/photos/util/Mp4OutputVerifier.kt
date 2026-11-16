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

package eu.akoos.photos.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "Mp4OutputVerifier"

/**
 * Reads back an MP4 the app produced from a video before that file is uploaded or written over the
 * user's original. A muxer that fails at the end leaves a file without its moov box, which no player can
 * open, so a file is accepted only when its top-level boxes run cleanly to the end with a moov among them
 * and the platform reads it back with the same kinds of tracks and about the same duration as the video
 * it was made from.
 */
object Mp4OutputVerifier {

    /** What the platform reads from one video: its track count, whether it has picture and sound, and its duration. */
    data class Probe(val trackCount: Int, val hasVideo: Boolean, val hasAudio: Boolean, val durationMs: Long)

    /** Smallest duration gap tolerated between a source and its output, for edit lists and audio priming. */
    internal const val DURATION_SLACK_MS = 2_000L

    /**
     * Whether [output], made from the video at [sourceUri], is safe to use in its place. [sameTracks]
     * demands the exact track count, for a stream copy that must keep every track.
     */
    fun verify(context: Context, sourceUri: String, output: File, sameTracks: Boolean): Boolean {
        if (!output.isFile || output.length() <= 0L) return false
        if (!hasCompleteMoov(output)) {
            Log.w(TAG, "output has no complete moov box")
            return false
        }
        val source = probe { it.setDataSource(context, Uri.parse(sourceUri), null) } ?: return false
        val produced = probe { it.setDataSource(output.absolutePath) } ?: return false
        val ok = accepts(source, produced, sameTracks)
        if (!ok) Log.w(TAG, "output does not match its source: source=$source output=$produced")
        return ok
    }

    /** Whether [output] stands in for [source]: same kinds of tracks, a real duration close to the source's. */
    internal fun accepts(source: Probe, output: Probe, sameTracks: Boolean): Boolean {
        if (output.trackCount <= 0 || output.durationMs <= 0L) return false
        if (source.hasVideo && !output.hasVideo) return false
        if (source.hasAudio && !output.hasAudio) return false
        if (sameTracks && output.trackCount != source.trackCount) return false
        if (source.durationMs > 0L) {
            val slack = maxOf(DURATION_SLACK_MS, source.durationMs / 20)
            if (kotlin.math.abs(output.durationMs - source.durationMs) > slack) return false
        }
        return true
    }

    /** Whether the top-level boxes of [file] tile it exactly and one of them is a moov. */
    fun hasCompleteMoov(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            topLevelBoxesCompleteWithMoov(raf.length()) { offset, into ->
                raf.seek(offset)
                raf.readFully(into)
            }
        }
    }.getOrDefault(false)

    /**
     * Walks the top-level boxes of a [length]-byte file through [read]. True only when every box header is
     * well formed, no box runs past the end, the boxes end exactly at [length], and a moov was among them.
     */
    internal fun topLevelBoxesCompleteWithMoov(length: Long, read: (offset: Long, into: ByteArray) -> Unit): Boolean {
        var offset = 0L
        var sawMoov = false
        val header = ByteArray(8)
        val large = ByteArray(8)
        while (offset < length) {
            if (length - offset < 8) return false
            read(offset, header)
            val size32 = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val boxSize = when (size32) {
                0L -> length - offset
                1L -> {
                    if (length - offset < 16) return false
                    read(offset + 8, large)
                    u64(large)
                }
                else -> size32
            }
            val headerSize = if (size32 == 1L) 16L else 8L
            if (boxSize < headerSize || boxSize > length - offset) return false
            if (type == "moov") sawMoov = true
            offset += boxSize
        }
        return sawMoov && offset == length
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray): Long = (u32(b, 0) shl 32) or u32(b, 4)

    private fun probe(setSource: (MediaExtractor) -> Unit): Probe? {
        val extractor = MediaExtractor()
        return try {
            setSource(extractor)
            var hasVideo = false
            var hasAudio = false
            var longestTrackUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    longestTrackUs = maxOf(longestTrackUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            Probe(extractor.trackCount, hasVideo, hasAudio, longestTrackUs / 1000)
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
