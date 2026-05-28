package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

interface SyncStateRepository {
    fun observeAll(userId: UserId): Flow<List<SyncState>>
    suspend fun upsert(state: SyncState, userId: UserId)
    suspend fun upsertAll(states: List<SyncState>, userId: UserId)
    suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus)
    suspend fun getByUri(localUri: String): SyncState?
    suspend fun getByCloudId(cloudFileId: String): SyncState?
    suspend fun getSyncedBefore(timestampMs: Long): List<SyncState>
    /** Deletes LOCAL_ONLY entries whose URIs are no longer in-scope (excluded folders). */
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)
}
