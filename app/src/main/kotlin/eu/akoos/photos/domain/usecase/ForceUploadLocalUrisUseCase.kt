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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forces a set of local-only photo URIs to back up, even when their source folder is outside the
 * backup selection. For each URI it writes a "localUri=albumLinkId" entry into
 * [SettingsKeys.PENDING_ALBUM_ADDS] (the marker the upload pipeline treats as "force this URI to
 * upload"), seeds a [SyncStatus.LOCAL_ONLY] row when reconcile hasn't created one yet, and kicks
 * an upload pass.
 *
 * Two entry points share this core:
 *   - [forceUpload] for a plain backup with no album to join — uses the
 *     [SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM] sentinel so the pipeline forces the upload but
 *     skips the album-join step.
 *   - [queueForAlbum] for adding a not-yet-backed-up photo to a cloud album — the freshly
 *     uploaded file joins [albumLinkId] once its cloud id is known.
 */
@Singleton
class ForceUploadLocalUrisUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
) {

    /** Force [uris] to back up with no album to join. Returns the number of URIs queued. */
    suspend fun forceUpload(userId: UserId, uris: List<String>): Int =
        run(userId, SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM, uris)

    /** Force [uris] to back up and join [albumLinkId] once uploaded. Returns the number queued. */
    suspend fun queueForAlbum(userId: UserId, albumLinkId: String, uris: List<String>): Int =
        run(userId, albumLinkId, uris)

    private suspend fun run(userId: UserId, albumLinkId: String, uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[SettingsKeys.PENDING_ALBUM_ADDS] ?: emptySet()
            prefs[SettingsKeys.PENDING_ALBUM_ADDS] = existing + uris.map { "$it=$albumLinkId" }
        }
        for (uri in uris) {
            val existingRow = syncStateRepo.getByUri(uri)
            // Don't disturb a row that's already uploaded/uploading/hidden — only force one when
            // there's nothing that will upload this URI on its own.
            if (existingRow != null && existingRow.status != SyncStatus.LOCAL_ONLY) continue
            if (existingRow == null) {
                syncStateRepo.upsert(
                    SyncState(
                        localUri = uri,
                        cloudFileId = null,
                        localHash = "",
                        cloudHash = null,
                        status = SyncStatus.LOCAL_ONLY,
                        lastSyncAttemptMs = System.currentTimeMillis(),
                        lastSyncSuccessMs = null,
                        backedUpAtMs = null,
                        sizeBytes = 0L,
                    ),
                    userId,
                )
            }
        }
        // Kick the DURABLE background worker rather than uploading inline in the caller's scope:
        // an inline pass dies the moment the user leaves the screen, and never surfaces in the
        // Settings sync list. The worker survives navigation, resumes on app restart, and feeds
        // the same progress events the list observes. allowLowBattery = true because this is an
        // explicit user action; wifi-only still honours the user's network preference.
        val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
        eu.akoos.photos.worker.SyncWorker.runNow(context, wifiOnly = wifiOnly, allowLowBattery = true)
        return uris.size
    }
}
