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

import android.os.Environment

/**
 * Single source of truth for the on-device folder structure Proton Photos owns.
 *
 *   Pictures/                  — default destination for image downloads + copies
 *   Pictures/Recovered/        — files restored from the Hidden vault
 *   Pictures/<AlbumName>/      — user-created manual local albums (images)
 *   Movies/                    — default destination for video downloads + copies
 *   Movies/<AlbumName>/        — manual local albums (videos)
 *
 * Previously each path was nested under `Proton Photos/`. That created a redundant
 * gallery folder (every device gallery surfaces "Proton Photos" as a separate album,
 * duplicating "Pictures"), so the layout was flattened to match DownloadPhotosUseCase.
 * Files written before this change keep living at `Pictures/Proton Photos/…` —
 * MediaStore observers still see them.
 *
 * Every write into MediaStore should go through one of these helpers so future relocations
 * (e.g. renaming the root) need a single touch point.
 */
object ProtonPhotosStorage {
    /** Kept for migration / legacy detection of pre-flat-layout files. New writes don't
     *  use it as a path segment any more — see DEFAULT_PICTURES below. */
    const val ROOT_NAME = "Proton Photos"

    /** Default download/copy folder for images. Flat: just "Pictures". */
    val DEFAULT_PICTURES: String
        get() = Environment.DIRECTORY_PICTURES

    /** Default download/copy folder for videos. Flat: just "Movies". */
    val DEFAULT_MOVIES: String
        get() = Environment.DIRECTORY_MOVIES

    /** Strips path separators and trims so an album name can be used as a folder segment. */
    fun sanitize(name: String): String = name
        .trim()
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace(Regex("\\s+"), " ")
}
