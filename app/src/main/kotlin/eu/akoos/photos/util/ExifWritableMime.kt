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

import java.util.Locale

/**
 * The image containers ExifInterface can reliably WRITE via saveAttributes. Single source of truth
 * for the mime gate, so the metadata writer that stamps EXIF and the downloader that decides whether
 * a stamp will hold agree on exactly one set. HEIC / HEIF are absent on purpose: ExifInterface
 * cannot write them, so those keep only the MediaStore column.
 */
val EXIF_WRITABLE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

/**
 * True when [mimeType] is an image container ExifInterface can WRITE (see [EXIF_WRITABLE_MIMES]).
 * Case- and parameter-insensitive: the type is stripped of its `;` parameters, trimmed and
 * lowercased before the set check, so `image/JPEG` and `image/jpeg; codecs=…` both normalise to the
 * base type. Pure.
 */
fun isExifWritableImageMime(mimeType: String): Boolean =
    mimeType.substringBefore(';').trim().lowercase(Locale.ROOT) in EXIF_WRITABLE_MIMES
