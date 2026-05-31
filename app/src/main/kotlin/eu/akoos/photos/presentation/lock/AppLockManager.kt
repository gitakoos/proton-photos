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

package eu.akoos.photos.presentation.lock

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isLockEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.APP_LOCK_ENABLED] ?: false }

    /**
     * Timeout in minutes between backgrounding the app and re-locking it on resume.
     * 0 = lock immediately (the default before this option existed). Larger values let the user briefly
     * switch to other apps without re-authenticating on every return.
     */
    val lockTimeoutMinutes: Flow<Int> = context.settingsDataStore.data
        .map { it[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] ?: 0 }

    suspend fun setLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setLockTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceAtLeast(0)
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] = clamped }
    }
}
