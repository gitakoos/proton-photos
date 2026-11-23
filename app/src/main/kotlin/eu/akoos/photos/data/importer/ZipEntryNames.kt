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

/**
 * The entry names an import walk skips. An import never writes an entry under its own name (staging uses
 * fresh temp files), so every name is read, a leading `/` included (a Dropbox download carries a `/` entry
 * of its own); only a name that steps up with `..` is skipped.
 */
internal object ZipEntryNames {

    /** True for a name with a `..` step, which an import skips rather than reads. */
    fun climbsOut(name: String): Boolean = name.replace('\\', '/').split('/').any { it == ".." }
}
