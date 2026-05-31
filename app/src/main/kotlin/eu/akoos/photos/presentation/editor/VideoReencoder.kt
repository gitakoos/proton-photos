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
import kotlin.math.min

/**
 * Decode-then-encode video transcoder. Used when the user has applied a crop or wants
 * pixel-rotation; stream-copy can't satisfy either because the bitstream encodes the
 * full source frame at the source orientation.
 *
 * Pipeline (canonical AOSP CTS pattern, attributed):
 *
 *   MediaExtractor(video) ── encoded samples ──> MediaCodec(decoder)
 *                                                       │
 *                                                       ▼ (decoded RGB on Surface)
 *                                            [OutputSurface ext-OES tex]
 *                                                       │
 *                                                       ▼ GL draw with CropMatrix
 *                                            [InputSurface(encoder.createInputSurface)]
 *                                                       │
 *                                                       ▼
 *                            MediaCodec(encoder) ── encoded samples ──> MediaMuxer(video)
 *
 *   MediaExtractor(audio) ── encoded samples ──> MediaMuxer(audio)   [stream-copy]
 *
 * The audio side is always stream-copy — both the original audio track and the user's
 * picked overlay audio go through the same path. If [audioInputFd] is null we use the
 * original video's audio; otherwise we open a second MediaExtractor on the overlay file.
 *
 * Encoder bitrate strategy: cap at min(sourceBitrate * 1.2, 12 Mbps) so we never blow
 * out a small clip and so cropping doesn't quadruple the file size by accident.
 *
 * Progress reporting: [onProgress] is invoked from the encoder thread every few frames
 * with a value in 0f..1f. The ViewModel can publish this to the UI directly.
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
        /** Source-audio gain in [0..1]. 0 drops the original from the output; 1 keeps it
         *  unattenuated; intermediate values scale samples via PCM mix. */
        originalAudioGain: Float = 1.0f,
        /** Overlay-music gain in [0..1]. Ignored when [audioOverlayUri] is null. When both
         *  this and [originalAudioGain] are > 0 the two streams mix sample-by-sample. */
        musicAudioGain: Float = 1.0f,
        onProgress: (Float) -> Unit,
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

            // Encoder needs an EVEN width/height for H.264 — round down if the user
            // cropped to an odd number. Mobile encoders enforce 16-aligned input on
            // some chipsets, but 2-aligned is the safe lowest common denominator.
            //
            // SWAP for 90°/270° rotation: the GL pipeline rotates the cropped quad's
            // content in-place inside the NDC square, then the viewport maps NDC to the
            // encoder's output buffer. For a non-square crop with sideways rotation the
            // rotated content's aspect is the inverse of the source crop's — if we
            // leave encOutW × encOutH equal to cropWidth × cropHeight the output buffer
            // is "the wrong way around" and the encoder stretches/squishes the rotated
            // content to fit. Swapping the dims for 90°/270° produces the correct
            // post-rotation aspect, which is what users expect: a portrait clip rotated
            // 90° saves as a landscape file with no distortion.
            // Encoder dim = crop region size (in source pixels). Rotation is handled by
            // the muxer's orientation hint below — burning rotation into pixels with a GL
            // matrix was double-applying source rotation on some chipsets (the decoder's
            // SurfaceTexture intrinsic transform already rotates per source rotation tag)
            // and produced mirrored / stretched / wrongly-oriented saves. Orientation hint
            // is honoured by every modern player and matches what the editor preview
            // shows (ExoPlayer auto-rotates by hint + graphicsLayer adds user rotation).
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

            // Build encoder format. KEY_COLOR_FORMAT = COLOR_FormatSurface tells MediaCodec
            // we'll feed it via createInputSurface() — no manual YUV pushing required.
            val frameRate = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
            // Many phone captures don't expose KEY_BIT_RATE on the track-level MediaFormat —
            // fall back to MediaMetadataRetriever's container-level bitrate (video + audio
            // combined) and subtract a typical 192 kbps audio budget so we don't accidentally
            // over-encode. Without this fallback the estimate path kicks in and produces a
            // visibly worse re-encode than the source (user-reported "121 MB → 44 MB").
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
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 keyframe per second
                // H.264 profile/level. High @ Level 4.2 covers 4K @ 30fps at 60 Mbps —
                // overkill for most phone clips but ensures the encoder doesn't downgrade
                // to Baseline (which strips B-frames and CABAC, halving quality at the
                // same bitrate). Wrapped in runCatching so older chipsets that don't
                // support High profile fall back silently to Baseline rather than crashing.
                runCatching {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                }
                // Tag the encoded YUV with explicit color attrs so a player on the other
                // end interprets the same color space the source was authored in. Sources
                // that omit KEY_COLOR_STANDARD/RANGE/TRANSFER get BT.709 limited-range
                // SDR-video defaults — the de-facto standard for SDR phone capture, which
                // covers ~all of our editor inputs. Without explicit values the encoder
                // tagged outputs as platform-default (BT.601 on many SoCs) and players
                // applied the wrong YUV→RGB matrix, surfacing as desaturated reds and
                // shifted skin tones (user-reported "elvész a szín").
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

            // The decoder writes decoded frames into our external-OES texture surface.
            outputSurface = OutputSurface()
            val decoderMime = srcVideoFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Source video track has no MIME type")
            decoder = MediaCodec.createDecoderByType(decoderMime)
            // Force the decoder to output frames in raw byte orientation by clearing
            // KEY_ROTATION. On some chipsets (Samsung Exynos S22 confirmed) the
            // SurfaceTexture's intrinsic transform applies the source's rotation tag
            // automatically when KEY_ROTATION is present, which then double-rotated with
            // our orientation hint and squished portrait content into a landscape encoder
            // buffer. With KEY_ROTATION explicitly 0 the sampled content is always raw
            // bytes and rotation is handled exclusively by the muxer's orientation hint.
            srcVideoFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            // Tell the decoder which color space the source uses so the SurfaceTexture
            // sampler picks the correct YUV→RGB matrix. Without this hint many drivers
            // default to BT.601 limited-range conversion regardless of the source's
            // actual color standard — which is fine for SD video but desaturates the
            // reds and shifts skin tones on modern BT.709 phone captures (user-reported
            // "the saved video's colours are off"). Sources that omit the keys are
            // assumed to be standard BT.709 SDR limited-range — the de-facto convention
            // for everything our editor sees in practice.
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

            // Output muxer. Add video track lazily — encoder needs to surface its
            // INFO_OUTPUT_FORMAT_CHANGED before we know the final SPS/PPS to embed.
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Rotation = orientation hint in the output container. Mirrors muxTrimmed's
            // stream-copy convention so playback through any modern player (ExoPlayer,
            // VLC, the system gallery) ends up oriented the way the user previewed in
            // the editor. Burning rotation into pixels via the GL matrix instead led
            // to double-rotation on chipsets where the decoder's SurfaceTexture
            // already applies the source rotation — the user saw mirrored / sideways
            // saves that didn't match the editor preview.
            val outputRotation = ((rotationDegrees % 360) + 360) % 360
            muxer.setOrientationHint(outputRotation)

            videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // ── Decide audio path ─────────────────────────────────────────────────
            // Three modes (see AudioMixer doc-comment for full reasoning):
            //   1. STREAM-COPY: exactly one source has gain == 1.0 and the other is OFF.
            //      Fast path — no decode/encode. Lossless.
            //   2. DECODE-ENCODE: at least one track needs partial gain or both tracks need
            //      to be mixed. AudioMixer.mixAndEncodeAudio handles it; the muxer's audio
            //      track gets a pre-synthesized AAC LC format with csd-0 baked in so the
            //      addTrack happens before muxer.start, same as the stream-copy path.
            //   3. SILENT: both gains effectively 0 (or source has no audio and no overlay).
            //      audioExtractor stays null and the muxer is built video-only.
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

            // Open the appropriate stream-copy extractor (mix path bypasses these — it
            // opens its own extractors inside AudioMixer.decodeAudioToPcm).
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

            // Pre-compute the AAC encoder's target format (sample rate + channels) for
            // the mix path. Probe source first (its rate matches what the user is used
            // to hearing); fall back to overlay if source has no audio track.
            val mixTargetFormat: Pair<Int, Int>? = if (needsAudioEncode) {
                AudioMixer.probeAudioFormat(context, sourceUri)
                    ?: audioOverlayUri?.let { AudioMixer.probeAudioFormat(context, it) }
                    ?: (44100 to 2)
            } else null

            // Rotation passed as 0 — see encOutW/encOutH comment above. The output muxer
            // gets setOrientationHint(rotationDegrees) instead, which the player honors
            // on playback without us touching pixel orientation in GL.
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
                            // Register the audio track BEFORE muxer.start() — MediaMuxer
                            // forbids addTrack after start. Three sources of audio format:
                            //   • Stream-copy: use the source/overlay's MediaFormat directly
                            //   • Decode-encode (mix): synthesize an AAC LC format with csd-0
                            //     so we don't need to spin up a probe encoder just to learn it
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
                                // Codec config bytes — muxer already grabbed them from outputFormat.
                                bufferInfo.size = 0
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

            // ── Audio (stream-copy OR mix-encode) ─────────────────────────────────
            // Branch on what we decided up top:
            //   • Stream-copy → byte-for-byte copy the chosen single track (existing path)
            //   • Mix-encode  → decode source + overlay PCM, mix at user gains, AAC-encode
            if (audioMuxerTrackIdx >= 0 && audioExtractor != null && !needsAudioEncode) {
                copyAudioSamples(
                    extractor = audioExtractor,
                    muxer = muxer,
                    muxerTrackIdx = audioMuxerTrackIdx,
                    startUs = if (streamCopyOverlay) audioTrimStartUs else trimStartUs,
                    endUs = if (streamCopyOverlay) audioTrimEndUs else trimEndUs,
                    timeOriginUs = if (streamCopyOverlay) audioTrimStartUs else trimStartUs,
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
            // Release OutputSurface before InputSurface — the SurfaceTexture's GL texture
            // is owned by the EGL context that InputSurface holds.
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { sourcePfd?.close() }
            runCatching { audioPfd?.close() }
        }
    }

    /**
     * Stream-copy video samples (lossless, no re-encode) while running the audio path
     * through the same mix/encode pipeline [transcode] uses. Used when the user has
     * audio edits (overlay or gain != 0/1) but no crop — re-encoding video would lose
     * HDR (8-bit GL pipeline can't preserve BT.2020/PQ source dynamic range) and burns
     * CPU for nothing when we could just copy the bytes.
     *
     * Rotation is written via the muxer's orientation hint (same as muxTrimmed).
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

            // Audio decision tree — same logic as transcode's audio path so the user gets
            // identical behaviour whether or not crop forced the GL re-encode.
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
                    // Video copies very fast (no decode) but we still want a progress signal
                    // so the sheet shows the bar advance. Cap reports at every 2%.
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

    /** Local copy of extractorFlagsToBufferFlags (defined in VideoEditorViewModel) so
     *  this file can stream-copy without cross-class plumbing. */
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
    ) {
        // Use the already-selected audio track's format for buffer sizing.
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
     * Pick an H.264 bitrate that matches the source so a re-encode (crop / audio swap)
     * doesn't visibly degrade quality. Priority:
     *
     *  1. If the source reports KEY_BIT_RATE, use it AS-IS. A re-encode doesn't add
     *     visual information — using the source's own bitrate gives a faithful output
     *     for the cropped region. A 4K @ 60 Mbps source stays at 60 Mbps; a 1080p @
     *     15 Mbps source stays at 15 Mbps. No 30 Mbps cap because that punished 4K
     *     sources unfairly (user-reported as "the editor turns my 4K into 1080p
     *     quality").
     *
     *  2. If KEY_BIT_RATE is missing (some containers don't expose it), fall back to a
     *     0.12 bpp pixel-rate estimate with a 5 Mbps floor and a 80 Mbps ceiling. 0.12 bpp
     *     is roughly the bitrate phones use natively for 1080p30 (10 Mbps) and produces
     *     visually transparent H.264 output for typical user content.
     */
    private fun chooseEncoderBitrate(srcBitrate: Int, width: Int, height: Int, fps: Int): Int {
        if (srcBitrate > 0) return srcBitrate
        val pixelRate = width.toLong() * height.toLong() * fps.toLong()
        val estimate = (pixelRate * 12L / 100L).toInt().coerceAtLeast(5_000_000) // 0.12 bpp
        return min(estimate, 80_000_000)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
