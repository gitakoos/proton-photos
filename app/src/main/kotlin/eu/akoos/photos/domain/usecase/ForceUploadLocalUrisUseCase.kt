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

package eu.akoos.photos.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.db.entity.UploadAlbumTargetEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forces a set of local-only photo URIs to back up, even when their source folder is outside the
 * backup selection. For each URI it stamps an explicit upload intent on the sync_state row
 * (queued / queueSource / queuedAt), seeds a [SyncStatus.LOCAL_ONLY] row when reconcile hasn't
 * created one yet, and kicks an upload pass. The DB queue is the single source of truth: the
 * upload selector picks the row up because it is queued, no DataStore side-marker involved.
 *
 * Two entry points share this core:
 *   - [forceUpload] for a plain backup with no album to join: stamps [QueueSource.MANUAL] and
 *     seeds a LOCAL_ONLY row; there is no album target, so the upload just backs the file up.
 *   - [queueForAlbum] for adding a not-yet-backed-up photo to a cloud album: stamps
 *     [QueueSource.ALBUM_ADD] and records the target album in [uploadAlbumTargetDao], so the
 *     freshly uploaded file joins [albumLinkId] once its cloud id is known.
 */
@Singleton
class ForceUploadLocalUrisUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val uploadAlbumTargetDao: UploadAlbumTargetDao,
) {

    /** Force [uris] to back up with no album to join. Returns the number of URIs queued. */
    suspend fun forceUpload(userId: UserId, uris: List<String>): Int =
        run(userId, SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM, uris)

    /** Force [uris] to back up and join [albumLinkId] once uploaded. Returns the number queued. */
    suspend fun queueForAlbum(userId: UserId, albumLinkId: String, uris: List<String>): Int =
        run(userId, albumLinkId, uris)

    private suspend fun run(userId: UserId, albumLinkId: String, uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        // The no-album sentinel means a plain "back up now" (MANUAL); a real linkId means the photo
        // must join that album once uploaded (ALBUM_ADD). A single timestamp for the whole request
        // keeps every queued row's queuedAt aligned.
        val isAlbumAdd = albumLinkId != SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM
        val queueSource = if (isAlbumAdd) QueueSource.ALBUM_ADD else QueueSource.MANUAL
        val now = System.currentTimeMillis()
        // Taken in slices: one read for a slice's rows and one write for the rows it seeds, instead of three
        // writes per photo, each of which told every list watching the table (on a big library a back-up of a
        // few thousand photos took minutes to queue, and the upload only began once it had).
        for (slice in uris.chunked(QUEUE_SLICE)) {
            val existingByUri = syncStateRepo.getByUris(slice).associateBy { it.localUri }
            val toSeed = ArrayList<SyncState>(slice.size)
            val albumTargets = ArrayList<UploadAlbumTargetEntity>()
            // Photos already on Drive that join the album straight away: one network call each, run after the
            // slice has been written, so a row read as waiting is not left to be overwritten while they run.
            val joinNow = ArrayList<Pair<String, String>>()
            for (uri in slice) {
                val existingRow = existingByUri[uri]
                // Skip seeding only when the URI is genuinely on Drive (the album-add drain joins it
                // once it sees the cloudFileId) or is owned by another flow we must not disturb.
                // A reinstall/re-index can leave a stale SYNCED/CLOUD_ONLY row with a null cloudFileId
                // (never actually uploaded); that case falls through to be reseeded as LOCAL_ONLY so it
                // uploads and then joins, instead of getting stuck.
                val cloudFileId = existingRow?.cloudFileId
                if (cloudFileId != null) {
                    // Already on Drive, so the upload pipeline never re-runs for it. Record the target
                    // and drain the join here directly: once the album holds the photo drop the pair,
                    // otherwise leave the target row so the upload pass's target-table drain retries it.
                    // The add reports an unsent or refused photo in its result, not by throwing, so the
                    // result decides. A single linkId per request keeps the server's per-request link cap
                    // satisfied.
                    if (isAlbumAdd) {
                        albumTargets.add(UploadAlbumTargetEntity(localUri = uri, albumLinkId = albumLinkId))
                        joinNow.add(uri to cloudFileId)
                    }
                    continue
                }
                if (existingRow?.status == SyncStatus.HIDDEN) continue
                if (existingRow?.status == SyncStatus.UPLOADING) {
                    // Already uploading, so it is never seeded again: that would upload it a second time.
                    // An album-add records the target, which the upload in flight joins once it lands (or
                    // the next pass's drain, when it landed before the target was written), and stamps the
                    // intent so an upload that fails is retried as this album-add. A stamp that lands after
                    // the upload finished comes off again, since a backed-up row carries no intent.
                    if (isAlbumAdd) {
                        uploadAlbumTargetDao.insertIgnore(uri, albumLinkId)
                        syncStateRepo.markQueued(uri, queueSource, now)
                        if (syncStateRepo.getByUri(uri)?.cloudFileId != null) syncStateRepo.clearQueuedForSynced(uri)
                    }
                    continue
                }
                toSeed.add(
                    SyncState(
                        localUri = uri,
                        cloudFileId = null,
                        localHash = existingRow?.localHash ?: "",
                        cloudHash = null,
                        status = SyncStatus.LOCAL_ONLY,
                        lastSyncAttemptMs = System.currentTimeMillis(),
                        lastSyncSuccessMs = null,
                        backedUpAtMs = null,
                        sizeBytes = existingRow?.sizeBytes ?: 0L,
                    ),
                )
                // An album-add also records the target album so the upload pass can join it once the cloud id is
                // known; a manual "back up now" has no target row, so its upload just backs the file up.
                if (isAlbumAdd) albumTargets.add(UploadAlbumTargetEntity(localUri = uri, albumLinkId = albumLinkId))
            }
            if (albumTargets.isNotEmpty()) uploadAlbumTargetDao.insertIgnoreAll(albumTargets)
            // The explicit upload intent on each seeded row (queued/queueSource/queuedAt) is what the upload
            // selector picks up, so no DataStore side-marker is needed.
            if (toSeed.isNotEmpty()) syncStateRepo.seedAndMarkQueued(toSeed, userId, queueSource, now)
            for ((uri, cloudFileId) in joinNow) {
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudFileId)) }
                    .onSuccess { result ->
                        if (cloudFileId in result.succeededLinkIds) uploadAlbumTargetDao.deleteTarget(uri, albumLinkId)
                    }
            }
        }
        // Kick the DURABLE background worker rather than uploading inline in the caller's scope:
        // an inline pass dies the moment the user leaves the screen, and never surfaces in the
        // Settings sync list. The worker survives navigation, resumes on app restart, and feeds
        // the same progress events the list observes. allowLowBattery = true because this is an
        // explicit user action. Wi-Fi-only does not hold these rows back either: the worker runs on
        // any connection, and the upload pass lets a MANUAL or ALBUM_ADD row through on mobile data.
        val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
        eu.akoos.photos.worker.SyncWorker.runNow(context, wifiOnly = wifiOnly, allowLowBattery = true)
        return uris.size
    }
}

/** Rows a back-up request reads and writes at a time, inside the SQL host-variable limit of older Android. */
private const val QUEUE_SLICE = 500
