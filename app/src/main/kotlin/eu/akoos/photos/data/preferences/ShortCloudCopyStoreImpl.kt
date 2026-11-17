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
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.domain.repository.ShortCloudCopyStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** [ShortCloudCopyStore] in the settings store. */
@Singleton
class ShortCloudCopyStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShortCloudCopyStore {

    override suspend fun all(): Set<String> =
        context.settingsDataStore.data.first()[SettingsKeys.SHORT_CLOUD_COPY_IDS] ?: emptySet()

    override suspend fun add(linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.SHORT_CLOUD_COPY_IDS] = (p[SettingsKeys.SHORT_CLOUD_COPY_IDS] ?: emptySet()) + linkIds
        }
    }

    override suspend fun editorCopiesCheckStartedMs(userId: String, nowMs: Long): Long {
        var started = nowMs
        context.settingsDataStore.edit { p ->
            val stored = p[SettingsKeys.editorCopiesCheckStartedKey(userId)]
            if (stored != null) started = stored else p[SettingsKeys.editorCopiesCheckStartedKey(userId)] = nowMs
        }
        return started
    }

    override suspend fun editorCopiesChecked(userId: String): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.editorCopiesCheckedKey(userId)] == true

    override suspend fun markEditorCopiesChecked(userId: String) {
        context.settingsDataStore.edit { p -> p[SettingsKeys.editorCopiesCheckedKey(userId)] = true }
    }
}
