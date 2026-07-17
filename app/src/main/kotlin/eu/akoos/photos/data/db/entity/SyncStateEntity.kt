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
    // Explicit upload-intent, set/cleared only through the SyncStateDao queue methods. queued/
    // queueSource are surfaced onto the domain SyncState (read-only) so the upload processor can
    // select on "LOCAL_ONLY AND queued"; queuedAt stays entity-only. Writing a SyncState back is
    // still safe: the partial upsert's updateDomainColumns OMITS every queue column, so a round-trip
    // never overwrites a live row's intent (a brand-new row inserts with these defaults then gets
    // markQueued). queued = the row is meant to be backed up; queueSource = why (a QueueSource
    // constant); queuedAt = when.
    val queued: Boolean = false,
    val queueSource: String? = null,
    val queuedAt: Long? = null,
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
        queued = queued,
        queueSource = queueSource,
    )
}

// Maps the read-only queued/queueSource through as well, but note the DAO upsert's
// updateDomainColumns never writes them, so an existing row's queue state is untouched on a
// round-trip; only insertIgnore's brand-new row uses the value (default false/null), which the
// enqueue paths then set via markQueued.
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
    queued = queued,
    queueSource = queueSource,
)
