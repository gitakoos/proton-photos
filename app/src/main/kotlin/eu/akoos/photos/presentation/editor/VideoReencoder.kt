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
import kotlin.math.max
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
        /** When true and [audioOverlayUri] is null, output has no audio track at all. With
         *  an overlay set this flag is moot because the overlay already replaces source
         *  audio in the current pipeline. */
        muteOriginalAudio: Boolean = false,
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
            val encOutW = (cropWidth and 1.inv()).coerceAtLeast(16)
            val encOutH = (cropHeight and 1.inv()).coerceAtLeast(16)

            // Build encoder format. KEY_COLOR_FORMAT = COLOR_FormatSurface tells MediaCodec
            // we'll feed it via createInputSurface() — no manual YUV pushing required.
            val frameRate = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
            val srcBitrate = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_BIT_RATE, 0)
            val encBitrate = chooseEncoderBitrate(srcBitrate, encOutW, encOutH, frameRate)
            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encOutW, encOutH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, encBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 keyframe per second
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
            decoder.configure(srcVideoFormat, outputSurface.surface, null, 0)
            decoder.start()

            // Output muxer. Add video track lazily — encoder needs to surface its
            // INFO_OUTPUT_FORMAT_CHANGED before we know the final SPS/PPS to embed.
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // We're burning rotation in pixels via the GL transform, so the file's
            // orientation hint stays at 0. Players that respect both pixel-rotation
            // AND the hint won't double-rotate.

            videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Set up audio side. We always open a second PFD (either to the source for
            // its original audio or to the overlay file) so the audio extractor doesn't
            // race with the video extractor's selectTrack/seekTo bookkeeping on the same
            // underlying MediaExtractor — which would happen if we shared one instance.
            if (audioOverlayUri != null) {
                audioPfd = context.contentResolver.openFileDescriptor(audioOverlayUri, "r")
                    ?: error("Could not open audio overlay for reading")
                audioExtractor = MediaExtractor().apply { setDataSource(audioPfd.fileDescriptor) }
            } else if (!muteOriginalAudio) {
                val audioPfd2 = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (audioPfd2 != null) {
                    audioPfd = audioPfd2
                    audioExtractor = MediaExtractor().apply { setDataSource(audioPfd.fileDescriptor) }
                }
            }
            // muteOriginalAudio && audioOverlayUri == null → audioExtractor stays null,
            // the muxer is built with the video track only, output is silent.
            val audioTrackIdx = audioExtractor?.let { selectTrack(it, "audio/") }
            if (audioTrackIdx != null) {
                audioExtractor!!.selectTrack(audioTrackIdx)
            }

            val cropMatrix = CropMatrix.build(
                sourceWidth = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, encOutW),
                sourceHeight = srcVideoFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, encOutH),
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropWidth = cropWidth.coerceAtLeast(2),
                cropHeight = cropHeight.coerceAtLeast(2),
                rotationDegrees = rotationDegrees,
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
                            // forbids addTrack after start.
                            if (audioExtractor != null && audioMuxerTrackIdx < 0) {
                                for (i in 0 until audioExtractor.trackCount) {
                                    val fmt = audioExtractor.getTrackFormat(i)
                                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                                    if (mime.startsWith("audio/")) {
                                        audioMuxerTrackIdx = muxer.addTrack(fmt)
                                        break
                                    }
                                }
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

            // ── Audio stream-copy ──────────────────────────────────────────────────
            if (audioMuxerTrackIdx >= 0 && audioExtractor != null) {
                copyAudioSamples(
                    extractor = audioExtractor,
                    muxer = muxer,
                    muxerTrackIdx = audioMuxerTrackIdx,
                    startUs = if (audioOverlayUri != null) audioTrimStartUs else trimStartUs,
                    endUs = if (audioOverlayUri != null) audioTrimEndUs else trimEndUs,
                    // Origin shift makes output audio start at 0us. For overlay audio
                    // that means subtracting audioTrimStartUs; for original audio it
                    // means subtracting trimStartUs.
                    timeOriginUs = if (audioOverlayUri != null) audioTrimStartUs else trimStartUs,
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
     * Pick a sensible H.264 bitrate. Source bitrate is a good upper bound when available;
     * otherwise we fall back to a pixel-rate estimate. Cap at 12 Mbps so a cropped clip
     * doesn't accidentally produce a larger file than the source.
     */
    private fun chooseEncoderBitrate(srcBitrate: Int, width: Int, height: Int, fps: Int): Int {
        val pixelRate = width.toLong() * height.toLong() * fps.toLong()
        val estimate = (pixelRate * 4L / 100L).toInt().coerceAtLeast(2_000_000) // 0.04 bpp
        val bandedSource = if (srcBitrate > 0) (srcBitrate * 6 / 5) else estimate
        return min(max(bandedSource, 1_500_000), 12_000_000)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
