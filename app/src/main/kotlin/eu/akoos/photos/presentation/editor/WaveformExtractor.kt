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

package eu.akoos.photos.presentation.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes an audio (or a video's audio track) into a small normalised amplitude envelope for drawing a
 * waveform in the editor's audio tracks. Fully best-effort: any failure (no audio track, an unsupported
 * codec, a decoder abort) returns null and the caller falls back to a plain block, so a waveform is
 * never load-bearing. Runs off the main thread; keep the bucket count small (a track is only a few
 * hundred pixels wide) so a large file decodes quickly.
 */
object WaveformExtractor {

    suspend fun extract(context: Context, uri: String, buckets: Int = 200): FloatArray? =
        withContext(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor().apply { setDataSource(context, parsed, null) }
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i; format = f; break
                    }
                }
                val fmt = format ?: return@withContext null
                if (trackIndex < 0) return@withContext null
                extractor.selectTrack(trackIndex)
                val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return@withContext null

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val sums = DoubleArray(buckets)
                val counts = LongArray(buckets)
                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false
                var guard = 0
                while (!sawOutputEOS && guard < 100_000) {
                    guard++
                    if (!sawInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            if (buf == null) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    val pts = extractor.sampleTime
                                    codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                var peak = 0
                                while (shorts.hasRemaining()) {
                                    val s = kotlin.math.abs(shorts.get().toInt())
                                    if (s > peak) peak = s
                                }
                                val bucket = if (durationUs > 0L) {
                                    ((info.presentationTimeUs.toDouble() / durationUs) * buckets).toInt().coerceIn(0, buckets - 1)
                                } else {
                                    0
                                }
                                sums[bucket] += peak.toDouble()
                                counts[bucket]++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }

                val out = FloatArray(buckets)
                var max = 1.0
                for (b in 0 until buckets) {
                    val v = if (counts[b] > 0) sums[b] / counts[b] else 0.0
                    out[b] = v.toFloat()
                    if (v > max) max = v
                }
                for (b in 0 until buckets) out[b] = (out[b] / max).toFloat().coerceIn(0f, 1f)
                // Fill silent gaps (buckets no buffer landed in) from neighbours so the line stays continuous.
                for (b in 1 until buckets) if (out[b] == 0f && counts[b] == 0L) out[b] = out[b - 1] * 0.8f
                out
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
            }
        }
}
