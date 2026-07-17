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

package eu.akoos.photos.data.upload

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.QueueSource
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingAddsImport"

/**
 * One-shot migration of the legacy [SettingsKeys.PENDING_ALBUM_ADDS] DataStore set into the
 * explicit upload queue. Each old entry is "localUri=albumLinkId" (split on the FIRST '=', since a
 * MediaStore URI never contains one but a base64 album linkId can). A no-album sentinel entry
 * becomes a plain MANUAL queue mark; a real album entry records the target album AND an ALBUM_ADD
 * queue mark.
 *
 * Idempotent by construction: [UploadAlbumTargetDao.insertIgnore] and [SyncStateDao.markQueued] can
 * both be replayed safely, and ALL DB rows are written BEFORE the DataStore key is removed. So a
 * process kill mid-import simply re-runs the whole conversion next launch. The
 * [SettingsKeys.PENDING_ALBUM_ADDS_MIGRATED] flag is the once-guard; it is flipped last, together
 * with the key removal, so the guard is never set while any work is still pending.
 *
 * This is a one-time upgrade importer: it converts whatever leftover PENDING_ALBUM_ADDS entries a
 * pre-queue install still had, then retires the key. PENDING_ALBUM_ADDS is no longer written by any
 * path, so this importer is its only remaining reader and does nothing on a fresh install.
 */
@Singleton
class PendingAlbumAddsImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateDao: SyncStateDao,
    private val uploadAlbumTargetDao: UploadAlbumTargetDao,
) {

    suspend fun runOnce() {
        runCatching {
            val prefs = context.settingsDataStore.data.first()
            if (prefs[SettingsKeys.PENDING_ALBUM_ADDS_MIGRATED] == true) return@runCatching

            val entries = prefs[SettingsKeys.PENDING_ALBUM_ADDS].orEmpty()
            val now = System.currentTimeMillis()
            var marked = 0

            // Write EVERY DB row first. insertIgnore + markQueued are idempotent, so re-running the
            // whole loop after a mid-import kill is harmless.
            for (entry in entries) {
                val idx = entry.indexOf('=')
                if (idx <= 0) continue
                val localUri = entry.substring(0, idx)
                val albumLinkId = entry.substring(idx + 1)
                if (albumLinkId == SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM) {
                    syncStateDao.markQueued(localUri, QueueSource.MANUAL, now)
                } else {
                    uploadAlbumTargetDao.insertIgnore(localUri, albumLinkId)
                    syncStateDao.markQueued(localUri, QueueSource.ALBUM_ADD, now)
                }
                marked++
            }

            // Only now that every DB row is durable, clear the legacy key and set the guard together.
            // A kill before this leaves the guard false and the key intact, so the import re-runs.
            context.settingsDataStore.edit { p ->
                p.remove(SettingsKeys.PENDING_ALBUM_ADDS)
                p[SettingsKeys.PENDING_ALBUM_ADDS_MIGRATED] = true
            }
            Log.d(TAG, "Imported $marked pending album-add entr${if (marked == 1) "y" else "ies"} into the upload queue")
        }.onFailure { e ->
            // A failure leaves the guard false and the key intact, so the next launch retries cleanly.
            Log.w(TAG, "Pending album-adds import failed, will retry next launch: ${e.message}")
        }
    }
}
