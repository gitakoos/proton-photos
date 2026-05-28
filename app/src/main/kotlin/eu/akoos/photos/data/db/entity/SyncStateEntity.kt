package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

@Entity(tableName = "sync_state")
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
