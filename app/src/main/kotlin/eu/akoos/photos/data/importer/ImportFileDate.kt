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

package eu.akoos.photos.data.importer

import android.content.Context
import android.net.Uri
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.Mp4CreationTime
import java.io.File
import java.time.ZoneId

/** The capture date an extracted import entry records in its own bytes: a video's mvhd, an image's EXIF. */
object ImportFileDate {

    /** Null when [file] records no date or cannot be read. Never throws. */
    fun read(context: Context, file: File, mimeType: String): Long? = try {
        when {
            mimeType.startsWith("video/", ignoreCase = true) -> Mp4CreationTime.read(file)
            mimeType.startsWith("image/", ignoreCase = true) ->
                ExifHelper.captureMs(ExifHelper.readMetadata(context, Uri.fromFile(file).toString()), ZoneId.systemDefault())
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
