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

package eu.akoos.photos.data.albumorder

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.AlbumOrderDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** An album's stored photo order: its mode word (null follows the default) and its hand-made order, if any. */
data class StoredAlbumOrder(val mode: String? = null, val arrangement: List<String> = emptyList()) {
    val hasArrangement: Boolean get() = arrangement.isNotEmpty()
}

/**
 * The photo order inside each album and device folder. A cloud album is keyed by the signed-in account and its link
 * id and travels in the shared backup; a device folder is always kept under the local user as `folder:<name>`, on
 * this device only, since a folder's files differ from phone to phone.
 */
@Singleton
class AlbumOrderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AlbumOrderDao,
) {
    private val seeded = Collections.synchronizedSet(HashSet<String>())

    fun observe(userKey: String, albumKey: String): Flow<StoredAlbumOrder> =
        combine(dao.observeState(userKey, albumKey), dao.observeArrangement(userKey, albumKey)) { state, keys ->
            StoredAlbumOrder(state?.mode, keys)
        }
            .onStart { seedLegacyModes(userKey) }
            .distinctUntilChanged()

    /** Every album of [userKey] with a mode of its own, album key to mode word. */
    fun observeModes(userKey: String): Flow<Map<String, String>> =
        dao.observeModes(userKey)
            .map { rows -> rows.mapNotNull { row -> row.mode?.let { row.albumKey to it } }.toMap() }
            .onStart { seedLegacyModes(userKey) }
            .distinctUntilChanged()

    suspend fun arrangement(userKey: String, albumKey: String): List<String> = dao.arrangement(userKey, albumKey)

    /** Each album's newest cached photo time, by link id, for the "Newest photo" album sort. */
    fun observeNewestPhotoTimes(userKey: String): Flow<Map<String, Long>> =
        dao.observeNewestPhotoTimes(userKey)
            .map { rows -> rows.associate { it.albumLinkId to it.newest } }
            .distinctUntilChanged()

    suspend fun setMode(userKey: String, albumKey: String, mode: String, nowMs: Long = System.currentTimeMillis()) {
        dao.setMode(userKey, albumKey, mode, nowMs)
    }

    /** A Done in arrange mode: [keys] become the album's order and the album switches to it, in one write. */
    suspend fun saveCustom(userKey: String, albumKey: String, keys: List<String>, nowMs: Long = System.currentTimeMillis()) {
        dao.saveCustom(userKey, albumKey, keys, AlbumOrderSnapshot.MODE_CUSTOM, nowMs)
    }

    /**
     * Copies the per-album Newest / Oldest choices kept in the settings before the choice lived here, at time 0 so
     * a choice from another device wins, once per account and run. An album that already has a mode keeps it.
     */
    private suspend fun seedLegacyModes(userKey: String) {
        if (userKey == LOCAL_USER || !seeded.add(userKey)) return
        val overrides = runCatching {
            context.settingsDataStore.data.first()[SettingsKeys.ALBUM_PHOTO_SORT_OVERRIDES]
        }.getOrNull() ?: return
        for (entry in overrides) {
            val cut = entry.indexOf(':')
            if (cut <= 0) continue
            val mode = modeOfOrdinal(entry.substring(0, cut).toIntOrNull()) ?: continue
            runCatching { dao.seedMode(userKey, entry.substring(cut + 1), mode) }
        }
    }

    /** [userId]'s album orders with their times, as the shared backup writes them. */
    suspend fun loadSyncState(userId: String): AlbumOrderSyncState {
        seedLegacyModes(userId)
        val state = AlbumOrderSyncState()
        for (row in dao.states(userId)) {
            val mode = row.mode
            val modeAt = row.modeAt
            if (mode != null && modeAt != null && mode in AlbumOrderSnapshot.MODES) {
                state.modes[row.albumKey] = AlbumModeState(mode, modeAt)
            }
            val orderAt = row.orderAt ?: continue
            state.orders[row.albumKey] = AlbumArrangementState(dao.arrangement(userId, row.albumKey), orderAt)
        }
        return state
    }

    /** Writes what other devices' copies brought, each value with the time it came with. Returns the albums changed. */
    suspend fun apply(userId: String, changes: AlbumOrderCopyMerge.Changes): Int {
        for ((album, taken) in changes.orders) dao.setArrangement(userId, album, taken.order, taken.at)
        for ((album, taken) in changes.modes) dao.setMode(userId, album, taken.mode, taken.at)
        return (changes.orders.keys + changes.modes.keys).size
    }

    suspend fun deleteForUser(userId: String) {
        dao.deleteForUser(userId)
        seeded.remove(userId)
    }

    companion object {
        val LOCAL_USER: String = PhotoLocationEntity.LOCAL_USER

        /** The key a device folder's order is kept under, beside the cloud albums' link ids. */
        fun folderKey(folderName: String): String = "folder:$folderName"

        /** The mode word of a stored sort ordinal: 0 newest, 1 oldest, 2 custom. */
        fun modeOfOrdinal(ordinal: Int?): String? = when (ordinal) {
            0 -> AlbumOrderSnapshot.MODE_NEWEST
            1 -> AlbumOrderSnapshot.MODE_OLDEST
            2 -> AlbumOrderSnapshot.MODE_CUSTOM
            else -> null
        }
    }
}
