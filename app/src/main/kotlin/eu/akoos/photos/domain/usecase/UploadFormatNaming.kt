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

import java.util.Locale

/**
 * Name and MIME type for an upload whose bytes changed format on the way out: photo compression and
 * the HEIC strip transcode write JPEG, a video transcode writes MP4. Drive should never hold JPEG
 * bytes called `.heic`, or MP4 bytes called `.mov` or `.webm`.
 */
object UploadFormatNaming {

    const val JPEG = "image/jpeg"
    const val MP4 = "video/mp4"

    /** The extension a produced format gets, and the extensions it replaces. Any other name, such as
     *  "trip.2024", keeps its full name and gets the extension appended. */
    private class Format(val extension: String, val replaces: Set<String>)

    private val FORMATS = mapOf(
        JPEG to Format(
            "jpg",
            setOf("jpg", "jpeg", "jpe", "png", "webp", "heic", "heif", "avif", "bmp", "gif", "dng", "tif", "tiff"),
        ),
        MP4 to Format("mp4", setOf("mp4", "m4v", "mov", "qt", "3gp", "3g2", "3gpp", "webm", "mkv", "avi")),
    )

    /**
     * The (name, MIME type) to upload under when the bytes are [producedMime]. Unchanged when nothing
     * was re-encoded or the bytes kept their format; otherwise the extension follows the bytes, in the
     * original's case.
     */
    fun forProducedFormat(originalName: String, originalMime: String, producedMime: String?): Pair<String, String> {
        val produced = normalize(producedMime)
        val format = FORMATS[produced]
        if (produced == null || format == null || produced == normalize(originalMime)) return originalName to originalMime
        val dot = originalName.lastIndexOf('.')
        val ext = if (dot > 0) originalName.substring(dot + 1) else ""
        val base = if (ext.lowercase(Locale.ROOT) in format.replaces) originalName.substring(0, dot) else originalName
        val upper = ext.isNotEmpty() && ext == ext.uppercase(Locale.ROOT) && ext != ext.lowercase(Locale.ROOT)
        return "$base.${if (upper) format.extension.uppercase(Locale.ROOT) else format.extension}" to produced
    }

    /** The name a re-encoded copy of this photo or video went up under, or null when it keeps its own. */
    fun reencodedName(localName: String, localMime: String): String? {
        val mime = localMime.lowercase(Locale.ROOT)
        val produced = when {
            mime.startsWith("image/") -> JPEG
            mime.startsWith("video/") -> MP4
            else -> return null
        }
        return forProducedFormat(localName, localMime, produced).first.takeIf { it != localName }
    }

    /** Whether a file of this type can take the MP4 a video transcode writes without its type lying. */
    fun isMp4(mime: String?): Boolean = normalize(mime) == MP4

    /** "image/jpg" and "image/pjpeg" are JPEG too. */
    private fun normalize(mime: String?): String? = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.let { if (it == "image/jpg" || it == "image/pjpeg") JPEG else it }
}
