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

/**
 * Maps a MIME type (e.g. "image/jpeg") to the lowercase file extension we use when
 * persisting the matching blob to cacheDir (e.g. "jpg"). Single source of truth for
 * the mapping — anyone writing OR reading the fullres cache should call this so the
 * filename a writer produces is the filename a reader looks for.
 *
 * Falls back to the raw subtype if it's not in the known list — covers e.g. "image/png"
 * → "png" without us needing to enumerate every photo MIME.
 *
 * The explicit entries exist because the naive substring split produces things like
 * ".quicktime" or ".x-matroska" which neither ExoPlayer nor Coil recognise as a
 * container/codec hint, so the downloaded blob ends up unplayable.
 */
fun mimeToFileExtension(mimeType: String): String {
    val sub = mimeType.substringAfterLast('/').lowercase()
    return when (sub) {
        "jpeg" -> "jpg"
        "quicktime" -> "mov"
        "x-matroska" -> "mkv"
        "x-msvideo" -> "avi"
        "x-ms-wmv" -> "wmv"
        "mpeg" -> if (mimeType.startsWith("video/")) "mpg" else "mp3"
        else -> sub
    }
}

/**
 * The MIME type a file at [path] holds, read from its extension alone, or the empty string for an
 * extension this app has no photo or video type for.
 *
 * The other direction of [mimeToFileExtension], and the answer for a file the content resolver has
 * nothing to say about: `getType` resolves a `content://` uri through its provider and returns null
 * for a plain `file://` one, so an app-private file — a vault copy, a cache blob — needs its type
 * derived from what it is named. Takes a full path, a uri string or a bare file name; only the part
 * after the last dot is read. Pure.
 */
fun mimeFromPath(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic", "heif" -> "image/heic"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "dng" -> "image/x-adobe-dng"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        else -> ""
    }
