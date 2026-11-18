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
import androidx.datastore.preferences.core.MutablePreferences

/**
 * Synchronous mirror of `SettingsKeys.THEME_MODE` backed by SharedPreferences so
 * `App.onCreate` can call `AppCompatDelegate.setDefaultNightMode` before the first
 * Activity inflates without blocking on DataStore IO. Canonical store stays DataStore;
 * writes mirror here from [SettingsViewModel] and [App] refreshes lazily.
 *
 * Values: `"dark"` | `"light"` | `"system"` (null when never written).
 */
object ThemePrefsBoot {
    private const val PREFS_NAME = "theme_boot"
    private const val KEY_MODE = "theme_mode"

    /**
     * Returns the cached mode key, or `"system"` if never written, the same default Settings and
     * the setup wizard show. The ProtonCore login activities stay dark regardless: App maps every
     * key except "light" to night mode for them.
     */
    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODE, null) ?: "system"
    }

    /**
     * Mirrors the canonical DataStore value into the boot cache. Safe to call on any
     * thread (SharedPreferences `apply()` schedules the disk write off the main thread).
     */
    fun write(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    /** Whether a mode was ever written here. Earlier versions always wrote one on their first start. */
    fun isWritten(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_MODE)
}

/**
 * The theme mode key the in-app screens follow: the saved [themeMode], else the legacy [legacyDark]
 * switch, else "system", matching what Settings and the setup wizard show for a fresh install.
 */
fun resolveThemeModeKey(themeMode: String?, legacyDark: Boolean?): String =
    themeMode ?: when (legacyDark) {
        true -> "dark"
        false -> "light"
        null -> "system"
    }

/**
 * Runs once per install. Earlier versions drew an install with no saved theme dark, so such an install
 * gets "dark" saved and keeps its look, and Settings shows what it sees. A fresh install keeps no saved
 * theme and follows the system. An earlier run shows as a written boot mirror ([mirrorWritten]), or as
 * other settings already saved on an install that was updated rather than freshly installed
 * ([installUpdated]). Returns true when "dark" was saved.
 */
fun settleThemeDefault(prefs: MutablePreferences, mirrorWritten: Boolean, installUpdated: Boolean): Boolean {
    if (prefs[SettingsKeys.THEME_DEFAULT_SETTLED] == true) return false
    val hasTheme = prefs[SettingsKeys.THEME_MODE] != null || prefs[SettingsKeys.DARK_MODE] != null
    val otherSettings = prefs.asMap().keys.any {
        it != SettingsKeys.THEME_MODE && it != SettingsKeys.DARK_MODE && it != SettingsKeys.THEME_DEFAULT_SETTLED
    }
    val earlierRun = mirrorWritten || (installUpdated && otherSettings)
    val keepDark = !hasTheme && earlierRun
    if (keepDark) prefs[SettingsKeys.THEME_MODE] = "dark"
    prefs[SettingsKeys.THEME_DEFAULT_SETTLED] = true
    return keepDark
}
