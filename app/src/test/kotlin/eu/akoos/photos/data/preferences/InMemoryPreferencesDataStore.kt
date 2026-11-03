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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A preferences store held in memory, so a test reads back what the code under test wrote. */
internal class InMemoryPreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val writes = Mutex()

    override val data: Flow<Preferences> = state

    val current: Preferences get() = state.value

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writes.withLock { transform(state.value).also { state.value = it } }

    companion object {
        private const val SETTINGS_FILE_CLASS = "eu.akoos.photos.data.preferences.SettingsDataStoreKt"

        /** Answers [Context.settingsDataStore] of [context] with [store] until [uninstall]. */
        fun install(context: Context, store: DataStore<Preferences>) {
            mockkStatic(SETTINGS_FILE_CLASS)
            every { context.settingsDataStore } returns store
        }

        fun uninstall() = unmockkStatic(SETTINGS_FILE_CLASS)
    }
}
