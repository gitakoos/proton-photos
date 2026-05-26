package me.proton.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.proton.photos.data.db.entity.SyncStateEntity
import me.proton.photos.domain.entity.SyncStatus

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE userId = :userId")
    fun observeAll(userId: String): Flow<List<SyncStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SyncStateEntity>)

    @Query("SELECT * FROM sync_state WHERE localUri = :localUri LIMIT 1")
    suspend fun getByUri(localUri: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE cloudFileId = :cloudFileId LIMIT 1")
    suspend fun getByCloudId(cloudFileId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE status = 'SYNCED' AND (backedUpAtMs IS NULL OR backedUpAtMs < :timestampMs)")
    suspend fun getSyncedBefore(timestampMs: Long): List<SyncStateEntity>

    @Transaction
    @Query("UPDATE sync_state SET status = :newStatus WHERE localUri = :localUri")
    suspend fun updateStatus(localUri: String, newStatus: SyncStatus)

    @Query("DELETE FROM sync_state WHERE localUri = :localUri")
    suspend fun delete(localUri: String)

    @Query("DELETE FROM sync_state WHERE localUri IN (:localUris) AND status = 'LOCAL_ONLY'")
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)
}
