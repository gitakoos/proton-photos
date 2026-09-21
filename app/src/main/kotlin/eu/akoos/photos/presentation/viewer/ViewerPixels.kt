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

package eu.akoos.photos.presentation.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import eu.akoos.photos.util.DisplayOrientation
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.PerfDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The pixels behind what the photo viewer is currently showing, for callers that need to analyse
 * the image rather than draw it.
 *
 * The viewer itself never holds a bitmap: [PhotoViewerViewModel.ViewerState.ShowImage] carries a
 * Coil model, which is a device photo's content URI, a decrypted cloud blob's `file://` URI, or a
 * remote thumbnail URL while the full-res download is pending or the metered-network gate is
 * holding it. That last case is why the result is a sealed type: a thumbnail is a poor analysis
 * subject and handing one back silently would look like a success.
 */
sealed class ViewerPixels {

    /**
     * Pixels in the same orientation the user sees, downsampled to [MAX_DIM] on the longest edge.
     * [orientation] is what was baked in on top of the decode, so a caller can map a position back
     * to the source frame's own coordinates.
     */
    data class Ok(
        val bitmap: Bitmap,
        val orientation: DisplayOrientation,
        val sampleSize: Int,
    ) : ViewerPixels() {
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height
    }

    /** Only a thumbnail exists — the full-res blob has not been fetched, or a metered link blocks it. */
    data object NoFullResolution : ViewerPixels()

    /** The viewer is on a video, or on a model that carries no still image at all. */
    data object NotAnImage : ViewerPixels()

    /** The source is gone or undecodable. */
    data object Unavailable : ViewerPixels()

    /** The decode ran out of heap; the image cache was dropped and the miss recorded. */
    data object OutOfMemory : ViewerPixels()

    companion object {

        /**
         * Longest-edge cap on the decode, matching the photo editor's. A 50 MP photo as ARGB_8888
         * is ~200 MB, and the viewer is already holding a full-res (possibly gain-mapped) bitmap
         * plus its cached neighbours when this runs.
         */
        const val MAX_DIM = 4096

        /** Reads the pixels behind [state], or says why it cannot. */
        suspend fun capture(
            context: Context,
            state: PhotoViewerViewModel.ViewerState,
            maxDim: Int = MAX_DIM,
        ): ViewerPixels = when (state) {
            is PhotoViewerViewModel.ViewerState.ShowImage -> capture(context, state.model, maxDim)
            else -> NotAnImage
        }

        /** Reads the pixels behind a Coil [model], or says why it cannot. */
        suspend fun capture(
            context: Context,
            model: Any?,
            maxDim: Int = MAX_DIM,
        ): ViewerPixels {
            val uri = sourceUri(model) ?: return NotAnImage
            val scheme = uri.scheme?.lowercase()
            // A remote model in this viewer is always the thumbnail placeholder.
            if (scheme == "http" || scheme == "https") return NoFullResolution
            val path = if (scheme == null || scheme == "file") uri.path else null
            if (path == null && scheme != "content") return NotAnImage

            return withContext(Dispatchers.IO) { decodeOriented(context, uri, path, maxDim) }
        }

        private fun sourceUri(model: Any?): Uri? = when (model) {
            is Uri -> model
            is String -> runCatching { Uri.parse(model) }.getOrNull()
            else -> null
        }

        /**
         * Decodes downsampled and bakes EXIF orientation into the pixels. Both steps are the large
         * allocations here, so both sit behind one OutOfMemoryError guard that drops the image
         * cache before it does anything else — the same recovery the editor's decode uses.
         */
        private fun decodeOriented(
            context: Context,
            uri: Uri,
            path: String?,
            maxDim: Int,
        ): ViewerPixels = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            readInto(context, uri, path, bounds)
            val sample = if (bounds.outWidth <= 0 || bounds.outHeight <= 0) 1
                else sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            val decoded = readInto(
                context, uri, path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return Unavailable

            val exif = if (path != null) ExifHelper.readOrientation(File(path))
                else ExifHelper.readOrientation(context, uri.toString())
            val oriented = ExifHelper.applyOrientation(decoded, exif)
            // applyOrientation hands back the very same instance both when there is nothing to do
            // and when the rotation copy fails, so identity is the honest signal for what actually
            // reached the pixels. Reporting a rotation that never happened would misplace every
            // coordinate a caller derives from this frame.
            val applied = if (oriented === decoded) DisplayOrientation.None
                else displayOrientationOf(exif)
            Ok(bitmap = oriented, orientation = applied, sampleSize = sample)
        } catch (_: OutOfMemoryError) {
            context.imageLoader.memoryCache?.clear()
            PerfDiagnostics.recordOom("viewer-pixels")
            OutOfMemory
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Unavailable
        }

        /** Runs one decode pass, from the file path when there is one and the resolver otherwise. */
        private fun readInto(
            context: Context,
            uri: Uri,
            path: String?,
            options: BitmapFactory.Options,
        ): Bitmap? = if (path != null) {
            BitmapFactory.decodeFile(path, options)
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
    }
}

/** Smallest power-of-two subsample that brings the longest edge within [maxDim]. */
internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
    if (maxDim <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / sample > maxDim) sample *= 2
    return sample
}

/**
 * Decomposes an EXIF orientation tag into the mirror-then-rotate pair that
 * [ExifHelper.applyOrientation] performs. Anything outside the eight defined values leaves the
 * frame alone, matching that helper's own fall-through.
 */
internal fun displayOrientationOf(exifOrientation: Int): DisplayOrientation = when (exifOrientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> DisplayOrientation(0, mirrored = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> DisplayOrientation(180, mirrored = false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> DisplayOrientation(180, mirrored = true)
    ExifInterface.ORIENTATION_TRANSPOSE -> DisplayOrientation(270, mirrored = true)
    ExifInterface.ORIENTATION_ROTATE_90 -> DisplayOrientation(90, mirrored = false)
    ExifInterface.ORIENTATION_TRANSVERSE -> DisplayOrientation(90, mirrored = true)
    ExifInterface.ORIENTATION_ROTATE_270 -> DisplayOrientation(270, mirrored = false)
    else -> DisplayOrientation.None
}
