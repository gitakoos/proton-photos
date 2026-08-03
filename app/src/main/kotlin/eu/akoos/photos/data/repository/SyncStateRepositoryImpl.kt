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

package eu.akoos.photos.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.entity.toEntity
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.flatMapSqlChunks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStateRepositoryImpl @Inject constructor(
    private val dao: SyncStateDao,
) : SyncStateRepository {

    override fun observeAll(userId: UserId): Flow<List<SyncState>> =
        dao.observeAll(userId.id).map { list -> list.map { it.toDomain() } }

    override fun countPendingUploads(userId: UserId): Flow<Int> =
        dao.countPendingUploads(userId.id)

    override suspend fun upsert(state: SyncState, userId: UserId) {
        dao.upsert(state.toEntity(userId.id))
    }

    override suspend fun upsertAll(states: List<SyncState>, userId: UserId) {
        dao.upsertAll(states.map { it.toEntity(userId.id) })
    }

    // Named for the caller's flow: the caller deletes the local MediaStore copy first, then this
    // flips the row's status (to CLOUD_ONLY). It does not itself delete anything.
    override suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus) {
        dao.updateStatus(localUri, newStatus)
    }

    override suspend fun getByUri(localUri: String): SyncState? =
        dao.getByUri(localUri)?.toDomain()

    override suspend fun getByCloudId(cloudFileId: String): SyncState? =
        dao.getByCloudId(cloudFileId)?.toDomain()

    override suspend fun cloudPairedLinkIds(userId: UserId, localUris: List<String>): Map<String, String> =
        localUris.flatMapSqlChunks { chunk -> dao.cloudPairs(userId.id, chunk) }
            .associate { it.localUri to it.cloudFileId }

    // status is persisted as the enum's .name (see the generated __SyncStatus_enumToString), so the
    // claim/reset queries take those exact string forms.
    override suspend fun claimForUpload(localUri: String): Int =
        dao.claimForUpload(
            localUri = localUri,
            uploading = SyncStatus.UPLOADING.name,
            localOnly = SyncStatus.LOCAL_ONLY.name,
        )

    override suspend fun resetStaleUploadingClaims() =
        dao.resetStaleUploadingClaims(
            uploading = SyncStatus.UPLOADING.name,
            localOnly = SyncStatus.LOCAL_ONLY.name,
        )

    override suspend fun getSyncedBefore(userId: UserId, timestampMs: Long): List<SyncState> =
        dao.getSyncedBefore(userId.id, timestampMs).map { it.toDomain() }

    override suspend fun getVaulted(userId: UserId): List<SyncState> =
        dao.getVaulted(userId.id).map { it.toDomain() }

    override suspend fun cloudIdsWithLivePairing(userId: UserId): Set<String> =
        dao.cloudIdsWithLivePairing(userId.id).toHashSet()

    override suspend fun clearHiddenForCloudId(cloudFileId: String) =
        dao.deleteHiddenForCloudId(cloudFileId)

    override suspend fun deleteLocalOnlyByUris(localUris: List<String>) {
        if (localUris.isEmpty()) return
        localUris.chunked(500).forEach { chunk -> dao.deleteLocalOnlyByUris(chunk) }
    }

    override suspend fun delete(localUri: String) = dao.delete(localUri)

    override suspend fun markQueued(localUri: String, source: String, at: Long) =
        dao.markQueued(localUri, source, at)

    override suspend fun clearQueued(localUri: String) = dao.clearQueued(localUri)

    override suspend fun clearQueuedForSynced(localUri: String) = dao.clearQueuedForSynced(localUri)

    override suspend fun getQueueSource(localUri: String): String? = dao.getQueueSource(localUri)

    override suspend fun clearManualQueue(): Int = dao.clearManualQueue()
}
