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

package eu.akoos.photos.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.entity.toEntity
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStateRepositoryImpl @Inject constructor(
    private val dao: SyncStateDao,
) : SyncStateRepository {

    override fun observeAll(userId: UserId): Flow<List<SyncState>> =
        dao.observeAll(userId.id).map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(state: SyncState, userId: UserId) {
        dao.upsert(state.toEntity(userId.id))
    }

    override suspend fun upsertAll(states: List<SyncState>, userId: UserId) {
        dao.upsertAll(states.map { it.toEntity(userId.id) })
    }

    override suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus) {
        dao.updateStatus(localUri, newStatus)
    }

    override suspend fun getByUri(localUri: String): SyncState? =
        dao.getByUri(localUri)?.toDomain()

    override suspend fun getByCloudId(cloudFileId: String): SyncState? =
        dao.getByCloudId(cloudFileId)?.toDomain()

    override suspend fun getSyncedBefore(timestampMs: Long): List<SyncState> =
        dao.getSyncedBefore(timestampMs).map { it.toDomain() }

    override suspend fun deleteLocalOnlyByUris(localUris: List<String>) {
        if (localUris.isEmpty()) return
        localUris.chunked(500).forEach { chunk -> dao.deleteLocalOnlyByUris(chunk) }
    }
}
