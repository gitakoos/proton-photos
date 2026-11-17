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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import eu.akoos.photos.util.VideoLocationMetadata
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VideoTrimExporter"
private const val PROGRESS_POLL_MS = 250L

/** The frame duration assumed when the source does not state its frame rate (30 fps). */
private const val DEFAULT_FRAME_US = 33_333L

/**
 * Whether a stream copy of the kept windows starts where the user asked. A stream copy can only begin
 * at a keyframe, so each window really starts at [keyframeStartsUs] instead of [inPointsUs]; that is
 * the asked-for start only while the keyframe is the frame on screen at the in-point, that is less
 * than one frame ([frameUs]) before it. Pure.
 */
internal fun streamCopyStartsOnTime(inPointsUs: List<Long>, keyframeStartsUs: List<Long>, frameUs: Long): Boolean =
    inPointsUs.zip(keyframeStartsUs).all { (inUs, keyUs) -> inUs - keyUs < frameUs }

/** One frame's duration at [frameRate] frames per second, or 30 fps when the rate is unknown. Pure. */
internal fun frameDurationUs(frameRate: Float?): Long =
    if (frameRate == null || frameRate.isNaN() || frameRate < 1f) DEFAULT_FRAME_US else (1_000_000f / frameRate).toLong()

/** The display rotation of a track turned by [degrees] once [extraDegrees] more is added, in 0..359. Pure. */
internal fun addedRotation(degrees: Int, extraDegrees: Int): Int = ((degrees + extraDegrees) % 360 + 360) % 360

/**
 * The clip of one kept window: from its exact in-point, not the keyframe before it, so the export
 * decodes from that keyframe and drops every frame ahead of the in-point.
 */
@OptIn(UnstableApi::class)
internal fun exactTrimClipping(window: VideoSegment): MediaItem.ClippingConfiguration =
    MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(window.startMs.coerceAtLeast(0L))
        .setEndPositionMs(window.endMs)
        .setStartsAtKeyFrame(false)
        .build()

/**
 * The frame rate of the first video track of [extractor], or null when the container states none.
 * MediaFormat holds it as an integer or a float depending on the source, so both are tried.
 */
internal fun videoFrameRate(extractor: MediaExtractor): Float? {
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrNull()
    }
    return null
}

/**
 * Frame-exact trim export for an edit whose in-point falls between keyframes, where a stream copy
 * would start up to a whole keyframe interval early. Media3 Transformer clips each kept window at its
 * exact in-point. A single window is trim-optimised: only the frames from the in-point to the next
 * keyframe are re-encoded and the rest is copied as it is. Several windows are re-encoded as one
 * sequence. Audio is clipped with the video, so the two stay in step.
 *
 * [rotationDeltaDegrees] is the turn the user added on top of the source's own rotation, written into
 * the track's display matrix. [removeAudio] drops the sound. [location] is written as the output's
 * location. Throws on any export failure, after deleting [outputFile].
 */
@OptIn(UnstableApi::class)
internal suspend fun exportExactTrim(
    context: Context,
    sourceUri: Uri,
    outputFile: File,
    windows: List<VideoSegment>,
    rotationDeltaDegrees: Int,
    removeAudio: Boolean,
    location: Pair<Double, Double>?,
    onProgress: (Float) -> Unit,
) {
    require(windows.isNotEmpty()) { "no kept window" }
    val items = windows.map { window ->
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(sourceUri).setClippingConfiguration(exactTrimClipping(window)).build(),
        ).setRemoveAudio(removeAudio).build()
    }
    val composition = Composition.Builder(EditedMediaItemSequence.Builder(*items.toTypedArray()).build()).build()
    val muxerFactory = RotatingMuxerFactory(
        InAppMp4Muxer.Factory { entries -> VideoLocationMetadata.apply(entries, location, stripLocation = false) },
        rotationDeltaDegrees,
    )
    val thread = HandlerThread("video-trim").apply { start() }
    val handler = Handler(thread.looper)
    try {
        suspendCancellableCoroutine<Unit> { cont ->
            handler.post {
                startExport(context.applicationContext, composition, muxerFactory, outputFile, handler, onProgress, cont)
            }
        }
    } catch (t: Throwable) {
        runCatching { outputFile.delete() }
        throw t
    } finally {
        thread.quitSafely()
    }
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
private fun startExport(
    context: Context,
    composition: Composition,
    muxerFactory: Muxer.Factory,
    outFile: File,
    handler: Handler,
    onProgress: (Float) -> Unit,
    cont: CancellableContinuation<Unit>,
) {
    if (!cont.isActive) return
    try {
        val transformer = Transformer.Builder(context)
            // Re-encodes only the frames ahead of the first keyframe after the in-point and copies the
            // rest, so the trimmed clip keeps the original quality. It applies to a single clip only;
            // whatever it cannot handle falls back to a full re-encode inside the same export.
            .experimentalSetTrimOptimizationEnabled(true)
            .setMuxerFactory(muxerFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    Log.d(TAG, "exact trim done, optimization=${result.optimizationResult}")
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    handler.removeCallbacksAndMessages(null)
                    Log.w(TAG, "exact trim failed ${exception.errorCodeName}: ${exception.message}")
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
            .build()
        // A cancel that already happened runs this handler right here, on the Looper thread.
        cont.invokeOnCancellation {
            val cancel = Runnable {
                handler.removeCallbacksAndMessages(null)
                runCatching { transformer.cancel() }
            }
            if (android.os.Looper.myLooper() == handler.looper) cancel.run() else handler.post(cancel)
        }
        if (!cont.isActive) return
        transformer.start(composition, outFile.absolutePath)
        val holder = ProgressHolder()
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = runCatching { transformer.getProgress(holder) }
                    .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, 100) / 100f)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }, PROGRESS_POLL_MS)
    } catch (t: Throwable) {
        handler.removeCallbacksAndMessages(null)
        if (cont.isActive) cont.resumeWithException(t)
    }
}

/**
 * A muxer factory that turns every video track by [extraDegrees] more than the export decided, the way
 * the stream copy's orientation hint carries the user's rotation. Transformer settles the source's own
 * rotation (as metadata, or in the pixels when it re-encodes); adding the user's turn on top of
 * whichever it chose shows the same picture either way.
 */
@OptIn(UnstableApi::class)
private class RotatingMuxerFactory(private val delegate: Muxer.Factory, private val extraDegrees: Int) : Muxer.Factory {
    override fun create(path: String): Muxer {
        val inner = delegate.create(path)
        return if (addedRotation(0, extraDegrees) == 0) inner else RotatingMuxer(inner, extraDegrees)
    }

    override fun getSupportedSampleMimeTypes(trackType: Int) = delegate.getSupportedSampleMimeTypes(trackType)

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean =
        delegate.supportsWritingNegativeTimestampsInEditList()
}

@OptIn(UnstableApi::class)
private class RotatingMuxer(private val inner: Muxer, private val extraDegrees: Int) : Muxer {
    override fun addTrack(format: Format): Int = inner.addTrack(
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            format.buildUpon().setRotationDegrees(addedRotation(format.rotationDegrees, extraDegrees)).build()
        } else {
            format
        },
    )

    override fun writeSampleData(trackId: Int, data: ByteBuffer, bufferInfo: BufferInfo) =
        inner.writeSampleData(trackId, data, bufferInfo)

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = inner.addMetadataEntry(metadataEntry)

    override fun close() = inner.close()
}
