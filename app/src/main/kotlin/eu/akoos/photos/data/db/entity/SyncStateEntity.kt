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

package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

@Entity(
    tableName = "sync_state",
    indices = [Index(value = ["userId"]), Index(value = ["status"])],
)
data class SyncStateEntity(
    @PrimaryKey val localUri: String,
    val userId: String,
    val cloudFileId: String?,
    val localHash: String,
    val cloudHash: String?,
    val status: SyncStatus,
    val lastSyncAttemptMs: Long,
    val lastSyncSuccessMs: Long?,
    val backedUpAtMs: Long?,
    val sizeBytes: Long,
) {
    fun toDomain() = SyncState(
        localUri = localUri,
        cloudFileId = cloudFileId,
        localHash = localHash,
        cloudHash = cloudHash,
        status = status,
        lastSyncAttemptMs = lastSyncAttemptMs,
        lastSyncSuccessMs = lastSyncSuccessMs,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = sizeBytes,
    )
}

fun SyncState.toEntity(userId: String) = SyncStateEntity(
    localUri = localUri,
    userId = userId,
    cloudFileId = cloudFileId,
    localHash = localHash,
    cloudHash = cloudHash,
    status = status,
    lastSyncAttemptMs = lastSyncAttemptMs,
    lastSyncSuccessMs = lastSyncSuccessMs,
    backedUpAtMs = backedUpAtMs,
    sizeBytes = sizeBytes,
)
