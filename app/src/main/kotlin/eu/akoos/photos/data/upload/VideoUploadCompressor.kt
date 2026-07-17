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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import eu.akoos.photos.util.Mp4CreationTime
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "VideoUploadCompressor"

/** Sources longer than this are left untouched (a very long transcode risks the foreground budget
 *  and rarely saves enough to be worth the wall-clock cost). About twenty minutes. */
private const val MAX_INPUT_DURATION_MS = 20L * 60L * 1000L

/** Sources larger than this are left untouched. About four gigabytes; guards a pathological input. */
private const val MAX_INPUT_BYTES = 4L * 1024L * 1024L * 1024L

/** Sources whose longest edge exceeds this (8K and up) are left untouched: decoding frames that
 *  large spikes memory past the app heap and risks an out-of-memory crash. 4K and below transcode
 *  fine, so only the very largest frames upload as the untouched original. */
private const val MAX_INPUT_LONG_EDGE = 4096

/** Clips shorter than this are not worth re-encoding (and probe results get unreliable). One second. */
private const val MIN_INPUT_DURATION_MS = 1000L

/** How often the export progress is polled off the transcode thread, in milliseconds. */
private const val PROGRESS_POLL_MS = 250L

/**
 * Opt-in upload compression for VIDEOS ONLY. Transcodes the source clip to a smaller cache temp file
 * with Media3 Transformer so a lighter copy reaches Drive while the on-device original is never
 * touched. The caller decides image-vs-video and only calls this for a video; stills go through
 * [UploadImageCompressor].
 *
 * The safety contract mirrors the image path: every failure mode returns null so the caller uploads
 * the untouched original. Compression must never fail (or block) an upload, and this must never emit
 * a partial or corrupt file. Rotation, creation time, audio, and HDR are preserved as far as the
 * platform allows: the source video codec is kept by default (H264 in stays H264 out, HEVC stays
 * HEVC) and HDR uses Transformer's default keep-or-tone-map behaviour.
 */
object VideoUploadCompressor {

    /** Explicit knobs so this has no dependency on the settings layer. [maxShortEdgePx] caps the
     *  output's SHORT edge (the "1080p/720p" dimension, aspect ratio and orientation preserved,
     *  never upscaled); [targetBitrateBps] is the requested video bitrate for the encoder. */
    data class VideoCompressionParams(
        val maxShortEdgePx: Int,
        val targetBitrateBps: Int,
    )

    /**
     * Transcode the video at [sourceUri] under [params] and return the temp [File], or null when it
     * can't (or shouldn't) be transcoded:
     *
     *  - No video track, unreadable, shorter than ~1s, longer than [MAX_INPUT_DURATION_MS], bigger
     *    than [MAX_INPUT_BYTES], or a frame larger than [MAX_INPUT_LONG_EDGE] (8K) → null (upload the
     *    original).
     *  - Any Transformer error, any throwable, or a cancelled upload → the partial temp is deleted and
     *    null is returned.
     *  - The produced file is missing, zero-length, or not strictly smaller than the source → deleted,
     *    null, so a transcode that would inflate the file never ships a larger copy.
     *
     * [onProgress] receives a 0..1 fraction while encoding (best-effort). A cancellation of the
     * calling coroutine cancels the transcode and cleans up the temp.
     */
    @OptIn(UnstableApi::class)
    suspend fun compressToTemp(
        context: Context,
        sourceUri: Uri,
        params: VideoCompressionParams,
        sourceDateEpochMs: Long = 0L,
        onProgress: ((Float) -> Unit)? = null,
    ): File? {
        val appContext = context.applicationContext

        // Source size drives the never-inflate rule and the input-ceiling check. If it can't be read
        // treat the transcode as not worthwhile rather than risk shipping a bigger file.
        val sourceSize = readSourceSize(appContext, sourceUri)
        if (sourceSize <= 0L || sourceSize > MAX_INPUT_BYTES) {
            Log.d(TAG, "skip: source size $sourceSize out of range (uri=$sourceUri)")
            return null
        }

        val probe = probe(appContext, sourceUri) ?: run {
            Log.d(TAG, "skip: probe failed (uri=$sourceUri)")
            return null
        }
        if (!probe.hasVideo ||
            probe.durationMs < MIN_INPUT_DURATION_MS ||
            probe.durationMs > MAX_INPUT_DURATION_MS ||
            probe.width <= 0 ||
            probe.height <= 0
        ) {
            Log.d(TAG, "skip: unsuitable input $probe (uri=$sourceUri)")
            return null
        }
        // An 8K frame decodes to hundreds of MB and, on top of everything already resident, tips the
        // app over its heap limit. Leave the very largest frames untouched (upload the original).
        if (maxOf(probe.width, probe.height) > MAX_INPUT_LONG_EDGE) {
            Log.d(TAG, "skip: frame too large ${probe.width}x${probe.height} (uri=$sourceUri)")
            return null
        }

        // A dedicated Looper thread: Transformer must be built, started, progress-polled, and
        // cancelled on one thread whose Looper drives its callbacks.
        val thread = HandlerThread("video-compress").apply { start() }
        val handler = Handler(thread.looper)
        var outFile: File? = null

        try {
            outFile = File.createTempFile("videocompress_", ".mp4", appContext.cacheDir)
            val target = outFile

            // The cancellation handler is registered once, inside startTransform, where the Transformer
            // reference exists; it cancels the transcode and deletes the temp on the Looper thread.
            val transcoded = suspendCancellableCoroutine<File?> { cont ->
                handler.post {
                    startTransform(appContext, sourceUri, params, probe, sourceSize, target, handler, onProgress, cont)
                }
            }
            // Media3's muxer stamps the output with the transcode time and drops the source capture
            // date. Write the original capture date back into the container timestamps so the
            // compressed FILE keeps it (Drive's timeline date comes from the upload metadata either
            // way, but a later download of the file then also carries the right date).
            return if (transcoded != null && sourceDateEpochMs > 0L) {
                stampCreationTime(transcoded, sourceDateEpochMs)
            } else {
                transcoded
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            outFile?.delete()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "compress failed for $sourceUri; uploading original: ${t.message}")
            outFile?.delete()
            return null
        } finally {
            thread.quitSafely()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startTransform(
        context: Context,
        sourceUri: Uri,
        params: VideoCompressionParams,
        probe: Probe,
        sourceSize: Long,
        outFile: File,
        handler: Handler,
        onProgress: ((Float) -> Unit)?,
        cont: CancellableContinuation<File?>,
    ) {
        // The coroutine may have been cancelled between the handler.post and now; do not build a
        // transformer that would then never be cancelled.
        if (!cont.isActive) {
            runCatching { outFile.delete() }
            return
        }
        try {
            // Cap the SHORT side (the "1080p/720p" dimension). createForShortSide scales the frame
            // uniformly with its aspect ratio AND orientation preserved, so a rotated portrait clip
            // stays portrait; a fixed width/height box built from the raw pre-rotation dimensions
            // would pillar-box a portrait clip into a landscape box (black bars, tiny content). The
            // short side is rotation-invariant. Only downscale, never upscale: skip when already small.
            val shortSide = minOf(probe.width, probe.height)
            val effects = buildList<Effect> {
                if (params.maxShortEdgePx in 1 until shortSide) {
                    add(Presentation.createForShortSide(params.maxShortEdgePx))
                }
            }

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setEffects(Effects(emptyList(), effects))
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(params.targetBitrateBps)
                        .build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                // No forced video MIME type: keep the source codec for compatibility. Audio is
                // passthrough (no audio effects). HDR stays on the default (keep HDR, or tone-map to
                // SDR on a device that cannot edit HDR) rather than the experimental force-SDR flag.
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        handler.removeCallbacksAndMessages(null)
                        // Never-inflate guard applied here so compressToTemp only ever returns a temp
                        // that is genuinely smaller and non-empty; anything else is deleted and null.
                        if (cont.isActive) cont.resume(acceptIfSmaller(outFile, sourceSize))
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        handler.removeCallbacksAndMessages(null)
                        Log.w(TAG, "transform error for $sourceUri: ${exception.message}")
                        runCatching { outFile.delete() }
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()

            // Register the cancellation bridge before starting so a cancel that arrives during start
            // still tears the transcode down. cancel() and delete run on this Looper thread.
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { transformer.cancel() }
                    runCatching { outFile.delete() }
                }
            }

            transformer.start(editedMediaItem, outFile.absolutePath)

            if (onProgress != null) {
                pollProgress(transformer, handler, onProgress, cont)
            }
        } catch (t: Throwable) {
            handler.removeCallbacksAndMessages(null)
            Log.w(TAG, "transform start failed for $sourceUri: ${t.message}")
            runCatching { outFile.delete() }
            if (cont.isActive) cont.resume(null)
        }
    }

    @OptIn(UnstableApi::class)
    private fun pollProgress(
        transformer: Transformer,
        handler: Handler,
        onProgress: (Float) -> Unit,
        cont: CancellableContinuation<File?>,
    ) {
        val holder = ProgressHolder()
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = runCatching { transformer.getProgress(holder) }.getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((holder.progress.coerceIn(0, 100)) / 100f)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, PROGRESS_POLL_MS)
                }
            }
        }, PROGRESS_POLL_MS)
    }

    /**
     * Final guard on the produced temp. Applied on completion so [compressToTemp] never returns a
     * transcode that inflated the clip (or emitted an empty file). Returns the file when it is
     * genuinely smaller and non-empty, otherwise deletes it and returns null.
     */
    private fun acceptIfSmaller(candidate: File?, sourceSizeBytes: Long): File? {
        if (candidate == null) return null
        val length = candidate.length()
        if (length in 1 until sourceSizeBytes) {
            Log.d(TAG, "compressed $sourceSizeBytes -> $length bytes")
            return candidate
        }
        Log.d(TAG, "compress skipped, not smaller: src=$sourceSizeBytes out=$length")
        candidate.delete()
        return null
    }

    /**
     * Best-effort: write [captureEpochMs] into the transcoded MP4's mvhd/tkhd/mdhd timestamps so the
     * compressed FILE keeps the original capture date rather than the transcode time. The file is
     * re-probed afterwards and, if it somehow no longer decodes, deleted so the caller falls back to
     * the original. Returns the file to upload, or null.
     */
    private fun stampCreationTime(file: File, captureEpochMs: Long): File? {
        Mp4CreationTime.stamp(file, captureEpochMs)
        if (probeReadable(file)) return file
        Log.w(TAG, "creation-time stamp left the file unreadable; uploading original instead")
        file.delete()
        return null
    }

    private fun probeReadable(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readSourceSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L }
            ?: context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
            ?: 0L
    }.getOrDefault(0L)

    private fun probe(context: Context, uri: Uri): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // Rotation is not read: the transcode scales by the SHORT side (createForShortSide), which is
            // rotation-invariant, and Transformer carries the source rotation through to the output.
            Probe(hasVideo, duration, width, height)
        } catch (t: Throwable) {
            Log.d(TAG, "probe threw: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class Probe(
        val hasVideo: Boolean,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )
}
