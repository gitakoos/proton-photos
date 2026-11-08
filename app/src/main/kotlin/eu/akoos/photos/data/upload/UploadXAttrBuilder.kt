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
import android.net.Uri
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import java.io.File

/**
 * Builds the encrypted xAttr metadata an upload carries (camera, location, capture time, display
 * dimensions) from the ORIGINAL source file, gated against the strip settings.
 */
internal class UploadXAttrBuilder(private val context: Context) {

    /** RAW formats whose embedded preview (what the thumbnail decodes from) is already display-
     *  oriented, so their xAttr must report a NORMAL camera orientation — see [buildXAttrMetadata]. */
    private val rawImageMimeTypes = setOf(
        "image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-sr2", "image/x-sony-srf",
        "image/x-fuji-raf", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef",
        "image/x-samsung-srw", "image/x-minolta-mrw", "image/x-kodak-dcr", "image/x-sigma-x3f",
        "image/x-epson-erf", "image/x-hasselblad-3fr", "image/x-raw",
    )
    private val rawImageExtensions = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf", "raf", "rw2", "orf", "pef",
        "srw", "mrw", "dcr", "x3f", "erf", "3fr", "raw",
    )

    /**
     * Builds the photo xAttr Camera/Location + display-dimension metadata for [item] from its
     * ORIGINAL [sourceUri] (read before any strip pass). Every field is gated against the strip
     * config so the encrypted xAttr can never carry data the on-file strip removed:
     *
     *  - Camera.Orientation: always emitted when known (never stripped; the web needs it to display).
     *  - Camera.Device: only when camera info is NOT stripped.
     *  - Camera.CaptureTime: [item].dateTaken — already floored to upload time when timestamps are
     *    stripped (see [eu.akoos.photos.domain.usecase.UploadPendingUseCase]), else the real capture time.
     *  - Location: only when GPS is NOT stripped.
     *  - displayWidth/Height: W/H swapped when EXIF orientation / video rotation is 90/270.
     */
    fun buildXAttrMetadata(
        sourceUri: String,
        item: eu.akoos.photos.domain.entity.LocalMediaItem,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
        videoDimsOverride: Pair<Int, Int>? = null,
    ): eu.akoos.photos.data.repository.drive.UploadXAttrMetadata {
        val stripGps = stripOnUpload && stripConfig.stripGps
        val stripCamera = stripOnUpload && stripConfig.stripCameraInfo
        // ISO_INSTANT (e.g. 2023-01-15T10:30:00Z), matching Drive Android's DateTimeFormatter.
        val captureTimeIso = item.dateTaken.takeIf { it > 0L }?.let { ms ->
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(ms))
        }
        return when {
            item.mimeType.startsWith("video/") -> {
                var rawW = 0
                var rawH = 0
                runCatching {
                    // Released explicitly: MediaMetadataRetriever implements AutoCloseable only from
                    // API 29, so a `use` block throws at close time on every older device and leaks
                    // the native retriever there.
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, Uri.parse(sourceUri))
                        rawW = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull() ?: 0
                        rawH = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull() ?: 0
                    } finally {
                        runCatching { r.release() }
                    }
                }
                // Send the RAW encoded stream dimensions UNSWAPPED and NO Camera block at all — what
                // the official client sends for a video. Drive web reads the display rotation from the
                // MP4 container itself, so swapping the dimensions or adding an orientation makes web
                // double-count the rotation and show the video sideways. The capture date still reaches
                // Drive through the separate photo.captureTime field (PhotoMetaDto), so the video keeps
                // its timeline position without an xAttr Camera block.
                // A compressed (downscaled) upload overrides the source dimensions with the OUTPUT
                // stream's, so the xAttr matches the bytes on the wire; rotation stays with the container.
                val baseW = videoDimsOverride?.first ?: rawW.takeIf { it > 0 } ?: item.width
                val baseH = videoDimsOverride?.second ?: rawH.takeIf { it > 0 } ?: item.height
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    displayWidth = baseW.takeIf { it > 0 },
                    displayHeight = baseH.takeIf { it > 0 },
                )
            }
            item.mimeType.startsWith("image/") -> {
                val meta = ExifHelper.readMetadata(context, sourceUri)
                val orientation = meta.orientation
                    ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                val swap = orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
                // RAW: the embedded preview the thumbnail decodes from is already display-oriented, so
                // report a NORMAL camera orientation. Sending the source's sensor orientation makes
                // Drive web rotate the already-upright RAW thumbnail and show it sideways (our own grid
                // draws the thumbnail as-is, so it looks right there — the mismatch only shows on the
                // web). Dimensions still use the upright (swapped) values so the aspect ratio matches.
                val ext = item.displayName.substringAfterLast('.', "").lowercase()
                val isRaw = item.mimeType in rawImageMimeTypes || ext in rawImageExtensions
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    latitude = if (!stripGps) meta.gpsLatitude else null,
                    longitude = if (!stripGps) meta.gpsLongitude else null,
                    cameraOrientation = if (isRaw)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL else orientation,
                    cameraCaptureTimeIso = captureTimeIso,
                    cameraDevice = if (!stripCamera) meta.model else null,
                    subjectCoordinates = if (!stripCamera) readSubjectCoordinates(sourceUri) else null,
                    displayWidth = if (swap) item.height.takeIf { it > 0 } else item.width.takeIf { it > 0 },
                    displayHeight = if (swap) item.width.takeIf { it > 0 } else item.height.takeIf { it > 0 },
                )
            }
            else -> eu.akoos.photos.data.repository.drive.UploadXAttrMetadata()
        }
    }

    /** Probes a transcoded video temp for its OUTPUT width/height (same retriever keys the video
     *  xAttr branch reads for the source), so a downscaled upload reports the real stream size.
     *  Returns null on any failure or a non-positive dimension, so the caller keeps the source dims. */
    fun probeVideoDimensions(file: File): Pair<Int, Int>? = runCatching {
        // Released explicitly: MediaMetadataRetriever implements AutoCloseable only from API 29, so a
        // `use` block throws at close time on every older device and turns each probe into a null.
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val h = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) w to h else null
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** Reads EXIF SubjectArea (3 or 4 comma-separated ints) and converts it to the
     *  [Top,Left,Bottom,Right] rectangle Drive's xAttr SubjectCoordinates expects, matching
     *  Drive Android's Rectangle.fromCenter. Returns null when absent or malformed. */
    private fun readSubjectCoordinates(uri: String): IntArray? = runCatching {
        val raw = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            androidx.exifinterface.media.ExifInterface(it)
                .getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SUBJECT_AREA)
        }?.takeUnless { it.isEmpty() } ?: return null
        val a = raw.split(",").map { it.trim().toInt() }
        val (cx, cy, w, h) = when (a.size) {
            3 -> listOf(a[0], a[1], a[2], a[2])
            4 -> listOf(a[0], a[1], a[2], a[3])
            else -> return null
        }
        // Rectangle.fromCenter: top, left, bottom, right.
        intArrayOf(cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2)
    }.getOrNull()
}
