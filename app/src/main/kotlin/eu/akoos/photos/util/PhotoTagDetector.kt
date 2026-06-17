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

import android.content.Context
import android.net.Uri
import java.nio.charset.StandardCharsets

/**
 * Detects the Drive PhotoTag ids that apply to a single local file.
 *
 * Tags fall into two cost tiers:
 *   • Cheap tags read nothing off disk — Videos (mime), Raw (mime/extension), Screenshots
 *     (filename). These are always evaluated.
 *   • Motion Photo (Camera:MotionPhoto / GCamera:MicroVideo) and Panorama (GPano) live in the
 *     file's XMP metadata, which means reading a prefix of the file. That read is gated on
 *     [sizeBytes] because the only motion/panorama files worth scanning embed an MP4 or a wide
 *     stitch and are therefore large; below the threshold the XMP read is skipped entirely.
 *
 * Panorama is detected ONLY from a GPano XMP marker — never from aspect ratio — so a tall
 * screenshot or a long receipt scan is never mistaken for a panorama.
 *
 * Tag ids (subset of the Drive PhotoTag enum):
 *   1 Screenshots, 2 Videos, 4 MotionPhotos, 8 Panoramas, 9 Raw.
 */
object PhotoTagDetector {

    /**
     * Detection-logic version. Bump whenever [detectTags] changes how it classifies a file
     * (a new tag, a tightened rule, a fixed false positive). The background tag scanner clears
     * the persisted cache when this moves, so every on-device file is re-detected with the new
     * logic without a manual cache wipe. An unchanged version leaves the cache reads as-is.
     */
    const val DETECTOR_VERSION = 1

    /** XMP markers that identify an Android Motion Photo (Samsung / Google embed an MP4). */
    private const val MARKER_MOTION_PHOTO = "Camera:MotionPhoto"
    private const val MARKER_MICRO_VIDEO = "MicroVideo"
    /** XMP namespace marker for a Google Photo Sphere / panorama. */
    private const val MARKER_GPANO = "GPano:"
    /** Motion Photo flag `(GCamera:)MotionPhoto="1"` and the Container-directory
     *  `Item:Semantic="MotionPhoto"` form. Matching the viewer's detector here keeps the grid
     *  badge in agreement with what actually plays (Samsung's Google-compatible XMP uses these). */
    private val MOTION_FLAG_REGEX = Regex("""(?:GCamera:)?MotionPhoto\s*=\s*["']?1""")
    private val SEMANTIC_MOTION_REGEX = Regex("""Semantic\s*=\s*["']MotionPhoto["']""")

    /**
     * Files at or below this size skip the XMP prefix read and surface only the cheap tags.
     * A Motion Photo embeds an MP4 and a panorama is a wide stitch, so the files worth scanning
     * sit above this floor; the threshold is deliberately low so a smaller HEIC motion photo is
     * not skipped (the viewer's playback detector reads them ungated, so the badge must too).
     */
    private const val XMP_SCAN_MIN_BYTES = 1_500_000L

    /** How many leading bytes of the file to scan for an XMP packet. The packet sits near the
     *  head of the container, so a prefix is enough and avoids reading a multi-MB file whole. */
    private const val XMP_PREFIX_BYTES = 512 * 1024

    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw",
    )
    private val RAW_MIME_TYPES = setOf(
        "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-raw",
    )

    /**
     * Detect the tag ids for [uri]. [sizeBytes] gates the XMP prefix read (see
     * [XMP_SCAN_MIN_BYTES]); pass the MediaStore SIZE. Returns the cheap tags immediately and,
     * only for large files, adds Motion Photo / Panorama after scanning the XMP prefix. Never
     * throws — any read failure simply yields no XMP-derived tag.
     */
    fun detectTags(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String,
        sizeBytes: Long,
    ): List<Int> {
        val tags = mutableListOf<Int>()
        val mime = mimeType.lowercase()
        val name = displayName.lowercase()
        val ext = name.substringAfterLast('.', "")

        // ── Cheap tags (no disk read) ────────────────────────────────────
        if (mime.startsWith("video/")) tags += 2
        if (ext in RAW_EXTENSIONS || mime in RAW_MIME_TYPES) tags += 9
        if (mime.startsWith("image/") &&
            (name.startsWith("screenshot") || name.startsWith("screen_shot"))
        ) {
            tags += 1
        }

        // ── XMP-derived tags (gated on size) ─────────────────────────────
        // Below the floor a file cannot embed a motion video or a panorama stitch, so we skip
        // the read and never assign tag 4 / 8. Videos carry no still-image XMP either.
        if (sizeBytes > XMP_SCAN_MIN_BYTES && mime.startsWith("image/")) {
            val xmp = readXmpPrefix(context, uri)
            if (xmp != null) {
                val hasMotion = xmp.contains(MARKER_MOTION_PHOTO) ||
                    xmp.contains(MARKER_MICRO_VIDEO) ||
                    MOTION_FLAG_REGEX.containsMatchIn(xmp) ||
                    SEMANTIC_MOTION_REGEX.containsMatchIn(xmp)
                if (hasMotion) tags += 4
                if (xmp.contains(MARKER_GPANO)) tags += 8
            }
        }

        return tags
    }

    /**
     * Read up to [XMP_PREFIX_BYTES] from the head of [uri] and return it as Latin-1 text so the
     * ASCII XMP markers can be substring-matched without UTF-8 decode failures on the binary
     * bytes around the packet. Returns null on any I/O failure.
     */
    private fun readXmpPrefix(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(XMP_PREFIX_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, StandardCharsets.ISO_8859_1)
        }
    } catch (_: Throwable) {
        null
    }
}
