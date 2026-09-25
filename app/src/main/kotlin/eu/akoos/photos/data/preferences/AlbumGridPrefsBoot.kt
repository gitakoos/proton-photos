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
 * Synchronous mirror of `SettingsKeys.ALBUM_GRID_COLUMNS`, `SettingsKeys.ALBUMS_SHOW_COLLECTION` and
 * `SettingsKeys.ALBUMS_VIEW_MODE` backed by SharedPreferences, so the Albums tab renders at the right
 * cover-size column count, with or without its Collection card, and as a grid or a list, on the very
 * first frame. Without it the grid opens from the DataStore collectors' `initial` values (2 columns,
 * card shown, grid), then jumps to the stored ones once the async read lands, and the cards'
 * `animateItem` animates that jump into a reshuffle every time the tab is recomposed (returning from an
 * album). Canonical store stays DataStore; [rememberAlbumColumns], [rememberAlbumsShowCollection] and
 * [rememberAlbumsViewMode] refresh this mirror as the values change. Mirrors [SeamlessGridPrefsBoot].
 */
object AlbumGridPrefsBoot {
    // Reuses the same boot-prefs file as the seamless mirror; the keys keep them apart.
    private const val PREFS_NAME = "grid_layout_boot"
    private const val KEY_ALBUM_COLUMNS = "album_grid_columns"
    private const val KEY_SHOW_COLLECTION = "albums_show_collection"
    private const val KEY_VIEW_MODE = "albums_view_mode"

    /** Returns the cached album-cover column count, or 2 (the roomy default) if never written. */
    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ALBUM_COLUMNS, 2)

    /** Mirrors the canonical DataStore value into the boot cache. Safe on any thread. */
    fun write(context: Context, columns: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ALBUM_COLUMNS, columns).apply()
    }

    /** Returns the cached Collection-card switch, or true (the card shown) if never written. */
    fun readShowCollection(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_COLLECTION, true)

    /** Mirrors the canonical DataStore value of the Collection-card switch into the boot cache. Safe on
     *  any thread. */
    fun writeShowCollection(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_COLLECTION, shown).apply()
    }

    /** Returns the cached view-mode ordinal, or null if never written, which reads as the grid. */
    fun readViewMode(context: Context): Int? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .takeIf { it.contains(KEY_VIEW_MODE) }
            ?.getInt(KEY_VIEW_MODE, 0)

    /** Mirrors the canonical DataStore value of the view mode into the boot cache. Safe on any thread. */
    fun writeViewMode(context: Context, ordinal: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_VIEW_MODE, ordinal).apply()
    }
}
