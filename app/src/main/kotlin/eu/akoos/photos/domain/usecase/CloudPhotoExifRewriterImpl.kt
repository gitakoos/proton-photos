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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.Mp4CreationTime
import eu.akoos.photos.util.isExifWritableImageMime
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudExifRewriter"

/**
 * The real [CloudPhotoExifRewriter]. Rewrites the changed EXIF on a downloaded original in place,
 * touching only the addressed tags so every pixel and every other tag stays byte-for-byte, then reports
 * the xAttr for the corrected copy. The xAttr mirrors the upload path's image branch
 * ([eu.akoos.photos.domain.usecase.UploadPendingUseCase]'s metadata builder): the ORIGINAL orientation
 * and upright dimensions, the original camera and subject-area, and the new capture time and place.
 *
 * A video takes the DATE only. Its date lives in the container's mvhd rather than in EXIF, so the file
 * edit stamps that box and does no EXIF work, and the xAttr mirrors the upload path's video branch: the
 * RAW encoded stream dimensions and duration, unswapped, with no camera, place or capture-time block
 * (Drive reads a video's rotation from the container and its capture time from the upload's own field).
 */
@Singleton
class CloudPhotoExifRewriterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudPhotoExifRewriter {

    override fun rewrite(
        file: File,
        mimeType: String,
        writeCaptureMs: Long?,
        xAttrCaptureMs: Long,
        location: LocationEdit,
        description: String?,
        artist: String?,
        copyright: String?,
    ): UploadXAttrMetadata {
        val fileUri = Uri.fromFile(file).toString()
        when {
            isExifWritableImageMime(mimeType) ->
                applyInFileEdits(file, fileUri, writeCaptureMs, location, description, artist, copyright)
            // A video takes the date only, and it lives in the container's mvhd, not in EXIF, so stamp
            // that box and do no EXIF work at all: no place, no text. writeCaptureMs is null for a place-
            // or text-only edit, which is a no-op on a video, so nothing is stamped then.
            writeCaptureMs != null && WriteLocalPhotoMetadataUseCase.isMvhdStampableMime(mimeType) ->
                Mp4CreationTime.stamp(file, writeCaptureMs)
        }
        // The in-file edits leave orientation, dimensions, camera and subject-area untouched, so the
        // xAttr reads the same values whether it runs before or after them; derive it from the corrected
        // file through the shared read-only builder.
        return xAttrFor(fileUri, mimeType, xAttrCaptureMs, location)
    }

    override fun xAttrFor(
        uri: String,
        mimeType: String,
        xAttrCaptureMs: Long,
        location: LocationEdit,
    ): UploadXAttrMetadata {
        // A video carries no EXIF Camera/Location block, and Drive reads its display rotation from the
        // container itself, so the xAttr sends the RAW encoded stream dimensions UNSWAPPED and its
        // duration, with no camera, place or capture-time block at all, the same shape the upload path
        // sends for a video. The capture date still reaches Drive through the upload's own capture-time
        // field, so a video keeps its timeline position without an xAttr Camera block.
        if (WriteLocalPhotoMetadataUseCase.isVideoMime(mimeType)) {
            val media = videoMediaInfo(uri)
            return UploadXAttrMetadata(
                displayWidth = media.width.takeIf { it > 0 },
                displayHeight = media.height.takeIf { it > 0 },
                durationMillis = media.durationMs.takeIf { it > 0 },
            )
        }
        // Read the metadata so the xAttr keeps the source orientation, dimensions, camera and
        // subject-area; only the capture time and place are allowed to change here.
        val meta = ExifHelper.readMetadata(context, uri)
        val orientation = meta.orientation ?: ExifInterface.ORIENTATION_NORMAL
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val (rawWidth, rawHeight) = rawDimensions(uri, meta.width, meta.height)
        val captureIso = xAttrCaptureMs.takeIf { it > 0L }?.let { ms ->
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
        }
        val place = when (location) {
            is LocationEdit.Set -> location.latitude to location.longitude
            is LocationEdit.Clear -> null
            is LocationEdit.Unchanged ->
                meta.gpsLatitude?.let { lat -> meta.gpsLongitude?.let { lon -> lat to lon } }
        }
        return UploadXAttrMetadata(
            latitude = place?.first,
            longitude = place?.second,
            cameraOrientation = orientation,
            cameraCaptureTimeIso = captureIso,
            cameraDevice = meta.model,
            subjectCoordinates = readSubjectCoordinates(uri),
            displayWidth = (if (swap) rawHeight else rawWidth).takeIf { it > 0 },
            displayHeight = (if (swap) rawWidth else rawHeight).takeIf { it > 0 },
        )
    }

    /**
     * Applies the date, place and descriptive-text edits to [file] in place. The date write, a Set place
     * and every non-null text tag share one ExifInterface pass; a Clear reuses the audited GPS strip so no
     * location tag is left behind. Each text value is encoded through [ExifAsciiText.transliterate], the
     * same US-ASCII form the device text path stores, so an empty result clears the tag. Never throws: a
     * container ExifInterface cannot rewrite degrades to Drive-side-only metadata (the new capture time
     * and place still reach Drive through the xAttr and the upload item).
     */
    private fun applyInFileEdits(
        file: File,
        fileUri: String,
        writeCaptureMs: Long?,
        location: LocationEdit,
        description: String?,
        artist: String?,
        copyright: String?,
    ) {
        val hasText = description != null || artist != null || copyright != null
        if (writeCaptureMs != null || location is LocationEdit.Set || hasText) {
            runCatching {
                val exif = ExifInterface(file.absolutePath)
                if (writeCaptureMs != null) writeCaptureDate(exif, writeCaptureMs)
                if (location is LocationEdit.Set) exif.setLatLong(location.latitude, location.longitude)
                if (description != null) {
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ExifAsciiText.transliterate(description))
                }
                if (artist != null) exif.setAttribute(ExifInterface.TAG_ARTIST, ExifAsciiText.transliterate(artist))
                if (copyright != null) {
                    exif.setAttribute(ExifInterface.TAG_COPYRIGHT, ExifAsciiText.transliterate(copyright))
                }
                exif.saveAttributes()
            }.onFailure { Log.w(TAG, "in-file date/place/text write skipped: ${it.message}") }
        }
        if (location is LocationEdit.Clear) {
            runCatching {
                ExifHelper.stripFieldsInPlace(context, fileUri, MetadataStripConfig(stripGps = true))
            }.onFailure { Log.w(TAG, "in-file place clear skipped: ${it.message}") }
        }
    }

    /**
     * Rewrites every EXIF datetime field so nothing left in the file contradicts [captureMs], matching
     * [WriteLocalPhotoMetadataUseCase]. The offset tags take the offset of the zone the wall clock is
     * read in, so a reader that honours them lands on the same absolute instant; the sub-second tags are
     * cleared, since a hand-picked date has no sub-second part.
     */
    private fun writeCaptureDate(exif: ExifInterface, captureMs: Long) {
        val zone = ZoneId.systemDefault()
        val stamp = ExifDateFormat.toExifLocal(captureMs, zone)
        val offset = ExifDateFormat.toExifOffset(captureMs, zone)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, null)
    }

    /** Raw encoded dimensions decoded from [uri]'s header ([BitmapFactory] bounds decode no pixels),
     *  falling back to the EXIF-reported size. Returns (0, 0) when neither is available. Reads through
     *  the resolver so it serves both a downloaded-original file uri and a device content uri. */
    private fun rawDimensions(uri: String, exifWidth: Int?, exifHeight: Int?): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
        val w = opts.outWidth.takeIf { it > 0 } ?: exifWidth ?: 0
        val h = opts.outHeight.takeIf { it > 0 } ?: exifHeight ?: 0
        return w to h
    }

    /** Raw encoded stream width/height and duration read from [uri]'s container through
     *  [MediaMetadataRetriever], for a video xAttr. Never throws; a value it cannot read stays 0, so the
     *  caller omits that field rather than reporting a zero. Reads through the resolver so it serves both
     *  a downloaded-original file uri and a device content uri. */
    private fun videoMediaInfo(uri: String): VideoMediaInfo {
        var width = 0
        var height = 0
        var durationMs = 0L
        runCatching {
            // Released explicitly: MediaMetadataRetriever implements AutoCloseable only from API 29, so a
            // `use` block throws at close time on every older device and leaks the native retriever there.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        }
        return VideoMediaInfo(width, height, durationMs)
    }

    /** Raw stream dimensions and duration a video reports, each 0 when it could not be read. */
    private data class VideoMediaInfo(val width: Int, val height: Int, val durationMs: Long)

    /** Reads EXIF SubjectArea (3 or 4 comma-separated ints) into the [Top, Left, Bottom, Right]
     *  rectangle Drive's xAttr SubjectCoordinates expects, matching the upload path. Null when absent
     *  or malformed. */
    private fun readSubjectCoordinates(uri: String): IntArray? = runCatching {
        val raw = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            ExifInterface(it).getAttribute(ExifInterface.TAG_SUBJECT_AREA)
        }?.takeUnless { it.isEmpty() } ?: return null
        val a = raw.split(",").map { it.trim().toInt() }
        val (cx, cy, w, h) = when (a.size) {
            3 -> listOf(a[0], a[1], a[2], a[2])
            4 -> listOf(a[0], a[1], a[2], a[3])
            else -> return null
        }
        intArrayOf(cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2)
    }.getOrNull()
}
