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

package eu.akoos.photos.domain.repository

import eu.akoos.photos.domain.entity.DeviceFileStamp

/**
 * Device copies free-up kept because nothing could tell whether they still hold what was backed up,
 * each with the stamp its file had and a key of its backup row as they were when that was found. Free-up
 * leaves such a copy out of its list for as long as both stay the same, so it is reported once rather
 * than offered on every run; a file written since, or a row backed up or recorded again, is checked again.
 */
interface FreeUpUncheckedStore {
    /** A kept copy: its file's [stamp] and its backup row's [rowKey] when it was kept. */
    data class Kept(val stamp: DeviceFileStamp, val rowKey: String)

    /** Every kept copy, by its device uri. */
    suspend fun all(): Map<String, Kept>

    /** Records [uri] as kept unchecked while its file and backup row stay as [kept] has them. */
    suspend fun mark(uri: String, kept: Kept)

    /** Forgets [uris], which are checked again the next time they come up. */
    suspend fun unmark(uris: Collection<String>)
}
