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

package eu.akoos.photos.data.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.gifmaker.GifAspect
import eu.akoos.photos.presentation.gifmaker.gifCanvasFor
import eu.akoos.photos.presentation.gifmaker.gifFraming
import eu.akoos.photos.util.GifEncoder
import eu.akoos.photos.util.ProtonPhotosStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Frames per second the GIF is sampled and played back at. */
private const val GIF_FPS = 12

/**
 * Longest edge each source frame is decoded at before the crop is applied. Larger than the canvas so a
 * zoomed-in crop still has detail to sample, but capped to keep per-frame decode memory in check.
 */
private const val GIF_DECODE_MAX_EDGE_PX = 720

/**
 * The GIF maker's encode-and-save engine, split out of the screen's view model so a foreground service can
 * own the work: the view model is destroyed the moment the screen pops, but the encode (and any cloud
 * upload) must run to completion in the background. This holder carries no UI state and no scope; the
 * service drives it and reports progress.
 *
 * [export] routes the finished GIF the way the photo and video editors route an edit: a cloud source is
 * uploaded to the Proton cloud (a new linkId, via [DrivePhotoRepository.uploadFile]); a device source is
 * written to the local gallery, which needs no account (guest mode).
 */
class GifExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
) {

    /**
     * Encodes the selected window to a GIF and saves it, returning the saved uri as a string. A cloud
     * source ([isCloud]) is uploaded to the Proton cloud, so it resolves the primary account up front and
     * fails fast when there is none; a device source is published to the local gallery with no account.
     * The temp file the encode produces is always deleted, and a [kotlinx.coroutines.CancellationException]
     * propagates untouched so a stopped export tears down cleanly.
     */
    suspend fun export(
        sourceUri: String,
        startMs: Long,
        endMs: Long,
        aspect: GifAspect,
        zoom: Float,
        panX: Float,
        panY: Float,
        maxEdgePx: Int,
        isCloud: Boolean,
        dateTakenMs: Long,
        displayName: String,
    ): String {
        val encoded = encodeGifToFile(sourceUri, displayName, startMs, endMs, aspect, zoom, panX, panY, maxEdgePx)
        return try {
            if (isCloud) {
                val userId = accountManager.getPrimaryUserId().first()
                    ?: throw IllegalStateException("No signed-in account to upload the GIF to")
                uploadEncodedGifToCloud(encoded, displayName, dateTakenMs, userId)
            } else {
                publishGifToDeviceGallery(encoded.file, displayName, dateTakenMs).toString()
            }
        } finally {
            runCatching { encoded.file.delete() }
        }
    }

    /** Encoder output: the finished GIF temp [file] and the [width]x[height] of its canvas (for upload metadata). */
    private data class GifEncodeOutput(val file: File, val width: Int, val height: Int)

    /**
     * Encodes the selected window to a GIF temp file under the cache, reporting 0..1 progress per frame.
     * Samples at [GIF_FPS], composes each frame through the shared [gifCanvasFor]/[gifFraming] so the export
     * matches the maker's WYSIWYG preview, and streams frames one at a time through [GifEncoder] so a full
     * 10s window never holds every decoded frame at once. Returns the temp file + canvas size; on any failure
     * the temp file is deleted and the error rethrown so the caller can surface it.
     */
    private suspend fun encodeGifToFile(
        sourceUriStr: String,
        displayName: String,
        startMs: Long,
        endMs: Long,
        aspect: GifAspect,
        zoom: Float,
        panX: Float,
        panY: Float,
        maxEdgePx: Int,
    ): GifEncodeOutput = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val dir = File(context.cacheDir, "gifexport").apply { mkdirs() }
        val base = displayName.substringBeforeLast('.', displayName).ifBlank { "video" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val outFile = File(dir, "${base}_gif_$ts.gif")
        try {
            retriever.setDataSource(context, Uri.parse(sourceUriStr))
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = (((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0) % 360) + 360) % 360
            val dispW = (if (rot % 180 != 0) rawH else rawW).coerceAtLeast(2)
            val dispH = (if (rot % 180 != 0) rawW else rawH).coerceAtLeast(2)
            val (canvasW, canvasH) = gifCanvasFor(dispW, dispH, aspect, maxEdgePx)
            val (decodeW, decodeH) = gifDecodeSize(dispW, dispH, maxEdgePx)
            val framing = gifFraming(decodeW, decodeH, canvasW, canvasH, zoom, panX, panY)
            val srcRect = Rect(
                framing.srcLeft.roundToInt().coerceIn(0, decodeW),
                framing.srcTop.roundToInt().coerceIn(0, decodeH),
                framing.srcRight.roundToInt().coerceIn(0, decodeW),
                framing.srcBottom.roundToInt().coerceIn(0, decodeH),
            )
            val dstRect = RectF(framing.dstLeft, framing.dstTop, framing.dstRight, framing.dstBottom)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
            val stepMs = (1000L / GIF_FPS).coerceAtLeast(1L)
            val span = (endMs - startMs).coerceAtLeast(0L)
            val frameCount = (span / stepMs).toInt() + 1
            val pixels = IntArray(canvasW * canvasH)
            FileOutputStream(outFile).use { out ->
                val encoder = GifEncoder(out, canvasW, canvasH, frameDelayMs = 1000 / GIF_FPS, loop = true)
                var framesAdded = 0
                for (i in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    val tUs = (startMs + i * stepMs) * 1000L
                    val frame = decodeGifFrame(retriever, tUs, decodeW, decodeH)
                    if (frame != null) {
                        try {
                            val outFrame = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(outFrame).apply {
                                    drawColor(Color.BLACK)
                                    drawBitmap(frame, srcRect, dstRect, paint)
                                }
                                outFrame.getPixels(pixels, 0, canvasW, 0, 0, canvasW, canvasH)
                                encoder.addFrame(pixels)
                                framesAdded++
                            } finally {
                                outFrame.recycle()
                            }
                        } finally {
                            frame.recycle()
                        }
                    }
                }
                if (framesAdded == 0) error("No frames decoded from the selected range")
                encoder.finish()
            }
            GifEncodeOutput(outFile, canvasW, canvasH)
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            throw t
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Writes the finished GIF into the device gallery as a pending `image/gif` (IS_PENDING on Q+) under the
     * shared Pictures folder, then publishes it. A plain MediaStore image with no account or cloud leg, so it
     * works with no Proton account (guest mode). Returns the published uri; deletes the pending row on failure.
     */
    private fun publishGifToDeviceGallery(gifFile: File, displayName: String, dateTakenMs: Long): Uri {
        val uri = insertPendingGif(displayName, dateTakenMs)
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                gifFile.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null for $uri")
            publishPendingGif(uri)
            uri
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    /**
     * Uploads the finished GIF to the Proton cloud as a new photo, mirroring the video editor's
     * uploadExistingFileToCloud: build a [LocalMediaItem] over the temp file, hash it, and hand it to
     * [DrivePhotoRepository.uploadFile]. The GIF inherits the source video's capture time so it sorts next to
     * the original. Returns the upload's source uri for the activity log.
     */
    private suspend fun uploadEncodedGifToCloud(
        encoded: GifEncodeOutput,
        displayName: String,
        dateTakenMs: Long,
        userId: me.proton.core.domain.entity.UserId,
    ): String {
        val uploadUri = Uri.fromFile(encoded.file).toString()
        val base = displayName.substringBeforeLast('.', displayName).ifBlank { "video" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val item = LocalMediaItem(
            uri = uploadUri,
            dateTaken = dateTakenMs,
            displayName = "${base}_gif_$ts.gif",
            mimeType = "image/gif",
            sizeBytes = encoded.file.length(),
            bucketName = null,
            width = encoded.width,
            height = encoded.height,
            duration = 0L,
        )
        cloudRepo.uploadFile(userId, item, sha1(encoded.file), uploadUri)
        return uploadUri
    }

    /** Bare sha1 hex of [file], the content digest [DrivePhotoRepository.uploadFile] expects. */
    private fun sha1(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Decodes the frame at [timeUs] scaled to exactly [targetW] x [targetH]. API 27+ scales during decode;
     * below that the full frame is decoded then scaled. getScaledFrameAtTime only fits within the requested
     * box (aspect preserved), so the result is normalised to the exact size the caller draws from.
     * OPTION_CLOSEST yields distinct frames, unlike the sync-only variant.
     */
    private fun decodeGifFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetW: Int,
        targetH: Int,
    ): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH,
            )
        } else {
            retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null
        if (decoded.width == targetW && decoded.height == targetH) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * The size each source frame is decoded at before cropping: the display size scaled so its longest edge
     * is at most the larger of [maxEdgePx] and [GIF_DECODE_MAX_EDGE_PX], aspect kept, both dimensions floored
     * to even and at least 2. Kept at least as large as the canvas so a zoomed-in crop keeps detail to sample.
     */
    private fun gifDecodeSize(dispW: Int, dispH: Int, maxEdgePx: Int): Pair<Int, Int> {
        val decodeEdge = maxOf(maxEdgePx, GIF_DECODE_MAX_EDGE_PX)
        val longest = maxOf(dispW, dispH)
        val scale = if (longest > decodeEdge) decodeEdge.toFloat() / longest else 1f
        val w = ((dispW * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((dispH * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return w to h
    }

    /**
     * Inserts a pending MediaStore image row for the GIF (IS_PENDING on Q+), named from the source with a
     * `_gif_` stamp, under the shared Pictures folder. The caller streams the bytes into the returned URI
     * then calls [publishPendingGif]. [dateTakenMs] is the source's capture time so the GIF sorts next to the
     * original; the filename is stamped with the current time so repeat exports never collide. The GIF carries
     * no EXIF, so no location travels in the file itself.
     */
    private fun insertPendingGif(sourceDisplayName: String, dateTakenMs: Long): Uri {
        val base = sourceDisplayName.substringBeforeLast('.', sourceDisplayName).ifBlank { "video" }
        val nowMs = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(nowMs))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${base}_gif_$ts.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
    }

    /** Clears IS_PENDING (Q+) so the finished GIF becomes visible, then wakes MediaStore observers. */
    private fun publishPendingGif(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
        runCatching { context.contentResolver.notifyChange(uri, null) }
    }
}
