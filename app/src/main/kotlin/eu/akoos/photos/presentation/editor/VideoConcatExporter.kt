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
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VideoConcatExporter"
private const val PROGRESS_POLL_MS = 250L

/**
 * Multi-source EXPORT: concatenate the kept clips of a cut/edited timeline that spans MORE THAN ONE
 * video file (the "+" added a second video) into one MP4, using Media3 [Transformer]'s [Composition] /
 * [EditedMediaItemSequence]. That is Google's purpose-built concatenation primitive: it composites clips
 * of different resolutions, frame rates, rotations and audio layouts onto one timeline and handles every
 * decoder/encoder/muxer and all timestamp continuity internally, so this file never hand-rolls the joins.
 *
 * Each clip becomes an [EditedMediaItem] clipped to its source range ([VideoClip.startMs]..[endMs]) and
 * scaled to fit a fixed canvas ([canvasWidth] x [canvasHeight], the primary video's post-rotation size)
 * with letterboxing, so mixed aspect ratios stay undistorted. The single-source save paths
 * ([VideoReencoder.transcode] / muxTrimmed) are unchanged; this is only reached for `isMultiSource`.
 *
 * Mirrors [eu.akoos.photos.data.upload.VideoUploadCompressor]'s proven Transformer scaffolding (a
 * dedicated Looper thread; build/start/poll/cancel all on it). Unlike the compressor, a failure here
 * THROWS (the editor's save must fail loudly, not silently swap in an original), and the partial temp is
 * deleted.
 */
@OptIn(UnstableApi::class)
internal suspend fun concatMultiSourceToTemp(
    context: Context,
    clipsInPlayOrder: List<VideoClip>,
    sources: List<VideoSource>,
    outputFile: File,
    canvasWidth: Int,
    canvasHeight: Int,
    /** Optional background music mixed under the whole concatenated video. */
    overlayUri: Uri? = null,
    overlayStartMs: Long = 0L,
    overlayEndMs: Long = 0L,
    /** Music start on the edited timeline, in ms: a positive value delays the music with a leading silent
     *  gap so it begins that far into the concatenated video. 0 keeps it flush with the first frame. */
    overlayOffsetMs: Long = 0L,
    /** Source-clip audio volume [0..1] (0 mutes the videos' own sound); music volume [0..1]. */
    sourceGain: Float = 1f,
    musicGain: Float = 1f,
    /** Effective colour transform (filter preset composed with the adjustment sliders) as a 4x4
     *  column-major matrix, applied to every clip via a Media3 [RgbMatrix]. null = no colour edit. Media3
     *  may apply this in a slightly different colour space than the single-source GL shader, an accepted
     *  v1 parity difference. */
    colorMatrix4x4: FloatArray? = null,
    /** Timeline crop in the primary source's post-rotation pixels; applied to every clip in NDC, so on a
     *  secondary source of different size it crops the same relative region. null = full frame. */
    cropRect: Rect? = null,
    cropSourceWidth: Int = 0,
    cropSourceHeight: Int = 0,
    onProgress: (Float) -> Unit,
) {
    val appContext = context.applicationContext
    // Even dimensions (H.264 needs it), and a sane fallback if the primary never reported a size.
    val cw = (if (canvasWidth > 0) canvasWidth else 1280) and 1.inv()
    val ch = (if (canvasHeight > 0) canvasHeight else 720) and 1.inv()
    val muteSource = sourceGain <= 0.001f
    val sourceAudioProcessors = volumeProcessors(sourceGain)

    // Colour and crop were dropped by the old concat (it applied only Presentation), so a filter /
    // adjustment or a crop set before the "+" added a second video was lost. Both are immutable effect
    // descriptors, built once and reused across every clip's effect list.
    val colorEffect: RgbMatrix? = colorMatrix4x4?.let { StaticRgbMatrix(it) }
    // Crop is authored in the primary source's post-rotation pixels; convert to NDC (the input frame maps
    // to the square -1..1 on each axis, y up), and skip a degenerate rect that Crop would reject.
    val cropEffect: Crop? = cropRect
        ?.takeIf { cropSourceWidth > 0 && cropSourceHeight > 0 }
        ?.let { r ->
            val w = cropSourceWidth.toFloat()
            val h = cropSourceHeight.toFloat()
            val left = (2f * r.left / w - 1f).coerceIn(-1f, 1f)
            val right = (2f * r.right / w - 1f).coerceIn(-1f, 1f)
            val top = (1f - 2f * r.top / h).coerceIn(-1f, 1f)
            val bottom = (1f - 2f * r.bottom / h).coerceIn(-1f, 1f)
            if (right > left && top > bottom) Crop(left, right, bottom, top) else null
        }

    // One EditedMediaItem per kept clip, in PLAY order: clipped to the clip's source range and scaled to
    // fit the shared canvas. Transformer carries each source's own rotation tag through automatically.
    val items = clipsInPlayOrder.mapNotNull { clip ->
        val uri = sources.uriOf(clip.sourceId) ?: return@mapNotNull null
        val start = clip.startMs.coerceAtLeast(0L)
        val end = clip.endMs.coerceAtLeast(start + 1L)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(start)
                    .setEndPositionMs(end)
                    .build(),
            )
            .build()
        // Crop the source region first (in its own NDC), then letterbox-scale onto the shared canvas, then
        // apply colour, so the crop maps to the region the user framed before the scale to the canvas.
        val videoEffects = buildList<Effect> {
            cropEffect?.let { add(it) }
            add(Presentation.createForWidthAndHeight(cw, ch, Presentation.LAYOUT_SCALE_TO_FIT))
            colorEffect?.let { add(it) }
        }
        EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteSource)
            .setEffects(Effects(if (muteSource) emptyList() else sourceAudioProcessors, videoEffects))
            .build()
    }
    if (items.isEmpty()) throw IOException("No exportable clips for multi-source concat")

    // Optional background-music item (audio only), clipped to the chosen slice and scaled to musicGain.
    val musicItem: EditedMediaItem? = if (overlayUri != null && musicGain > 0.001f && overlayEndMs > overlayStartMs) {
        val mediaItem = MediaItem.Builder()
            .setUri(overlayUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(overlayStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(overlayEndMs)
                    .build(),
            )
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(volumeProcessors(musicGain), emptyList()))
            .build()
    } else {
        null
    }

    // Delay of the music on the output timeline, as a leading silent gap prepended to its sequence.
    val musicGapUs = if (overlayOffsetMs > 0L) overlayOffsetMs * 1000L else 0L

    val thread = HandlerThread("video-concat").apply { start() }
    val handler = Handler(thread.looper)
    try {
        suspendCancellableCoroutine<Unit> { cont ->
            handler.post { startConcat(appContext, items, musicItem, musicGapUs, outputFile, handler, onProgress, cont) }
        }
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        runCatching { outputFile.delete() }
        throw ce
    } finally {
        thread.quitSafely()
    }
}

/** Audio processors that scale volume by [gain]; empty when [gain] is ~1 (leave the audio untouched). A
 *  ChannelMixingMatrix scaled by the gain works for mono and stereo streams (whichever the source is). */
@OptIn(UnstableApi::class)
private fun volumeProcessors(gain: Float): List<AudioProcessor> {
    if (gain in 0.999f..1.001f) return emptyList()
    val g = gain.coerceIn(0f, 1f)
    val processor = ChannelMixingAudioProcessor()
    processor.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(g))
    processor.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(g))
    return listOf(processor)
}

/** A constant [RgbMatrix]: the same 4x4 column-major colour transform for every frame. The composition
 *  tone-maps HDR to SDR before effects run, so one matrix serves both the SDR and (post-tone-map) HDR
 *  paths. */
@OptIn(UnstableApi::class)
private class StaticRgbMatrix(private val matrix: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

@OptIn(UnstableApi::class)
private fun startConcat(
    context: Context,
    items: List<EditedMediaItem>,
    musicItem: EditedMediaItem?,
    musicGapUs: Long,
    outFile: File,
    handler: Handler,
    onProgress: (Float) -> Unit,
    cont: CancellableContinuation<Unit>,
) {
    if (!cont.isActive) {
        runCatching { outFile.delete() }
        return
    }
    try {
        // Sequence 1: the video clips back to back (a source without audio gets a generated silent track
        // so the joins stay A/V-aligned). Sequence 2 (optional): the background music, mixed under the
        // whole thing by Transformer — that is how you add music beneath a multi-source concat.
        val videoSequence = EditedMediaItemSequence.Builder(*items.toTypedArray())
            .experimentalSetForceAudioTrack(true)
            .build()
        val builder = if (musicItem != null) {
            // Place the music at its timeline offset by prepending a silent gap ([musicGapUs]) to its own
            // sequence. A sequence that opens with a gap must force an audio track so Transformer knows to
            // fill the gap with silence in the music's format; with no offset the music stays flush at 0.
            val musicSequence = if (musicGapUs > 0L) {
                EditedMediaItemSequence.Builder()
                    .addGap(musicGapUs)
                    .addItem(musicItem)
                    .experimentalSetForceAudioTrack(true)
                    .build()
            } else {
                EditedMediaItemSequence.Builder(musicItem).build()
            }
            Composition.Builder(videoSequence, musicSequence)
        } else {
            Composition.Builder(videoSequence)
        }
        val composition = builder
            // Mixing an HDR and an SDR clip on one canvas is unsafe; tone-map HDR down to SDR (matches the
            // editor's 8-bit pipeline elsewhere) so a mixed timeline exports without a colour blow-out.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    handler.removeCallbacksAndMessages(null)
                    Log.w(TAG, "multi-source export failed: ${exception.message}")
                    runCatching { outFile.delete() }
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
            .build()

        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacksAndMessages(null)
                runCatching { transformer.cancel() }
                runCatching { outFile.delete() }
            }
        }

        transformer.start(composition, outFile.absolutePath)
        pollProgress(transformer, handler, onProgress, cont)
    } catch (t: Throwable) {
        handler.removeCallbacksAndMessages(null)
        Log.w(TAG, "multi-source export start failed: ${t.message}")
        runCatching { outFile.delete() }
        if (cont.isActive) cont.resumeWithException(t)
    }
}

@OptIn(UnstableApi::class)
private fun pollProgress(
    transformer: Transformer,
    handler: Handler,
    onProgress: (Float) -> Unit,
    cont: CancellableContinuation<Unit>,
) {
    val holder = ProgressHolder()
    handler.postDelayed(object : Runnable {
        override fun run() {
            if (!cont.isActive) return
            val state = runCatching { transformer.getProgress(holder) }
                .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress.coerceIn(0, 100) / 100f)
            }
            if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }
    }, PROGRESS_POLL_MS)
}
