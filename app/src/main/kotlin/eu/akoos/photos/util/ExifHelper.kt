package eu.akoos.photos.util

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
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

    /**
     * Copies the file from [uri] to a temp file, strips the configured metadata fields,
     * and returns the temp file path. Caller must delete the temp file after use.
     * Returns null if nothing needs stripping or the operation fails.
     */
    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS kept to wipe the legacy tag too.
    fun stripToTempFile(context: Context, uri: String, config: MetadataStripConfig): File? {
        if (config.isNoOp) return null
        return try {
            val inputStream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            val tmpFile = File.createTempFile("stripped_", ".jpg", context.cacheDir)
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
     * Returns false (and logs) on:
     *  - Pre-flight nothing-to-strip (no flags set).
     *  - [RecoverableSecurityException] on Android 10+ when the file isn't app-owned — the
     *    OS requires an IntentSender confirmation flow we don't drive yet (callers should
     *    surface a friendly message; see PhotoViewerViewModel.stripMetadataFromLocal).
     *  - Any other I/O or EXIF error.
     */
    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS kept to wipe the legacy tag too.
    fun stripFieldsInPlace(context: Context, uri: String, config: MetadataStripConfig): Boolean {
        if (config.isNoOp) return false
        return try {
            val fd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw") ?: return false
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
                }
                if (config.stripCameraInfo) {
                    // Keep this set in sync with [stripToTempFile] — the user-facing UI
                    // ("Strip camera info" button) calls THIS path, so any tag missing here
                    // survives a manual wipe but gets stripped on upload, which is a
                    // confusing privacy gap. Aperture-value / ISO-ratings / white-balance /
                    // exposure-mode were missing pre-2026-05-25 — bug 1 in the metadata-
                    // remover review.
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
            true
        } catch (e: SecurityException) {
            // Android 10+ scoped storage: rw on a non-app-owned MediaStore file requires
            // the user to confirm via an IntentSender (RecoverableSecurityException carries
            // it). Caller must surface a friendly message — we just log + bail here.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                Log.w(TAG, "stripFieldsInPlace: scoped-storage rw denied, IntentSender flow needed", e)
            } else {
                Log.w(TAG, "stripFieldsInPlace: SecurityException", e)
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "stripFieldsInPlace failed", e)
            false
        }
    }
}
