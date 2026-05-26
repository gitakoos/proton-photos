package me.proton.photos.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.domain.entity.UserId
import me.proton.photos.data.db.dao.SyncStateDao
import me.proton.photos.data.db.entity.toEntity
import me.proton.photos.domain.entity.SyncState
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.SyncStateRepository
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
