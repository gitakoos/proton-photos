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
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * Decode-then-encode video transcoder (AOSP CTS pattern: decoder → OutputSurface → GL crop →
 * InputSurface → encoder → muxer). Used for crop/rotation, which stream-copy can't satisfy.
 * [onProgress] is invoked from the encoder thread with 0f..1f.
 */
internal class VideoReencoder(private val context: Context) {

    /**
     * @param sourceUri input video (must be a content:// or file:// URI we can openFileDescriptor on)
     * @param outputFile destination .mp4 — caller may move/copy it into MediaStore after
     * @param trimStartUs / trimEndUs trim range in microseconds (relative to source timeline)
     * @param cropLeft/cropTop/cropWidth/cropHeight crop rect in source video pixels
     * @param rotationDegrees pixel rotation to burn in (0/90/180/270 — anything else is rounded)
     * @param audioOverlayUri picked audio replacement — null means use source's audio track
     * @param audioTrimStartUs / audioTrimEndUs only used when audioOverlayUri != null
     * @param onProgress called with 0..1 progress estimate
     */
    fun transcode(
        sourceUri: Uri,
        outputFile: File,
        trimStartUs: Long,
        trimEndUs: Long,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        audioOverlayUri: Uri?,
        audioTrimStartUs: Long,
        audioTrimEndUs: Long,
        /** Source-audio gain [0..1]: 0 drops the original, 1 keeps it, between scales via PCM mix. */
        originalAudioGain: Float = 1.0f,
        /** Overlay-music gain [0..1], ignored when [audioOverlayUri] is null. */
        musicAudioGain: Float = 1.0f,
        onProgress: (Float) -> Unit,
        /** Cooperative cancellation probe checked per loop iteration; false → release codecs and throw. */
        isActive: () -> Boolean = { true },
    ) {
        var sourcePfd: ParcelFileDescriptor? = null
        var audioPfd: ParcelFileDescriptor? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null

        try {
            sourcePfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Could not open source video for reading")
            videoExtractor = MediaExtractor().apply { setDataSource(sourcePfd.fileDescriptor) }
            val videoTrackIdx = selectTrack(videoExtractor, "video/")
                ?: error("Source has no video track")
            videoExtractor.selectTrack(videoTrackIdx)
            val srcVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            // Encoder dim = crop region size, rounded to even (H.264 needs it), min 16. Rotation goes
            // through the muxer orientation hint below, NOT GL pixels — burning it in double-applied
            // source rotation on chipsets where the decoder's SurfaceTexture already rotates by tag.
            val encOutW = (cropWidth and 1.inv()).coerceAtLeast(16)
            val encOutH = (cropHeight and 1.inv()).coerceAtLeast(16)
            val decW = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, -1)
            val decH = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, -1)
            val srcStdLog = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD).toString() else "none"
            val srcRngLog = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE))
                srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE).toString() else "none"
            val srcTrfLog = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER).toString() else "none"
            android.util.Log.d(
                "VideoReencoder",
                "transcode dims: src=${decW}x${decH} crop=(${cropLeft},${cropTop} ${cropWidth}x${cropHeight}) " +
                    "rot=$rotationDegrees(via orientation hint) encOut=${encOutW}x${encOutH}",
            )
            android.util.Log.d(
                "VideoReencoder",
                "source color: standard=$srcStdLog range=$srcRngLog transfer=$srcTrfLog (BT2020=6, HLG=7, PQ=6/13 — see MediaFormat constants)",
            )

            val frameRate = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
            // KEY_BIT_RATE is often absent on phone captures; fall back to MediaMetadataRetriever's
            // container bitrate minus a 192 kbps audio budget, else the estimate path under-encodes.
            val srcBitrate: Int = run {
                val fromFormat = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_BIT_RATE, 0)
                if (fromFormat > 0) return@run fromFormat
                runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(context, sourceUri)
                    val totalBr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull() ?: 0
                    mmr.release()
                    (totalBr - 192_000).coerceAtLeast(0)
                }.getOrDefault(0)
            }
            val encBitrate = chooseEncoderBitrate(srcBitrate, encOutW, encOutH, frameRate)
            android.util.Log.d(
                "VideoReencoder",
                "transcode bitrate: src=$srcBitrate fps=$frameRate -> enc=$encBitrate (${encBitrate / 1_000_000.0} Mbps)",
            )
            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encOutW, encOutH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, encBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Force High@4.2 so the encoder keeps B-frames/CABAC instead of downgrading to
                // Baseline (halves quality at the same bitrate). runCatching: older chipsets fall back.
                runCatching {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                }
                // Tag explicit color attrs (default BT.709 limited-range SDR) so players use the right
                // YUV→RGB matrix; without them many SoCs tag BT.601 and reds/skin tones desaturate.
                val srcColorStandard = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                    srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                else MediaFormat.COLOR_STANDARD_BT709
                val srcColorRange = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE))
                    srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE)
                else MediaFormat.COLOR_RANGE_LIMITED
                val srcColorTransfer = if (srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    srcVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                else MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                setInteger(MediaFormat.KEY_COLOR_STANDARD, srcColorStandard)
                setInteger(MediaFormat.KEY_COLOR_RANGE, srcColorRange)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, srcColorTransfer)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            outputSurface = OutputSurface()
            val decoderMime = srcVideoFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Source video track has no MIME type")
            decoder = MediaCodec.createDecoderByType(decoderMime)
            // Clear KEY_ROTATION: on Exynos the SurfaceTexture transform auto-applies the rotation tag,
            // which then double-rotated against the muxer hint. With 0, rotation comes only from the hint.
            srcVideoFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            // Hint the source color space (default BT.709 SDR) so the SurfaceTexture sampler picks the
            // right YUV→RGB matrix; many drivers default to BT.601 and desaturate BT.709 captures.
            if (!srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                srcVideoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            }
            if (!srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                srcVideoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            if (!srcVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                srcVideoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            decoder.configure(srcVideoFormat, outputSurface.surface, null, 0)
            decoder.start()

            // Video track added lazily (need the encoder's INFO_OUTPUT_FORMAT_CHANGED for final SPS/PPS).
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Rotation via container orientation hint (matches muxTrimmed); GL pixel-rotation double-rotated.
            val outputRotation = ((rotationDegrees % 360) + 360) % 360
            muxer.setOrientationHint(outputRotation)

            videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Audio path: stream-copy (one track at gain 1.0, lossless), decode-encode (partial gain or
            // mix, via AudioMixer), or silent (both gains ~0). See AudioMixer for the full reasoning.
            val sourceHasAudio = run {
                val probe = MediaExtractor().apply { setDataSource(sourcePfd.fileDescriptor) }
                val has = selectTrack(probe, "audio/") != null
                probe.release()
                has
            }
            val useSourceAudio = sourceHasAudio && originalAudioGain > 0.001f
            val useOverlayAudio = audioOverlayUri != null && musicAudioGain > 0.001f
            val needsAudioEncode =
                (useSourceAudio && useOverlayAudio) ||
                (useSourceAudio && originalAudioGain < 0.999f) ||
                (useOverlayAudio && !useSourceAudio && musicAudioGain < 0.999f)
            val streamCopySource = useSourceAudio && !useOverlayAudio && !needsAudioEncode
            val streamCopyOverlay = useOverlayAudio && !useSourceAudio && !needsAudioEncode

            // Stream-copy extractor (mix path opens its own inside AudioMixer.decodeAudioToPcm).
            if (streamCopyOverlay) {
                audioPfd = context.contentResolver.openFileDescriptor(audioOverlayUri!!, "r")
                    ?: error("Could not open audio overlay for reading")
                audioExtractor = MediaExtractor().apply { setDataSource(audioPfd.fileDescriptor) }
            } else if (streamCopySource) {
                val audioPfd2 = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (audioPfd2 != null) {
                    audioPfd = audioPfd2
                    audioExtractor = MediaExtractor().apply { setDataSource(audioPfd.fileDescriptor) }
                }
            }
            val audioTrackIdx = audioExtractor?.let { selectTrack(it, "audio/") }
            if (audioTrackIdx != null) {
                audioExtractor!!.selectTrack(audioTrackIdx)
            }

            // AAC target format for the mix path: prefer the source's rate, fall back to overlay.
            val mixTargetFormat: Pair<Int, Int>? = if (needsAudioEncode) {
                AudioMixer.probeAudioFormat(context, sourceUri)
                    ?: audioOverlayUri?.let { AudioMixer.probeAudioFormat(context, it) }
                    ?: (44100 to 2)
            } else null

            // Rotation 0 here — handled by the muxer orientation hint, not GL pixels (see above).
            val cropMatrix = CropMatrix.build(
                sourceWidth = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, encOutW),
                sourceHeight = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, encOutH),
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropWidth = cropWidth.coerceAtLeast(2),
                cropHeight = cropHeight.coerceAtLeast(2),
                rotationDegrees = 0,
            )

            // ── Video re-encode loop ───────────────────────────────────────────────
            val totalUs = (trimEndUs - trimStartUs).coerceAtLeast(1L)
            var videoMuxerTrackIdx = -1
            var audioMuxerTrackIdx = -1
            var muxerStarted = false

            var inputDone = false
            var decoderOutputDone = false
            var encoderOutputDone = false

            val timeoutUs = 10_000L
            val bufferInfo = MediaCodec.BufferInfo()
            var lastReportedProgress = -1f

            while (!encoderOutputDone) {
                if (!isActive()) throw CancellationException("Video transcode cancelled")
                // Feed encoded video samples into decoder.
                if (!inputDone) {
                    val inputIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIdx >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIdx)!!
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        val pts = videoExtractor.sampleTime
                        if (sampleSize < 0 || pts > trimEndUs) {
                            decoder.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIdx, 0, sampleSize, pts, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                // Drain encoder output → muxer.
                var encoderBusy = true
                while (encoderBusy && !encoderOutputDone) {
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderBusy = false
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) error("Encoder output format changed twice")
                            val outFormat = encoder.outputFormat
                            videoMuxerTrackIdx = muxer.addTrack(outFormat)
                            // Register audio track before muxer.start() (addTrack is forbidden after):
                            // stream-copy uses the source format; mix synthesizes an AAC LC format.
                            if (audioExtractor != null && audioMuxerTrackIdx < 0) {
                                for (i in 0 until audioExtractor.trackCount) {
                                    val fmt = audioExtractor.getTrackFormat(i)
                                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                                    if (mime.startsWith("audio/")) {
                                        audioMuxerTrackIdx = muxer.addTrack(fmt)
                                        break
                                    }
                                }
                            } else if (needsAudioEncode && mixTargetFormat != null && audioMuxerTrackIdx < 0) {
                                val (sr, ch) = mixTargetFormat
                                audioMuxerTrackIdx = muxer.addTrack(AudioMixer.synthesizeAacFormat(sr, ch))
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val encodedBuffer = encoder.getOutputBuffer(outIdx)
                                ?: error("encoder.getOutputBuffer returned null")
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0 // config bytes already taken from outputFormat
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encodedBuffer.position(bufferInfo.offset)
                                encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoMuxerTrackIdx, encodedBuffer, bufferInfo)
                                val progress = (bufferInfo.presentationTimeUs.toFloat() /
                                    (trimEndUs - trimStartUs).toFloat()).coerceIn(0f, 1f)
                                if (progress - lastReportedProgress >= 0.02f || progress >= 1f) {
                                    lastReportedProgress = progress
                                    onProgress(progress)
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderOutputDone = true
                            }
                        }
                    }
                }

                // Drain decoder output → OutputSurface → encoder InputSurface.
                if (!decoderOutputDone) {
                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* informational */ }
                        outIdx >= 0 -> {
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val pts = bufferInfo.presentationTimeUs
                            val withinRange = pts in trimStartUs..trimEndUs
                            // releaseOutputBuffer(_, true) renders the frame to OutputSurface.surface.
                            decoder.releaseOutputBuffer(outIdx, withinRange && bufferInfo.size > 0)
                            if (withinRange && bufferInfo.size > 0) {
                                outputSurface.awaitNewImage()
                                outputSurface.drawImage(cropMatrix)
                                // Time origin shifts so output starts at 0.
                                inputSurface.setPresentationTime((pts - trimStartUs) * 1000L)
                                inputSurface.swapBuffers()
                            }
                            if (isEos) {
                                encoder.signalEndOfInputStream()
                                decoderOutputDone = true
                            }
                        }
                    }
                }
            }

            // Audio: stream-copy the single track, or mix source+overlay PCM at user gains and AAC-encode.
            if (audioMuxerTrackIdx >= 0 && audioExtractor != null && !needsAudioEncode) {
                copyAudioSamples(
                    extractor = audioExtractor,
                    muxer = muxer,
                    muxerTrackIdx = audioMuxerTrackIdx,
                    startUs = if (streamCopyOverlay) audioTrimStartUs else trimStartUs,
                    endUs = if (streamCopyOverlay) audioTrimEndUs else trimEndUs,
                    timeOriginUs = if (streamCopyOverlay) audioTrimStartUs else trimStartUs,
                    isActive = isActive,
                )
            } else if (audioMuxerTrackIdx >= 0 && needsAudioEncode && mixTargetFormat != null) {
                val (sr, ch) = mixTargetFormat
                AudioMixer.mixAndEncodeAudio(
                    context = context,
                    sourceUri = sourceUri,
                    overlayUri = audioOverlayUri,
                    sourceTrimStartUs = trimStartUs,
                    sourceTrimEndUs = trimEndUs,
                    overlayTrimStartUs = audioTrimStartUs,
                    overlayTrimEndUs = audioTrimEndUs,
                    sourceGain = if (useSourceAudio) originalAudioGain else 0f,
                    overlayGain = if (useOverlayAudio) musicAudioGain else 0f,
                    targetSampleRate = sr,
                    targetChannels = ch,
                    muxer = muxer,
                    audioMuxerTrackIdx = audioMuxerTrackIdx,
                )
            }

            onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            // OutputSurface before InputSurface: its GL texture lives in InputSurface's EGL context.
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { sourcePfd?.close() }
            runCatching { audioPfd?.close() }
        }
    }

    /**
     * Stream-copy video (lossless) while mixing audio via [transcode]'s pipeline. Used for audio-only
     * edits without crop: re-encoding would lose HDR (8-bit GL can't preserve BT.2020/PQ) and waste CPU.
     */
    fun streamCopyVideoWithMixedAudio(
        sourceUri: Uri,
        outputFile: File,
        trimStartUs: Long,
        trimEndUs: Long,
        rotationDegrees: Int,
        audioOverlayUri: Uri?,
        audioTrimStartUs: Long,
        audioTrimEndUs: Long,
        originalAudioGain: Float,
        musicAudioGain: Float,
        onProgress: (Float) -> Unit,
        /** Cancellation probe; see [transcode]. */
        isActive: () -> Boolean = { true },
    ) {
        var sourcePfd: ParcelFileDescriptor? = null
        var videoExtractor: MediaExtractor? = null
        var streamCopyAudioPfd: ParcelFileDescriptor? = null
        var streamCopyAudioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            sourcePfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Could not open source for stream-copy")
            videoExtractor = MediaExtractor().apply { setDataSource(sourcePfd.fileDescriptor) }
            val videoTrackIdx = selectTrack(videoExtractor, "video/")
                ?: error("No video track in source")
            videoExtractor.selectTrack(videoTrackIdx)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputRotation = ((rotationDegrees % 360) + 360) % 360
            muxer.setOrientationHint(outputRotation)
            val videoMuxerTrack = muxer.addTrack(videoFormat)

            // Audio decision tree — same logic as transcode's audio path.
            val sourceHasAudio = run {
                val probePfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                val probe = MediaExtractor().apply { setDataSource(probePfd!!.fileDescriptor) }
                val has = selectTrack(probe, "audio/") != null
                probe.release()
                probePfd?.close()
                has
            }
            val useSrc = sourceHasAudio && originalAudioGain > 0.001f
            val useOvl = audioOverlayUri != null && musicAudioGain > 0.001f
            val needsAudioEncode =
                (useSrc && useOvl) ||
                (useSrc && originalAudioGain < 0.999f) ||
                (useOvl && !useSrc && musicAudioGain < 0.999f)
            val streamCopySrc = useSrc && !useOvl && !needsAudioEncode
            val streamCopyOvl = useOvl && !useSrc && !needsAudioEncode

            var audioMuxerTrack = -1
            val mixTargetFormat: Pair<Int, Int>?

            if (streamCopySrc) {
                streamCopyAudioPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                streamCopyAudioExtractor = MediaExtractor().apply {
                    setDataSource(streamCopyAudioPfd!!.fileDescriptor)
                }
                val idx = selectTrack(streamCopyAudioExtractor, "audio/")!!
                streamCopyAudioExtractor.selectTrack(idx)
                audioMuxerTrack = muxer.addTrack(streamCopyAudioExtractor.getTrackFormat(idx))
                mixTargetFormat = null
            } else if (streamCopyOvl) {
                streamCopyAudioPfd = context.contentResolver.openFileDescriptor(audioOverlayUri!!, "r")
                streamCopyAudioExtractor = MediaExtractor().apply {
                    setDataSource(streamCopyAudioPfd!!.fileDescriptor)
                }
                val idx = selectTrack(streamCopyAudioExtractor, "audio/")!!
                streamCopyAudioExtractor.selectTrack(idx)
                audioMuxerTrack = muxer.addTrack(streamCopyAudioExtractor.getTrackFormat(idx))
                mixTargetFormat = null
            } else if (needsAudioEncode) {
                mixTargetFormat = AudioMixer.probeAudioFormat(context, sourceUri)
                    ?: audioOverlayUri?.let { AudioMixer.probeAudioFormat(context, it) }
                    ?: (44100 to 2)
                val (sr, ch) = mixTargetFormat
                audioMuxerTrack = muxer.addTrack(AudioMixer.synthesizeAacFormat(sr, ch))
            } else {
                mixTargetFormat = null
            }

            muxer.start()

            // ── Stream-copy video samples in the trim window ──
            videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val maxBuf = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1 shl 20)
            else 4 * 1024 * 1024
            val buf = ByteBuffer.allocate(maxBuf)
            val info = MediaCodec.BufferInfo()
            val totalUs = (trimEndUs - trimStartUs).coerceAtLeast(1L)
            var lastReported = -1f
            while (true) {
                if (!isActive()) throw CancellationException("Video stream-copy cancelled")
                buf.clear()
                val read = videoExtractor.readSampleData(buf, 0)
                if (read < 0) break
                val pts = videoExtractor.sampleTime
                if (pts > trimEndUs) break
                if (pts >= trimStartUs) {
                    info.offset = 0
                    info.size = read
                    info.presentationTimeUs = (pts - trimStartUs).coerceAtLeast(0L)
                    info.flags = extractorFlagsToBufferFlagsLocal(videoExtractor.sampleFlags)
                    muxer.writeSampleData(videoMuxerTrack, buf, info)
                    // Copy is fast but still report progress (capped every 2%) so the bar advances.
                    val p = ((pts - trimStartUs).toFloat() / totalUs.toFloat()) * 0.6f
                    if (p - lastReported >= 0.02f) {
                        lastReported = p
                        onProgress(p.coerceIn(0f, 0.6f))
                    }
                }
                videoExtractor.advance()
            }

            // ── Audio (stream-copy or mix-encode) ──
            if (audioMuxerTrack >= 0 && streamCopyAudioExtractor != null) {
                copyAudioSamples(
                    extractor = streamCopyAudioExtractor,
                    muxer = muxer,
                    muxerTrackIdx = audioMuxerTrack,
                    startUs = if (streamCopyOvl) audioTrimStartUs else trimStartUs,
                    endUs = if (streamCopyOvl) audioTrimEndUs else trimEndUs,
                    timeOriginUs = if (streamCopyOvl) audioTrimStartUs else trimStartUs,
                    isActive = isActive,
                )
            } else if (audioMuxerTrack >= 0 && needsAudioEncode && mixTargetFormat != null) {
                val (sr, ch) = mixTargetFormat
                AudioMixer.mixAndEncodeAudio(
                    context = context,
                    sourceUri = sourceUri,
                    overlayUri = audioOverlayUri,
                    sourceTrimStartUs = trimStartUs,
                    sourceTrimEndUs = trimEndUs,
                    overlayTrimStartUs = audioTrimStartUs,
                    overlayTrimEndUs = audioTrimEndUs,
                    sourceGain = if (useSrc) originalAudioGain else 0f,
                    overlayGain = if (useOvl) musicAudioGain else 0f,
                    targetSampleRate = sr,
                    targetChannels = ch,
                    muxer = muxer,
                    audioMuxerTrackIdx = audioMuxerTrack,
                )
            }
            onProgress(1f)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { sourcePfd?.close() }
            runCatching { streamCopyAudioExtractor?.release() }
            runCatching { streamCopyAudioPfd?.close() }
        }
    }

    /** Local copy of VideoEditorViewModel.extractorFlagsToBufferFlags to avoid cross-class plumbing. */
    private fun extractorFlagsToBufferFlagsLocal(extractorFlags: Int): Int {
        var flags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun copyAudioSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIdx: Int,
        startUs: Long,
        endUs: Long,
        timeOriginUs: Long,
        isActive: () -> Boolean = { true },
    ) {
        var maxBufferSize = 256 * 1024
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/") && fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxBufferSize = fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                break
            }
        }
        extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!isActive()) throw CancellationException("Audio copy cancelled")
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read < 0) break
            val pts = extractor.sampleTime
            if (pts > endUs) break
            if (pts >= startUs) {
                info.offset = 0
                info.size = read
                info.presentationTimeUs = (pts - timeOriginUs).coerceAtLeast(0L)
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(muxerTrackIdx, buffer, info)
            }
            if (!extractor.advance()) break
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    /**
     * H.264 bitrate matching the source so a re-encode doesn't degrade quality. Uses KEY_BIT_RATE
     * as-is when present (no cap — a 30 Mbps cap downgraded 4K), else a 0.12 bpp estimate (5–80 Mbps).
     */
    private fun chooseEncoderBitrate(srcBitrate: Int, width: Int, height: Int, fps: Int): Int {
        if (srcBitrate > 0) return srcBitrate
        val pixelRate = width.toLong() * height.toLong() * fps.toLong()
        val estimate = (pixelRate * 12L / 100L).toInt().coerceAtLeast(5_000_000) // 0.12 bpp
        return min(estimate, 80_000_000)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        if (!containsKey(key)) return default
        // Some encoders store KEY_FRAME_RATE (occasionally KEY_BIT_RATE) as a float; getInteger then
        // throws ClassCastException and aborts the whole re-encode. Read the float as a fallback,
        // then the default, so a crop or audio edit never crashes on those devices.
        return runCatching { getInteger(key) }.getOrElse {
            runCatching { getFloat(key).toInt() }.getOrDefault(default)
        }
    }
}
