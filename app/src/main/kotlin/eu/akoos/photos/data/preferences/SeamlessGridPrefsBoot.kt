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
 * Synchronous mirror of `SettingsKeys.SEAMLESS_GRID` backed by SharedPreferences so a freshly opened
 * photo grid can pick its layout (edge-to-edge vs padded) on the first frame without a main-thread
 * `runBlocking { DataStore.data.first() }`, which could stall for tens to hundreds of ms on cold
 * cache. Canonical store stays DataStore; the grid collector refreshes this mirror as the value
 * changes and [eu.akoos.photos.presentation.settings.SettingsViewModel] writes it on toggle.
 *
 * Value: true when the seamless (edge-to-edge) grid is on; false (the default) keeps padded, rounded
 * tiles. Mirrors the same pattern as [ThemePrefsBoot] and [LanguagePrefsBoot].
 */
object SeamlessGridPrefsBoot {
    private const val PREFS_NAME = "grid_layout_boot"
    private const val KEY_SEAMLESS = "seamless_grid"

    /** Returns the cached seamless flag, or false if never written. */
    fun read(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SEAMLESS, false)
    }

    /**
     * Mirrors the canonical DataStore value into the boot cache. Safe to call on any thread
     * (SharedPreferences `apply()` schedules the disk write off the main thread).
     */
    fun write(context: Context, seamless: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SEAMLESS, seamless).apply()
    }
}
