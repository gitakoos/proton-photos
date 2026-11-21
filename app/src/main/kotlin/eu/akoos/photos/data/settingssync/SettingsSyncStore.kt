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

package eu.akoos.photos.data.settingssync

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.LanguagePrefsBoot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's synced settings as the shared backup reads and writes them, and the way another device's changes are
 * carried out here. The settings are the ones of [SettingsSyncRegistry]; their times come from [SettingsSyncTimes].
 * Only a signed-in device stamps or reads anything: the callers are the shared backup's round and its change watch,
 * neither of which runs for a guest.
 *
 * Another device's value is written the way this phone writes it itself, together with the time it came with, so it
 * is not sent back as a new change. The two boot copies of the theme and the language are refreshed with it, so the
 * next cold start already opens in them.
 */
@Singleton
class SettingsSyncStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Stamps what the user changed since the last stamping. True when a stamp was written, so the change is a reason
     * for a round.
     */
    suspend fun stampChanges(nowMs: Long = System.currentTimeMillis()): Boolean {
        var stamped = false
        context.settingsDataStore.edit { stamped = SettingsSyncTimes.stampChanges(it, nowMs) }
        return stamped
    }

    /** This device's settings with the time of each, after stamping what the user changed. */
    internal suspend fun load(nowMs: Long = System.currentTimeMillis()): SettingsSyncState =
        SettingsSyncTimes.stateOf(context.settingsDataStore.edit { SettingsSyncTimes.stampChanges(it, nowMs) })

    /** Carries out [changes], the later values other devices' copies brought. Returns how many settings changed. */
    internal suspend fun apply(changes: SettingsCopyMerge.Changes): Int {
        if (changes.isEmpty) return 0
        var applied = emptySet<String>()
        val prefs = context.settingsDataStore.edit { applied = SettingsSyncTimes.applyChanges(it, changes) }
        if ("appearance.theme" in applied) ThemePrefsBoot.write(context, prefs[SettingsKeys.THEME_MODE] ?: "system")
        if ("appearance.language" in applied) LanguagePrefsBoot.write(context, prefs[SettingsKeys.LANGUAGE] ?: "system")
        return applied.size
    }
}
