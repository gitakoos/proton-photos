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
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioMixer"

/**
 * PCM-level audio editor for [VideoReencoder]: decodes source/overlay, scales or mixes at the
 * user's gains, re-encodes AAC LC. Only invoked when PCM processing is needed — the gain=1.0
 * single-track cases use [VideoReencoder.copyAudioSamples]'s stream-copy fast path.
 * Intermediate PCM is 16-bit interleaved; resampling is linear (fine for 44.1↔48 kHz).
 */
internal object AudioMixer {

    /**
     * Runs the mix/gain pipeline and writes AAC samples to [muxer] track [audioMuxerTrackIdx].
     * Caller must have already addTrack'd an AAC LC format (via [synthesizeAacFormat]) and started the muxer.
     */
    fun mixAndEncodeAudio(
        context: Context,
        sourceUri: Uri,
        overlayUri: Uri?,
        sourceTrimStartUs: Long,
        sourceTrimEndUs: Long,
        overlayTrimStartUs: Long,
        overlayTrimEndUs: Long,
        sourceGain: Float,
        overlayGain: Float,
        targetSampleRate: Int,
        targetChannels: Int,
        muxer: MediaMuxer,
        audioMuxerTrackIdx: Int,
    ) {
        val useSource = sourceGain > 0.001f
        val useOverlay = overlayUri != null && overlayGain > 0.001f
        if (!useSource && !useOverlay) return

        val srcPcm = if (useSource) decodeAudioToPcm(
            context, sourceUri, sourceTrimStartUs, sourceTrimEndUs,
            targetSampleRate, targetChannels,
        ) else null
        val ovlPcm = if (useOverlay) decodeAudioToPcm(
            context, overlayUri!!, overlayTrimStartUs, overlayTrimEndUs,
            targetSampleRate, targetChannels,
        ) else null

        val mixed = mixPcm(srcPcm, ovlPcm, sourceGain, overlayGain)
        if (mixed.isEmpty()) return

        val durationUs = (mixed.size.toLong() * 1_000_000L) /
            (targetSampleRate.toLong() * targetChannels.toLong())
        encodePcmToAac(mixed, targetSampleRate, targetChannels, durationUs, muxer, audioMuxerTrackIdx)
    }

    /**
     * AAC LC [MediaFormat] with a pre-computed `csd-0` so the caller can addTrack before muxer.start
     * without spinning up a probe encoder to read INFO_OUTPUT_FORMAT_CHANGED.
     */
    fun synthesizeAacFormat(sampleRate: Int, channelCount: Int, bitRate: Int = 192_000): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(aacLcCsd(sampleRate, channelCount)))
        }

    /** First audio track's (sample rate, channel count) off [uri], or null when there's no audio track. */
    fun probeAudioFormat(context: Context, uri: Uri): Pair<Int, Int>? {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                    val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    return sr to ch.coerceIn(1, 2)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "probeAudioFormat failed: ${e.message}")
            null
        } finally {
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }
    }

    // ── PCM decode ────────────────────────────────────────────────────────────

    /**
     * Decodes [uri]'s audio track to interleaved 16-bit PCM, sliced to [trimStartUs]..[trimEndUs],
     * resampled to [targetSampleRate] and remixed to [targetChannels].
     */
    private fun decodeAudioToPcm(
        context: Context,
        uri: Uri,
        trimStartUs: Long,
        trimEndUs: Long,
        targetSampleRate: Int,
        targetChannels: Int,
    ): ShortArray {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return ShortArray(0)
            extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { trackIdx = i; break }
            }
            if (trackIdx < 0) return ShortArray(0)
            extractor.selectTrack(trackIdx)
            val inputFormat = extractor.getTrackFormat(trackIdx)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return ShortArray(0)
            val srcSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else targetSampleRate
            val srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else targetChannels

            extractor.seekTo(trimStartUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            // Primitive ShortArray, not ArrayList<Short> (boxing OOM'd long clips). Pre-size to the
            // trim window + 5% headroom, then array-double if the decoder produces more. The hard cap
            // turns a pathologically long clip into a catchable error (overlay audio is dropped)
            // instead of an uncatchable OutOfMemoryError that would crash the whole save.
            val maxPcmSamples = 48_000_000 // ~96 MB, roughly 9 min of 44.1 kHz stereo
            val expectedSamples = (((trimEndUs - trimStartUs).coerceAtLeast(0L) / 1_000_000.0) *
                srcSampleRate.toDouble() * srcChannels.toDouble() * 1.05).toLong().coerceAtLeast(1024L)
            var accum = ShortArray(expectedSamples.coerceIn(1024L, maxPcmSamples.toLong()).toInt())
            var accumSize = 0
            fun appendShorts(src: java.nio.ShortBuffer) {
                val need = src.remaining()
                if (accumSize + need > maxPcmSamples) error("audio clip exceeds the in-memory mix cap")
                if (accumSize + need > accum.size) {
                    val newSize = maxOf(accum.size * 2, accumSize + need).coerceAtMost(maxPcmSamples)
                    val grown = ShortArray(newSize)
                    System.arraycopy(accum, 0, grown, 0, accumSize)
                    accum = grown
                }
                src.get(accum, accumSize, need)
                accumSize += need
            }

            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        val pts = extractor.sampleTime
                        if (sampleSize < 0 || pts > trimEndUs) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    if (info.size > 0 && info.presentationTimeUs >= trimStartUs) {
                        val out = decoder.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        appendShorts(out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
            }

            val raw = if (accumSize == accum.size) accum else accum.copyOf(accumSize)
            // Rechannel before resample to keep the mid-step buffer smaller.
            val rechannelled = convertChannels(raw, srcChannels, targetChannels)
            return resample(rechannelled, srcSampleRate, targetSampleRate, targetChannels)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decodeAudioToPcm: clip too large to mix in memory, dropping overlay audio for $uri")
            return ShortArray(0)
        } catch (e: Exception) {
            Log.w(TAG, "decodeAudioToPcm failed for $uri: ${e.message}")
            return ShortArray(0)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }
    }

    // ── Channel + sample-rate normalization ───────────────────────────────────

    /** Mono → stereo duplicates samples; stereo → mono averages L/R; same → identity. */
    private fun convertChannels(input: ShortArray, srcChannels: Int, dstChannels: Int): ShortArray {
        if (srcChannels == dstChannels) return input
        return when {
            srcChannels == 1 && dstChannels == 2 -> ShortArray(input.size * 2).also { out ->
                for (i in input.indices) {
                    out[i * 2] = input[i]
                    out[i * 2 + 1] = input[i]
                }
            }
            srcChannels == 2 && dstChannels == 1 -> ShortArray(input.size / 2).also { out ->
                for (i in out.indices) {
                    out[i] = ((input[i * 2].toInt() + input[i * 2 + 1].toInt()) / 2).toShort()
                }
            }
            else -> input // unsupported combinations fall back to identity
        }
    }

    /** Linear-interpolation resampler — transparent enough for the 44.1↔48 kHz ratios we hit. */
    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int, channels: Int): ShortArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val srcFrames = input.size / channels
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstFrames = (srcFrames / ratio).toInt().coerceAtLeast(0)
        val out = ShortArray(dstFrames * channels)
        for (df in 0 until dstFrames) {
            val srcPos = df * ratio
            val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val frac = srcPos - i0
            for (c in 0 until channels) {
                val s0 = input[i0 * channels + c].toInt()
                val s1 = input[i1 * channels + c].toInt()
                val interp = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                out[df * channels + c] = interp.toShort()
            }
        }
        return out
    }

    // ── Mix ───────────────────────────────────────────────────────────────────

    /** Mixes source + overlay PCM at the given gains, clamped to int16; the longer input plays on alone. */
    private fun mixPcm(src: ShortArray?, ovl: ShortArray?, srcGain: Float, ovlGain: Float): ShortArray {
        val a = src ?: ShortArray(0)
        val b = ovl ?: ShortArray(0)
        val length = maxOf(a.size, b.size)
        val out = ShortArray(length)
        for (i in 0 until length) {
            val sv = if (i < a.size) (a[i] * srcGain) else 0f
            val ov = if (i < b.size) (b[i] * ovlGain) else 0f
            out[i] = (sv + ov).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ── AAC encode → muxer ────────────────────────────────────────────────────

    private fun encodePcmToAac(
        pcm: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        durationUs: Long,
        muxer: MediaMuxer,
        muxerTrackIdx: Int,
    ) {
        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            // Sized for our ~20 ms slices so the encoder doesn't reject buffer-too-small.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            // 1024 PCM frames per AAC frame is the canonical block size for AAC LC.
            val framesPerBlock = 1024
            val samplesPerBlock = framesPerBlock * channelCount
            val totalFrames = pcm.size / channelCount
            var feedIdx = 0
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (feedIdx >= totalFrames) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val frames = minOf(framesPerBlock, totalFrames - feedIdx)
                            val samples = frames * channelCount
                            val byteBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            byteBuf.put(pcm, feedIdx * channelCount, samples)
                            val pts = (feedIdx.toLong() * 1_000_000L) / sampleRate.toLong()
                            encoder.queueInputBuffer(inIdx, 0, samples * 2, pts, 0)
                            feedIdx += frames
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* csd-0 already in muxer */ }
                    outIdx >= 0 -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        // Drop the config buffer: muxer already has csd-0; a second copy corrupts playback.
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (info.size > 0 && !isConfig) {
                            val out = encoder.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerTrackIdx, out, info)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
            Log.d(TAG, "encodePcmToAac: encoded ${pcm.size} samples → durationUs≈$durationUs")
        } catch (e: Exception) {
            Log.w(TAG, "encodePcmToAac failed: ${e.message}")
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    // ── AAC LC AudioSpecificConfig (csd-0) ───────────────────────────────────

    /**
     * 2-byte AAC LC AudioSpecificConfig used as `csd-0` when addTrack precedes the encoder.
     * Layout per ISO/IEC 14496-3: 5b audioObjectType(LC=2), 4b samplingFreqIndex, 4b channelConfig, 3b pad.
     */
    private fun aacLcCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val srIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> 4 // default 44100
        }
        val profile = 2 // AAC LC
        val b0 = ((profile shl 3) or (srIndex shr 1)) and 0xFF
        val b1 = (((srIndex and 0x01) shl 7) or ((channelCount and 0x0F) shl 3)) and 0xFF
        return byteArrayOf(b0.toByte(), b1.toByte())
    }
}
