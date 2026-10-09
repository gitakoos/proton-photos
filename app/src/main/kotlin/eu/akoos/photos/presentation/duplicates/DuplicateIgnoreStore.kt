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

package eu.akoos.photos.presentation.duplicates

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the duplicate groups the user chose to ignore, on the device and not tied to an account, so a
 * guest keeps them too and they survive a sign-out. The keys are [duplicateIgnoreKey]s.
 */
@Singleton
class DuplicateIgnoreStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val keys: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.DUPLICATE_IGNORED_GROUPS] ?: emptySet() }
        .distinctUntilChanged()

    suspend fun add(key: String) {
        context.settingsDataStore.edit { it[SettingsKeys.DUPLICATE_IGNORED_GROUPS] = (it[SettingsKeys.DUPLICATE_IGNORED_GROUPS] ?: emptySet()) + key }
    }

    suspend fun remove(toRemove: Collection<String>) {
        if (toRemove.isEmpty()) return
        context.settingsDataStore.edit {
            it[SettingsKeys.DUPLICATE_IGNORED_GROUPS] = (it[SettingsKeys.DUPLICATE_IGNORED_GROUPS] ?: emptySet()) - toRemove.toSet()
        }
    }
}
