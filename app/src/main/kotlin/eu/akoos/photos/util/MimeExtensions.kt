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
