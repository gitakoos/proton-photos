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

package eu.akoos.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.domain.entity.SyncStatus

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
