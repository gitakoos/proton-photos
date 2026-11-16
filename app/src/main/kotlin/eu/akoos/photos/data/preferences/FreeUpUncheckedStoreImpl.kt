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
import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.repository.FreeUpUncheckedStore
import eu.akoos.photos.domain.repository.FreeUpUncheckedStore.Kept
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** [FreeUpUncheckedStore] in the settings store, one "size|modified|rowKey|uri" entry per kept copy. */
@Singleton
class FreeUpUncheckedStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FreeUpUncheckedStore {

    override suspend fun all(): Map<String, Kept> =
        decode(runCatching { context.settingsDataStore.data.first()[SettingsKeys.FREE_UP_UNCHECKED] }.getOrNull())

    override suspend fun mark(uri: String, kept: Kept) {
        edit { entries -> (entries - uri) + (uri to kept) }
    }

    override suspend fun unmark(uris: Collection<String>) {
        if (uris.isEmpty()) return
        edit { entries -> entries - uris.toSet() }
    }

    private suspend fun edit(change: (Map<String, Kept>) -> Map<String, Kept>) {
        // A write the settings store refuses leaves the copy to be checked again next run, which keeps it.
        runCatching {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.FREE_UP_UNCHECKED] = encode(change(decode(p[SettingsKeys.FREE_UP_UNCHECKED])))
            }
        }
    }

    companion object {
        /** The most copies kept at once; past it the oldest entries go and are checked again. */
        internal const val MAX_ENTRIES = 5_000

        internal fun encode(entries: Map<String, Kept>): Set<String> =
            entries.entries.toList().takeLast(MAX_ENTRIES).mapTo(LinkedHashSet()) { (uri, kept) ->
                "${kept.stamp.sizeBytes}|${kept.stamp.modifiedSec}|${kept.rowKey}|$uri"
            }

        internal fun decode(raw: Set<String>?): Map<String, Kept> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Kept>()
            for (entry in raw) {
                val parts = entry.split('|', limit = 4)
                if (parts.size != 4 || parts[2].isEmpty() || parts[3].isEmpty()) continue
                val size = parts[0].toLongOrNull() ?: continue
                val modified = parts[1].toLongOrNull() ?: continue
                out[parts[3]] = Kept(DeviceFileStamp(size, modified), parts[2])
            }
            return out
        }
    }
}
