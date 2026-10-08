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

package eu.akoos.photos.data.upload

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import eu.akoos.photos.data.upload.compression.TranscodeAcceptance
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.HdrHandling
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo
import eu.akoos.photos.util.Mp4CreationTime
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "VideoUploadCompressor"

/** How often the export progress is polled off the transcode thread, in milliseconds. */
private const val PROGRESS_POLL_MS = 250L

/** The muxer gives up after 10 s without a sample; a software encoder on a warm phone can take longer. */
private const val SOFTWARE_MUXER_WATCHDOG_MS = 60_000L

/**
 * Opt-in upload compression for VIDEOS ONLY. Carries out a [VideoCompressionPlan.Transcode] with
 * Media3 Transformer into a cache temp, so a lighter copy reaches Drive while the on-device original
 * is never touched. Stills go through [UploadImageCompressor].
 *
 * Every failure is an [Outcome] without a file, so the caller uploads the untouched original:
 * compression must never fail an upload or emit a partial, corrupt or larger file. [InAppMp4Muxer]
 * is used because it writes AV1 below Android 14, where the framework muxer can't.
 */
object VideoUploadCompressor {

    /** One progress tick: 0..1 done, the codec being produced, and seconds of video encoded per second. */
    data class Progress(
        val fraction: Float,
        val codec: VideoCodec,
        val speedX: Float?,
    )

    /**
     * The result of one [transcode]. [file] is set only for a kept output; the caller owns it.
     * [outputMime] and [outputBytes] describe the last finished export, kept or not.
     */
    data class Outcome(
        val file: File?,
        val outcome: CompressionOutcome,
        val reason: CompressionSkipReason?,
        val outputMime: String? = null,
        val outputBytes: Long? = null,
        val detail: String? = null,
    )

    /**
     * Carry out [plan] on the clip at [sourceUri], already probed as [source]. [sourceDateEpochMs],
     * when positive, is written into the output's container timestamps. A cancellation of the calling
     * coroutine cancels the export, deletes the temp and propagates.
     */
    @OptIn(UnstableApi::class)
    suspend fun transcode(
        context: Context,
        sourceUri: Uri,
        source: VideoSourceInfo,
        plan: VideoCompressionPlan.Transcode,
        sourceDateEpochMs: Long = 0L,
        tempDir: File = context.applicationContext.cacheDir,
        onProgress: ((Progress) -> Unit)? = null,
    ): Outcome {
        val appContext = context.applicationContext
        suspend fun export(hdrMode: Int, cbr: Boolean) =
            runAttempt(appContext, sourceUri, source, plan, hdrMode, cbr, tempDir, onProgress)

        val toneMaps = plan.hdr == HdrHandling.TONE_MAP_TO_SDR
        var hdrMode = if (toneMaps) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR
        var attempt = export(hdrMode, cbr = false)
        if (attempt.file == null && toneMaps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // OpenGL tone-mapping needs 10-bit GL, which some GPUs (and the emulator) lack. The decoder
            // can often tone-map instead.
            Log.d(TAG, "OpenGL tone-map failed (${attempt.error}); retrying with MediaCodec tone-mapping")
            hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
            attempt = export(hdrMode, cbr = false)
        }
        var verdict = attempt.verdict(source.sizeBytes)
        if (attempt.file != null && TranscodeAcceptance.shouldRetryWithCbr(verdict)) {
            Log.d(TAG, "VBR output ${attempt.file?.length()} vs source ${source.sizeBytes}: retrying once in CBR")
            attempt.file?.delete()
            attempt = export(hdrMode, cbr = true)
            verdict = attempt.verdict(source.sizeBytes)
        }

        val produced = attempt.file
            ?: return Outcome(null, CompressionOutcome.FAILED, CompressionSkipReason.ENCODER_ERROR, detail = attempt.error)
        val outputMime = attempt.outputMime ?: plan.codec.mimeType
        val outputBytes = produced.length()
        if (verdict != TranscodeAcceptance.Verdict.ACCEPT) {
            Log.d(TAG, "discarding output: $verdict (src=${source.sizeBytes} out=$outputBytes)")
            produced.delete()
            return Outcome(null, CompressionOutcome.SKIPPED, TranscodeAcceptance.reasonFor(verdict), outputMime, outputBytes)
        }
        // The capture date the upload uses can differ from the source container's creation time, which
        // InAppMp4Muxer copies, so stamp it explicitly. Then read the file back: a muxer or stamp problem
        // must never reach Drive.
        if (sourceDateEpochMs > 0L) Mp4CreationTime.stamp(produced, sourceDateEpochMs)
        if (!probeReadable(produced)) {
            Log.w(TAG, "transcoded file does not read back; uploading original instead")
            produced.delete()
            return Outcome(null, CompressionOutcome.FAILED, CompressionSkipReason.ENCODER_ERROR, outputMime, outputBytes, "UNREADABLE")
        }
        val fellBack = plan.plannedFallback != null || VideoCodec.fromMime(outputMime) != plan.codec
        return Outcome(
            file = produced,
            outcome = if (fellBack) CompressionOutcome.FELL_BACK else CompressionOutcome.COMPRESSED,
            reason = null,
            outputMime = outputMime,
            outputBytes = outputBytes,
        )
    }

    /** One export: the finished temp (not yet judged) and its codec, or the [error] that stopped it. */
    private class Attempt(val file: File?, val outputMime: String?, val error: String?) {
        fun verdict(sourceBytes: Long): TranscodeAcceptance.Verdict =
            TranscodeAcceptance.judge(file?.length() ?: 0L, sourceBytes)
    }

    @OptIn(UnstableApi::class)
    private suspend fun runAttempt(
        context: Context,
        sourceUri: Uri,
        source: VideoSourceInfo,
        plan: VideoCompressionPlan.Transcode,
        hdrMode: Int,
        cbr: Boolean,
        tempDir: File,
        onProgress: ((Progress) -> Unit)?,
    ): Attempt {
        // A dedicated Looper thread: Transformer must be built, started, progress-polled, and
        // cancelled on one thread whose Looper drives its callbacks.
        val thread = HandlerThread("video-compress").apply { start() }
        val handler = Handler(thread.looper)
        var outFile: File? = null
        try {
            outFile = File.createTempFile("videocompress_", ".mp4", tempDir)
            val target = outFile
            return suspendCancellableCoroutine { cont ->
                handler.post {
                    startTransform(context, sourceUri, source, plan, hdrMode, cbr, target, handler, onProgress, cont)
                }
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            outFile?.delete()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "transcode failed; uploading original: ${t.message}")
            outFile?.delete()
            return Attempt(null, null, t::class.java.simpleName)
        } finally {
            thread.quitSafely()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startTransform(
        context: Context,
        sourceUri: Uri,
        source: VideoSourceInfo,
        plan: VideoCompressionPlan.Transcode,
        hdrMode: Int,
        cbr: Boolean,
        outFile: File,
        handler: Handler,
        onProgress: ((Progress) -> Unit)?,
        cont: CancellableContinuation<Attempt>,
    ) {
        // The coroutine may have been cancelled between the handler.post and now; do not build a
        // transformer that would then never be cancelled.
        if (!cont.isActive) {
            runCatching { outFile.delete() }
            return
        }
        // The codec Media3 fell back to at run time, if any. Touched only on this Looper thread.
        var fallbackMime: String? = null
        try {
            // Frame drop first, so fewer frames reach the downscale. createForShortSide keeps the aspect
            // ratio and orientation, so a rotated portrait clip stays portrait.
            val effects = buildList<Effect> {
                plan.frameRateCap?.let { add(FrameDropEffect.createDefaultFrameDropEffect(it)) }
                if (plan.scaleToShortSide > 0) add(Presentation.createForShortSide(plan.scaleToShortSide))
            }
            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setEffects(Effects(emptyList(), effects))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(editedMediaItem).build())
                .setHdrMode(hdrMode)
                .build()

            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(plan.targetBitrate)
                .setBitrateMode(
                    if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                // Background work: let the codec run at its natural pace, not its maximum operating rate.
                .setEncoderPerformanceParameters(VideoEncoderSettings.RATE_UNSET, VideoEncoderSettings.RATE_UNSET)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(plan.codec.mimeType)
                // AAC passes through; anything else (Opus, Vorbis, AMR) is converted. AAC plays in every
                // MP4 player, and InAppMp4Muxer 1.8.0 writes an Opus header nothing can read back.
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .setMuxerFactory(InAppMp4Muxer.Factory())
                .apply { if (!plan.hardware) setMaxDelayBetweenMuxerSamplesMs(SOFTWARE_MUXER_WATCHDOG_MS) }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        handler.removeCallbacksAndMessages(null)
                        if (cont.isActive) cont.resume(Attempt(outFile, result.videoMimeType, null))
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        handler.removeCallbacksAndMessages(null)
                        Log.w(TAG, "transform error ${exception.errorCodeName}: ${exception.message}")
                        runCatching { outFile.delete() }
                        if (cont.isActive) cont.resume(Attempt(null, null, exception.errorCodeName))
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest,
                    ) {
                        fallbackMime = fallbackTransformationRequest.videoMimeType
                        Log.d(TAG, "fallback applied: $originalTransformationRequest -> $fallbackTransformationRequest")
                    }
                })
                .build()

            // Register the cancellation bridge before starting so a cancel that arrives during start
            // still tears the transcode down. A cancel that already happened runs the handler right
            // here, on the Looper thread, where a post could land after the Looper has quit.
            cont.invokeOnCancellation {
                val cancel = Runnable {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { transformer.cancel() }
                    runCatching { outFile.delete() }
                }
                if (Looper.myLooper() == handler.looper) cancel.run() else handler.post(cancel)
            }
            if (!cont.isActive) return

            transformer.start(composition, outFile.absolutePath)

            if (onProgress != null) {
                pollProgress(transformer, handler, onProgress, cont, source.durationMs) {
                    VideoCodec.fromMime(fallbackMime) ?: plan.codec
                }
            }
        } catch (t: Throwable) {
            handler.removeCallbacksAndMessages(null)
            Log.w(TAG, "transform start failed: ${t.message}")
            runCatching { outFile.delete() }
            if (cont.isActive) cont.resume(Attempt(null, null, t::class.java.simpleName))
        }
    }

    @OptIn(UnstableApi::class)
    private fun pollProgress(
        transformer: Transformer,
        handler: Handler,
        onProgress: (Progress) -> Unit,
        cont: CancellableContinuation<Attempt>,
        durationMs: Long,
        producing: () -> VideoCodec,
    ) {
        val holder = ProgressHolder()
        val startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = runCatching { transformer.getProgress(holder) }.getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val fraction = holder.progress.coerceIn(0, 100) / 100f
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    val speedX = if (fraction > 0f && elapsedMs > 0L) fraction * durationMs / elapsedMs else null
                    onProgress(Progress(fraction, producing(), speedX))
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, PROGRESS_POLL_MS)
                }
            }
        }, PROGRESS_POLL_MS)
    }

    /** Whether the file reads back as a video with a duration. */
    private fun probeReadable(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
