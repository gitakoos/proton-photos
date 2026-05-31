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

package eu.akoos.photos.domain.entity

data class LocalAlbum(
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
    val items: List<LocalMediaItem>,
    val backedUpCount: Int = 0,
    /** True when the user created this album from inside the app (vs auto-discovered bucket). */
    val isManual: Boolean = false,
    /**
     * True when this album has NO real MediaStore bucket files — every member is a virtual
     * reference recorded in [eu.akoos.photos.data.preferences.SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP].
     * Pure-virtual albums are safe to rename and delete (DataStore-only mutations). Bucket-
     * derived albums (Camera, Screenshots, etc.) are device folders we won't touch.
     */
    val isVirtualOnly: Boolean = false,
) {
    val isFullyBackedUp: Boolean get() = itemCount > 0 && backedUpCount >= itemCount
    val hasAnyBackedUp: Boolean get() = backedUpCount > 0
}
