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

import android.os.Environment

/**
 * Single source of truth for the on-device folder structure Proton Photos owns.
 *
 *   DCIM/Camera/               loose image + video downloads, copies, edits and screenshots
 *   DCIM/<AlbumName>/          downloads from a cloud album (e.g. DCIM/Budapest)
 *
 * Downloads land in DCIM so they sit with the camera roll and a device that backs up DCIM treats
 * them as on-device photos, and so a per-album download creates a gallery folder like the OEM
 * gallery does. Earlier builds wrote to Pictures/ and Pictures/&lt;AlbumName&gt;/ (and before that
 * under a redundant `Proton Photos/` root); files written then keep living where they are, since
 * MediaStore observers still see them.
 *
 * Every write into MediaStore should go through one of these helpers so future relocations
 * (e.g. renaming the root) need a single touch point.
 */
object ProtonPhotosStorage {
    /** Kept for migration / legacy detection of pre-flat-layout files. New writes don't
     *  use it as a path segment any more — see [DEFAULT_PICTURES] below. */
    const val ROOT_NAME = "Proton Photos"

    /** Default destination for loose image downloads, copies, edits and screenshots: DCIM/Camera.
     *  Landing in the camera folder keeps the file with the camera roll and lets a device that backs
     *  up DCIM pick it up as an on-device photo. */
    val DEFAULT_PICTURES: String
        get() = "${Environment.DIRECTORY_DCIM}/Camera"

    /** Videos share the camera folder, DCIM/Camera. */
    val DEFAULT_MOVIES: String
        get() = "${Environment.DIRECTORY_DCIM}/Camera"

    /** An album downloads into DCIM/&lt;AlbumName&gt;, mirroring how gallery apps create a per-folder
     *  album on the device (e.g. a "Budapest" album lands in DCIM/Budapest). */
    fun albumFolder(name: String): String = "${Environment.DIRECTORY_DCIM}/${sanitize(name)}"

    /** Strips path separators and trims so an album name can be used as a folder segment. */
    fun sanitize(name: String): String = name
        .trim()
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace(Regex("\\s+"), " ")
}
