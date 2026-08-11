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

package eu.akoos.photos.presentation.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ExifHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import javax.inject.Inject

/**
 * Turns a selected [GalleryItem] into a bitmap for the collage, at two very different sizes and with
 * the app's OOM discipline in mind (this app runs with a hard heap and a native crypto library that
 * both punish a burst of large bitmaps):
 *
 * - [preview] returns a small, downscaled bitmap for the on-screen editor. It never decrypts or hits
 *   the network: a device photo decodes its own file, a cloud-only photo reuses the thumbnail already
 *   on disk (`thumb_<linkId>.jpg`), and returns null when that thumbnail is not cached yet so the UI
 *   can show a placeholder instead of stalling.
 * - [fullRes] returns a full-resolution bitmap for the final export, bounded to a max long side. For a
 *   cloud-only photo it first downloads and decrypts the original. Callers run these ONE AT A TIME so
 *   the export never holds several originals in memory at once.
 *
 * Every decode is guarded: a corrupt file or an [OutOfMemoryError] yields null rather than a crash.
 */
class CollageBitmapSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
) {
    private val thumbnailDir: File get() = File(context.cacheDir, "thumbnails")

    /** A downscaled bitmap for the editor preview, or null when nothing is available yet (a cloud
     *  photo whose thumbnail is not cached). Cheap: no decrypt, no network. */
    suspend fun preview(item: GalleryItem, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        when (item) {
            is GalleryItem.LocalOnly -> decodeUri(Uri.parse(item.local.uri), maxPx)
            is GalleryItem.Synced -> decodeUri(Uri.parse(item.local.uri), maxPx)
            is GalleryItem.CloudOnly -> {
                val cached = File(thumbnailDir, "thumb_${item.cloud.linkId}.jpg")
                if (cached.exists()) decodeFile(cached.absolutePath, maxPx, applyExif = false) else null
            }
        }
    }

    /** A full-resolution bitmap bounded to [maxPx] on the long side, for the export. A cloud-only
     *  photo is downloaded and decrypted first; a device photo decodes its own file. Null on failure.
     *  Run these one at a time so only one original is in memory at once. */
    suspend fun fullRes(item: GalleryItem, userId: UserId, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        when (item) {
            is GalleryItem.LocalOnly -> decodeUri(Uri.parse(item.local.uri), maxPx)
            is GalleryItem.Synced -> decodeUri(Uri.parse(item.local.uri), maxPx)
            is GalleryItem.CloudOnly -> {
                val file = try {
                    cloudRepo.downloadFullResPhoto(userId, item.cloud)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "full-res download failed for ${item.cloud.linkId}: ${e.message}")
                    return@withContext null
                }
                decodeFile(file.absolutePath, maxPx, applyExif = true)
            }
        }
    }

    private fun decodeUri(uri: Uri, maxPx: Int): Bitmap? = guarded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@guarded null
        val opts = decodeOptions(bounds.outWidth, bounds.outHeight, maxPx)
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        // Originals carry an EXIF orientation BitmapFactory ignores, so rotate to upright here.
        bmp?.let { applyOrientation(it, ExifHelper.readOrientation(context, uri.toString())) }
    }

    private fun decodeFile(path: String, maxPx: Int, applyExif: Boolean): Bitmap? = guarded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@guarded null
        val bmp = BitmapFactory.decodeFile(path, decodeOptions(bounds.outWidth, bounds.outHeight, maxPx))
        // A cached thumbnail is already upright; only an original file needs its EXIF applied.
        if (applyExif && bmp != null) applyOrientation(bmp, ExifHelper.readOrientation(File(path))) else bmp
    }

    /** Bakes an [ExifInterface] orientation into the pixels, so a rotated original renders upright. */
    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }

    private fun decodeOptions(width: Int, height: Int, maxPx: Int) = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(width, height, maxPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /** Power-of-two subsample that keeps the long side at or under [maxPx], the standard OOM-safe
     *  decode: it never allocates the full-size bitmap just to shrink it afterwards. */
    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        val longSide = maxOf(width, height)
        while (longSide / sample > maxPx) sample *= 2
        return sample
    }

    private inline fun guarded(decode: () -> Bitmap?): Bitmap? =
        try {
            decode()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decode hit OOM, skipping: ${e.message}")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "CollageBitmapSource"
    }
}
