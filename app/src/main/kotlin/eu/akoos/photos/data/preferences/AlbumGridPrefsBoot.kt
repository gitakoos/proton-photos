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

package eu.akoos.photos.data.preferences

import android.content.Context

/**
 * Synchronous mirror of `SettingsKeys.ALBUM_GRID_COLUMNS` backed by SharedPreferences, so the Albums
 * tab renders at the right cover-size column count on the very first frame. Without it the grid opens
 * from the DataStore collector's `initial` (2), then jumps to the stored 3/4 once the async read
 * lands, and the cards' `animateItem` animates that jump into a reshuffle every time the tab is
 * recomposed (returning from an album). Canonical store stays DataStore; [rememberAlbumColumns]
 * refreshes this mirror as the value changes. Mirrors [SeamlessGridPrefsBoot].
 */
object AlbumGridPrefsBoot {
    // Reuses the same boot-prefs file as the seamless mirror; the key keeps the two apart.
    private const val PREFS_NAME = "grid_layout_boot"
    private const val KEY_ALBUM_COLUMNS = "album_grid_columns"

    /** Returns the cached album-cover column count, or 2 (the roomy default) if never written. */
    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ALBUM_COLUMNS, 2)

    /** Mirrors the canonical DataStore value into the boot cache. Safe on any thread. */
    fun write(context: Context, columns: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ALBUM_COLUMNS, columns).apply()
    }
}
