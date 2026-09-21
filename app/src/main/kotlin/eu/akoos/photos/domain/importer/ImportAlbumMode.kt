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

package eu.akoos.photos.domain.importer

/**
 * How one import run treats the export's album folders. The choice is orthogonal to the upload: the
 * photos land in Drive Photos the same way in all three modes, only the album grouping differs.
 *
 * [NONE] sends every kept photo to the timeline and creates no albums. [SHELLS_ONLY] recreates the
 * export's albums in Drive as empty albums, without adding any photo to them. [WITH_PHOTOS] recreates the
 * albums and adds each photo to the album it came from.
 */
enum class ImportAlbumMode {
    NONE,
    SHELLS_ONLY,
    WITH_PHOTOS,
}
