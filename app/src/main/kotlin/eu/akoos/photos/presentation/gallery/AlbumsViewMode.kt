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

package eu.akoos.photos.presentation.gallery

/**
 * How the Albums tab lays out its albums and device folders: cover cards in a grid, or one row per
 * album with its cover as a thumbnail. Persisted as an ordinal in
 * [eu.akoos.photos.data.preferences.SettingsKeys.ALBUMS_VIEW_MODE].
 */
enum class AlbumsViewMode {
    Grid,
    List;

    companion object {
        val Default = Grid

        /** Read back a persisted ordinal, tolerating an absent or out-of-range value. */
        fun fromOrdinal(ordinal: Int?): AlbumsViewMode =
            ordinal?.let { AlbumsViewMode.entries.getOrNull(it) } ?: Default
    }
}
