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

package eu.akoos.photos.data.preferences

import android.content.Context

/**
 * Synchronous mirror of `SettingsKeys.LANGUAGE` backed by SharedPreferences so
 * `App.onCreate` can call `AppCompatDelegate.setApplicationLocales` before the
 * first Activity inflates without blocking on DataStore IO. Canonical store
 * stays DataStore; writes mirror here from `SettingsViewModel` /
 * `OnboardingViewModel` and `App` refreshes lazily.
 *
 * Values: BCP-47 tag (`"en"`, `"hu"`, `"de"`, ...) or the sentinel `"system"`
 * meaning "follow the OS locale" (empty `LocaleListCompat`).
 *
 * Why a separate boot mirror: the locale must be applied to AppCompatDelegate
 * exactly once at process startup so the externally-launched ProtonCore login
 * Activities (XML-based, outside the app's Compose tree) render in the chosen
 * language. Calling `setApplicationLocales` after process startup forces an
 * Activity recreate which loses navigation state — runtime locale switches go
 * through `LocaleOverride` in the Compose tree instead.
 */
object LanguagePrefsBoot {
    private const val PREFS_NAME = "language_boot"
    private const val KEY_TAG = "language_tag"

    /**
     * Returns the cached language tag, or `"system"` if never written (matches
     * the empty-locale-list semantic — let the OS pick).
     */
    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TAG, null) ?: "system"
    }

    /**
     * Mirrors the canonical DataStore value into the boot cache. Safe to call
     * on any thread (SharedPreferences `apply()` schedules the disk write off
     * the main thread).
     */
    fun write(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TAG, tag).apply()
    }
}
