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

package eu.akoos.photos.util

import android.app.RecoverableSecurityException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ExifHelper"

data class PhotoMetadata(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val dateTime: String? = null,
    val dateTimeOriginal: String? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val exposureTime: String? = null,
    val isoSpeed: String? = null,
    val flash: Int? = null,
    val software: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val whiteBalance: Int? = null,
    val exposureMode: Int? = null,
)

data class MetadataStripConfig(
    val stripGps: Boolean = false,
    val stripCameraInfo: Boolean = false,
    val stripTimestamp: Boolean = false,
    val stripSoftwareInfo: Boolean = false,
) {
    /** Caller asked to remove nothing — short-circuit the strip pipeline. */
    val isNoOp: Boolean
        get() = !stripGps && !stripCameraInfo && !stripTimestamp && !stripSoftwareInfo
}

/** Outcome of an in-place strip. [NeedsPermission] means the OS raised a
 *  [RecoverableSecurityException] for a non-app-owned MediaStore file on Android 10+; the caller
 *  batches the affected URIs into a single [MediaStore.createWriteRequest], launches it, and
 *  retries on RESULT_OK. */
sealed interface StripResult {
    data object Stripped : StripResult
    data object NeedsPermission : StripResult
    data object Failed : StripResult
}

object ExifHelper {

    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS kept as fallback for older EXIF files.
    fun readMetadata(context: Context, uri: String): PhotoMetadata {
        return try {
            val stream = context.contentResolver.openInputStream(Uri.parse(uri))
                ?: return PhotoMetadata()
            val exif = stream.use { ExifInterface(it) }
            val latLon = exif.latLong
            PhotoMetadata(
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                gpsLatitude = latLon?.get(0),
                gpsLongitude = latLon?.get(1),
                gpsAltitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)
                    ?.let { exif.getAttributeDouble(ExifInterface.TAG_GPS_ALTITUDE, 0.0) },
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 },
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1).takeIf { it >= 0 },
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ?: exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE),
                exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                isoSpeed = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 },
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                artist = exif.getAttribute(ExifInterface.TAG_ARTIST),
                copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT),
                whiteBalance = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1).takeIf { it >= 0 },
                exposureMode = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_MODE, -1).takeIf { it >= 0 },
            )
        } catch (_: Exception) {
            PhotoMetadata()
        }
    }

    /** Reads the raw EXIF orientation tag (one of [ExifInterface]'s ORIENTATION_* values)
     *  from a content [uri]. Returns ORIENTATION_NORMAL when the stream can't be opened or
     *  carries no orientation. */
    fun readOrientation(context: Context, uri: String): Int = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Same as [readOrientation] but for a local [file] (used for the cloud full-res
     *  download, which lands on disk before decoding). */
    fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Rotates / flips [bitmap] so its pixels match the EXIF [orientation]. BitmapFactory
     * never honours the orientation tag, so a decode followed by this call yields the same
     * upright image the gallery thumbnail shows. Returns the original bitmap unchanged for
     * NORMAL / UNDEFINED (the common case) so no copy is made when none is needed.
     */
    fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * Picks the temp-file extension for a strip copy from the source [uri]. Prefers the
     * extension on the URI path; falls back to the ContentResolver MIME type mapped through
     * [MimeTypeMap]. Defaults to ".jpg" so the common JPEG case is unchanged.
     */
    private fun tempSuffixFor(context: Context, uri: Uri): String {
        val pathExt = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            .takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        if (pathExt != null) return ".${pathExt.lowercase()}"

        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val mimeExt = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (mimeExt != null) ".${mimeExt.lowercase()}" else ".jpg"
    }

    /**
     * Copies the file from [uri] to a temp file, strips the configured metadata fields,
     * and returns the temp file path. Caller must delete the temp file after use.
     * Returns null if nothing needs stripping or the operation fails.
     */
    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS kept to wipe the legacy tag too.
    fun stripToTempFile(context: Context, uri: String, config: MetadataStripConfig): File? {
        if (config.isNoOp) return null
        return try {
            val parsed = Uri.parse(uri)
            val inputStream = context.contentResolver.openInputStream(parsed) ?: return null
            // The temp must keep the source container's extension. A hardcoded ".jpg" mislabels
            // HEIC / RAW / motion-photo bytes, which then upload (and decode) under the wrong type.
            val suffix = tempSuffixFor(context, parsed)
            val tmpFile = File.createTempFile("stripped_", suffix, context.cacheDir)
            FileOutputStream(tmpFile).use { out -> inputStream.use { it.copyTo(out) } }
            val exif = ExifInterface(tmpFile)
            if (config.stripGps) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_SPEED, null)
                exif.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TRACK, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DOP, null)
            }
            if (config.stripCameraInfo) {
                exif.setAttribute(ExifInterface.TAG_MAKE, null)
                exif.setAttribute(ExifInterface.TAG_MODEL, null)
                exif.setAttribute(ExifInterface.TAG_LENS_MAKE, null)
                exif.setAttribute(ExifInterface.TAG_LENS_MODEL, null)
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, null)
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, null)
                exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, null)
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, null)
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, null)
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, null)
                exif.setAttribute(ExifInterface.TAG_FLASH, null)
                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, null)
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, null)
            }
            if (config.stripTimestamp) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, null)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
            }
            if (config.stripSoftwareInfo) {
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, null)
                exif.setAttribute(ExifInterface.TAG_ARTIST, null)
                exif.setAttribute(ExifInterface.TAG_COPYRIGHT, null)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
            }
            exif.saveAttributes()
            tmpFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strips specific EXIF fields from the file at [uri] in-place.
     * Works for files accessible via content resolver with rw mode
     * (e.g., app-owned files or with MANAGE_MEDIA permission).
     *
     * Returns:
     *  - [StripResult.Stripped] on success.
     *  - [StripResult.NeedsPermission] on Android 10+ when the file isn't app-owned — carries the
     *    IntentSender from the [RecoverableSecurityException] so the caller can launch the system
     *    write-permission dialog and retry. (See PhotoViewerViewModel / GalleryViewModel.)
     *  - [StripResult.Failed] for a pre-flight no-op or any other I/O or EXIF error.
     */
    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS kept to wipe the legacy tag too.
    fun stripFieldsInPlace(context: Context, uri: String, config: MetadataStripConfig): StripResult {
        if (config.isNoOp) return StripResult.Failed
        return try {
            val fd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw")
                ?: return StripResult.Failed
            fd.use {
                val exif = ExifInterface(it.fileDescriptor)
                if (config.stripGps) {
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_SPEED, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_TRACK, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_DOP, null)
                }
                if (config.stripCameraInfo) {
                    // Keep this set in sync with [stripToTempFile] — the user-facing UI
                    // ("Strip camera info" button) calls THIS path, so any tag missing here
                    // survives a manual wipe but gets stripped on upload, which would be a
                    // confusing privacy gap. Aperture-value / ISO-ratings / white-balance /
                    // exposure-mode must be wiped here to match `stripToTempFile`.
                    exif.setAttribute(ExifInterface.TAG_MAKE, null)
                    exif.setAttribute(ExifInterface.TAG_MODEL, null)
                    exif.setAttribute(ExifInterface.TAG_LENS_MAKE, null)
                    exif.setAttribute(ExifInterface.TAG_LENS_MODEL, null)
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, null)
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, null)
                    exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, null)
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, null)
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, null)
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, null)
                    exif.setAttribute(ExifInterface.TAG_FLASH, null)
                    exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, null)
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, null)
                }
                if (config.stripTimestamp) {
                    exif.setAttribute(ExifInterface.TAG_DATETIME, null)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
                }
                if (config.stripSoftwareInfo) {
                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, null)
                    exif.setAttribute(ExifInterface.TAG_ARTIST, null)
                    exif.setAttribute(ExifInterface.TAG_COPYRIGHT, null)
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
                }
                exif.saveAttributes()
            }
            StripResult.Stripped
        } catch (e: SecurityException) {
            // Android 10+ scoped storage: rw on a non-app-owned MediaStore file requires the user
            // to confirm via an IntentSender. RecoverableSecurityException carries one; surface it
            // so the caller can launch the system dialog (or batch the URI into createWriteRequest)
            // and retry. API <29 never raises the recoverable variant, so it falls through to Failed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                StripResult.NeedsPermission
            } else {
                Log.w(TAG, "stripFieldsInPlace: SecurityException", e)
                StripResult.Failed
            }
        } catch (e: Exception) {
            Log.w(TAG, "stripFieldsInPlace failed", e)
            StripResult.Failed
        }
    }
}
