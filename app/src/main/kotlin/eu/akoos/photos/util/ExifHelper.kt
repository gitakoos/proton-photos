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
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.time.ZoneId

private const val TAG = "ExifHelper"

/**
 * Every EXIF tag [copyExifPreservingOrientation] transfers from the original onto an edited copy.
 * The editor re-encodes pixels via [Bitmap.compress], which drops ALL metadata, so this restores
 * capture time, camera identity, GPS and shooting parameters onto the saved file. Intentionally
 * EXCLUDED: [ExifInterface.TAG_ORIENTATION] (the editor bakes rotation into pixels, so the copy is
 * always upright) and every dimension tag (a crop changes the size). Those are handled separately.
 */
@Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS carried so the legacy tag copies across too.
internal val COPYABLE_EXIF_TAGS: Array<String> = arrayOf(
    // Timestamps
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    // Camera / lens identity
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_LENS_SPECIFICATION,
    ExifInterface.TAG_LENS_SERIAL_NUMBER,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    // Exposure / optics
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_MAX_APERTURE_VALUE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_BRIGHTNESS_VALUE,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_EXPOSURE_INDEX,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
    ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
    ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_LIGHT_SOURCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_FLASH_ENERGY,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_ISO_SPEED,
    ExifInterface.TAG_SENSITIVITY_TYPE,
    ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
    ExifInterface.TAG_SENSING_METHOD,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_SCENE_TYPE,
    ExifInterface.TAG_SUBJECT_DISTANCE,
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
    ExifInterface.TAG_SUBJECT_AREA,
    ExifInterface.TAG_SUBJECT_LOCATION,
    ExifInterface.TAG_CONTRAST,
    ExifInterface.TAG_SATURATION,
    ExifInterface.TAG_SHARPNESS,
    ExifInterface.TAG_GAIN_CONTROL,
    ExifInterface.TAG_CUSTOM_RENDERED,
    // Colour / capture description
    ExifInterface.TAG_COLOR_SPACE,
    ExifInterface.TAG_WHITE_POINT,
    ExifInterface.TAG_COMPONENTS_CONFIGURATION,
    ExifInterface.TAG_MAKER_NOTE,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_IMAGE_UNIQUE_ID,
    // Authorship / provenance
    ExifInterface.TAG_SOFTWARE,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_CAMERA_OWNER_NAME,
    ExifInterface.TAG_EXIF_VERSION,
    // GPS
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_AREA_INFORMATION,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
)

data class PhotoMetadata(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val dateTime: String? = null,
    val dateTimeOriginal: String? = null,
    val offsetTimeOriginal: String? = null,
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
    val description: String? = null,
    val software: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val whiteBalance: Int? = null,
    val exposureMode: Int? = null,
)

/**
 * The metadata groups a [MetadataStripConfig] can remove from an upload. A re-encode that rebuilds a
 * file's EXIF from the original consults these to decide which tags it may carry, so a recompressed
 * copy never re-injects metadata the strip was meant to erase. Tags outside every group (colour
 * space, EXIF version, image description) are not part of the strip model and are always copied.
 *
 * AUTHORSHIP (artist, copyright) stands apart from SOFTWARE because it carries a name the user chose
 * to put on the photo, so removing it is a decision of its own rather than a side effect of dropping
 * the editing tool.
 */
enum class ExifMetadataGroup { GPS, CAMERA, TIMESTAMP, SOFTWARE, AUTHORSHIP }

data class MetadataStripConfig(
    val stripGps: Boolean = false,
    val stripCameraInfo: Boolean = false,
    val stripTimestamp: Boolean = false,
    val stripSoftwareInfo: Boolean = false,
    val stripAuthorship: Boolean = false,
) {
    /** Caller asked to remove nothing — short-circuit the strip pipeline. */
    val isNoOp: Boolean
        get() = !stripGps && !stripCameraInfo && !stripTimestamp && !stripSoftwareInfo &&
            !stripAuthorship

    /**
     * The [ExifMetadataGroup]s that may be copied onto a re-encoded (recompressed) upload: a group is
     * allowed only when its strip flag is off. Pure and side-effect-free (it just inverts the five
     * flags), so a plain JVM test can pin it without Android, a Context, or an ExifInterface. A
     * stripped group is absent from the result; tags that belong to no modelled group are never
     * listed here and are always carried by the copy step.
     */
    fun allowedCopyGroups(): Set<ExifMetadataGroup> = buildSet {
        if (!stripGps) add(ExifMetadataGroup.GPS)
        if (!stripCameraInfo) add(ExifMetadataGroup.CAMERA)
        if (!stripTimestamp) add(ExifMetadataGroup.TIMESTAMP)
        if (!stripSoftwareInfo) add(ExifMetadataGroup.SOFTWARE)
        if (!stripAuthorship) add(ExifMetadataGroup.AUTHORSHIP)
    }
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
                // The offset that pins the bare datetime to an absolute instant. TAG_OFFSET_TIME is the
                // fallback because it belongs to TAG_DATETIME, which is the same clock on all but a
                // re-saved file.
                offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
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
                description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
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
        val parsed = Uri.parse(uri)
        val inputStream = context.contentResolver.openInputStream(parsed) ?: return null
        // The temp must keep the source container's extension. A hardcoded ".jpg" mislabels
        // HEIC / RAW / motion-photo bytes, which then upload (and decode) under the wrong type.
        val suffix = tempSuffixFor(context, parsed)
        val tmpFile = File.createTempFile("stripped_", suffix, context.cacheDir)
        // Once the temp exists, any later failure (copy / EXIF write) must delete it so a thrown
        // strip never orphans a cache file. The success path returns it for the caller to use+delete.
        return try {
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
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null)
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, null)
                exif.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, null)
                exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, null)
                exif.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, null)
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
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
            }
            if (config.stripAuthorship) {
                exif.setAttribute(ExifInterface.TAG_ARTIST, null)
                exif.setAttribute(ExifInterface.TAG_COPYRIGHT, null)
            }
            exif.saveAttributes()
            tmpFile
        } catch (_: Exception) {
            runCatching { tmpFile.delete() }
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
                    // Keep this GPS set identical to [stripToTempFile]: the user-facing in-place
                    // "clean" path calls THIS method, so any GPS tag missing here (compass bearing,
                    // map datum, positioning error) would survive a manual wipe yet get stripped on
                    // upload, leaking location context on a photo the user believes is cleaned.
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING_REF, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, null)
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
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
                }
                // Artist / copyright are their own flag: a caller that drops the editing tool can
                // still keep the credit the user put on the photo. Keep the tag set identical to
                // [stripToTempFile] so a manual wipe and an upload strip remove the same fields.
                if (config.stripAuthorship) {
                    exif.setAttribute(ExifInterface.TAG_ARTIST, null)
                    exif.setAttribute(ExifInterface.TAG_COPYRIGHT, null)
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

    /** EXIF tags that pin the capture wall-clock time (and its UTC offset / sub-second precision). */
    private val TIMESTAMP_GROUP_TAGS: Set<String> = setOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    )

    /** EXIF tags that identify the camera / lens or record its per-shot capture settings. */
    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS classified so the legacy ISO tag is grouped too.
    private val CAMERA_GROUP_TAGS: Set<String> = setOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_INDEX,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
    )

    /** EXIF tags that record the editing software or its free-text provenance note. */
    private val SOFTWARE_GROUP_TAGS: Set<String> = setOf(
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_USER_COMMENT,
    )

    /** EXIF tags that name the person behind the photo and the rights over it. */
    private val AUTHORSHIP_GROUP_TAGS: Set<String> = setOf(
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
    )

    /** EXIF tags that reveal the capture location. */
    private val GPS_GROUP_TAGS: Set<String> = setOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    /** The [ExifMetadataGroup] an EXIF [tag] belongs to, or null when it is outside the strip model
     *  and always safe to copy. Every privacy-relevant tag in [COPYABLE_EXIF_TAGS] must be classified:
     *  an unclassified tag falls through to null and copies unconditionally, which for a GPS tag would
     *  reintroduce the location a strip was asked to remove. A test pins that invariant. */
    internal fun groupForTag(tag: String): ExifMetadataGroup? = when (tag) {
        in GPS_GROUP_TAGS -> ExifMetadataGroup.GPS
        in TIMESTAMP_GROUP_TAGS -> ExifMetadataGroup.TIMESTAMP
        in CAMERA_GROUP_TAGS -> ExifMetadataGroup.CAMERA
        in SOFTWARE_GROUP_TAGS -> ExifMetadataGroup.SOFTWARE
        in AUTHORSHIP_GROUP_TAGS -> ExifMetadataGroup.AUTHORSHIP
        else -> null
    }

    /**
     * Copies every present tag in [COPYABLE_EXIF_TAGS] from [source] onto [dest], then forces the
     * destination orientation to [ExifInterface.ORIENTATION_NORMAL] and stamps the edited pixel size
     * ([bitmapWidth] x [bitmapHeight]). Orientation is forced because the editor bakes any rotation
     * into the pixels at load, and the dimensions are overwritten (not copied) because a crop changes
     * the size. Caller must invoke [ExifInterface.saveAttributes] on [dest]; this only sets fields.
     *
     * [config] gates the copy by [ExifMetadataGroup]: a tag whose group the config strips is skipped,
     * so a re-encode never restores metadata the upload was told to remove. The default strips nothing
     * and copies every group.
     */
    private fun copyTagsPreservingOrientation(
        source: ExifInterface,
        dest: ExifInterface,
        bitmapWidth: Int,
        bitmapHeight: Int,
        config: MetadataStripConfig = MetadataStripConfig(),
    ) {
        val allowedGroups = config.allowedCopyGroups()
        for (tag in COPYABLE_EXIF_TAGS) {
            val group = groupForTag(tag)
            if (group != null && group !in allowedGroups) continue
            val value = source.getAttribute(tag) ?: continue
            dest.setAttribute(tag, value)
        }
        // The edited copy is always upright (rotation baked into pixels), so the orientation tag must
        // read NORMAL, since copying the source orientation would make viewers double-rotate the image.
        dest.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString(),
        )
        // A crop changes the pixel size, so report the edited bitmap's dimensions, never the source's.
        if (bitmapWidth > 0 && bitmapHeight > 0) {
            dest.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmapWidth.toString())
            dest.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmapHeight.toString())
            dest.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmapWidth.toString())
            dest.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmapHeight.toString())
        }
    }

    /**
     * Re-injects the original photo's EXIF (see [copyTagsPreservingOrientation]) from [source] into an
     * already-written JPEG at [destFile], forcing a NORMAL orientation and the edited [bitmapWidth] x
     * [bitmapHeight] size. Used by the editor's cloud temp files (where the source is the downloaded
     * full-res original) and by the upload compressor. Never throws: a failure to copy EXIF must not
     * fail the save.
     *
     * [config] gates which metadata groups are copied: a stripped group's tags are dropped so a
     * recompressed upload cannot restore metadata the user asked to remove, even when the source is a
     * container ExifInterface cannot rewrite in place. The default copies every group.
     */
    fun copyExifPreservingOrientation(
        source: ExifInterface,
        destFile: File,
        bitmapWidth: Int,
        bitmapHeight: Int,
        config: MetadataStripConfig = MetadataStripConfig(),
    ) {
        runCatching {
            val dest = ExifInterface(destFile.absolutePath)
            copyTagsPreservingOrientation(source, dest, bitmapWidth, bitmapHeight, config)
            dest.saveAttributes()
        }.onFailure { Log.w(TAG, "copyExifPreservingOrientation(file) failed", it) }
    }

    /**
     * Same contract as the [File] overload, but writes the original EXIF from an already-read
     * [source] snapshot into a JPEG opened at [destFd] (owned by the caller). Used by the editor's
     * local-copy and overwrite paths, where the destination is a MediaStore file descriptor and the
     * source EXIF was captured up front (an overwrite clobbers the file before this runs).
     */
    fun copyExifPreservingOrientation(
        source: ExifInterface,
        destFd: FileDescriptor,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ) {
        runCatching {
            val dest = ExifInterface(destFd)
            copyTagsPreservingOrientation(source, dest, bitmapWidth, bitmapHeight)
            dest.saveAttributes()
        }.onFailure { Log.w(TAG, "copyExifPreservingOrientation(fd) failed", it) }
    }

    /**
     * Reads the source EXIF into a detached, in-memory snapshot ([ExifInterface] backed by nothing
     * writable). Callers use this for the overwrite path, where the destination IS the source: they
     * capture the tags into a snapshot BEFORE [Bitmap.compress] clobbers the file, then re-inject
     * from the snapshot afterwards. Returns null when the stream can't be opened or parsed.
     */
    fun readExifSnapshot(context: Context, uri: String): ExifInterface? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { ExifInterface(it) }
    }.getOrNull()

    /**
     * Reads the source EXIF from a local [file] into a snapshot (see the [Context]/URI overload).
     * Returns null on any parse/read failure.
     */
    fun readExifSnapshot(file: File): ExifInterface? = runCatching {
        ExifInterface(file.absolutePath)
    }.getOrNull()

    /**
     * Fill DateTimeOriginal (and DateTime) with [captureEpochMs] ONLY when the file has no original
     * date yet, so a real camera date is never overwritten and unchanged bytes keep hashing to their
     * cloud twin. MediaStore derives DATE_TAKEN from this EXIF block on scan, so a downloaded or
     * unhidden image with no embedded date otherwise lands at "today" on strict scanners. Best-effort
     * and never throws: a format ExifInterface cannot write is a silent no-op.
     */
    fun stampDateTakenIfMissing(file: File, captureEpochMs: Long) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
                val stamp = ExifDateFormat.toExifLocal(captureEpochMs, ZoneId.systemDefault())
                // The offset pairs with the datetime, so the stamp names one absolute instant.
                val offset = ExifDateFormat.toExifOffset(captureEpochMs, ZoneId.systemDefault())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                if (exif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrBlank()) {
                    exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
                }
                exif.saveAttributes()
            }
        }.onFailure { Log.w(TAG, "stampDateTakenIfMissing skipped: ${it.message}") }
    }

    /**
     * Writes [latitude] / [longitude] into [file]'s GPS EXIF so a downloaded / exported image keeps the
     * location the app already holds for its cloud twin. [ExifInterface.setLatLong] sets the coordinate
     * value plus its N/S and E/W reference tags. Best-effort and never throws: a format ExifInterface
     * cannot write is a silent no-op, so a failed write still leaves a valid downloaded file. Callers
     * gate this to the EXIF-writable image formats and pass ONLY real coordinates, never invented ones,
     * so a photo whose GPS was stripped at upload gains nothing here.
     */
    fun writeGpsLocation(file: File, latitude: Double, longitude: Double) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(latitude, longitude)
            exif.saveAttributes()
        }.onFailure { Log.w(TAG, "writeGpsLocation skipped: ${it.message}") }
    }
}
