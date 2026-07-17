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

package eu.akoos.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.domain.entity.SyncStatus

/** Lean projection: just the stored content hash per local uri, for callers that only pair by content
 *  (the duplicate finder). Blank-hash rows are excluded in SQL since an empty hash groups nothing, so
 *  the whole SyncState entity list never has to load just to read two columns. */
data class LocalHashRow(
    val localUri: String,
    val localHash: String,
)

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE userId = :userId")
    fun observeAll(userId: String): Flow<List<SyncStateEntity>>

    /** Lean stream of local content hashes for the duplicate finder: two columns, blank hashes
     *  filtered out in SQL, so a 50k library does not materialise the full SyncState list just to build
     *  a uri -> hash lookup. */
    @Query("SELECT localUri, localHash FROM sync_state WHERE userId = :userId AND localHash != ''")
    fun observeLocalHashes(userId: String): Flow<List<LocalHashRow>>

    /** Live count of the photos actually waiting to upload: LOCAL_ONLY rows carrying a queued intent.
     *  Backs the gallery's pending badge so it means the same set as the upload processor's selector
     *  and the Activity screen's pending list (all three are "LOCAL_ONLY AND queued"). Counting in
     *  SQL keeps it off the merge/sort hot path that builds the gallery list. */
    @Query("SELECT COUNT(*) FROM sync_state WHERE userId = :userId AND status = 'LOCAL_ONLY' AND queued = 1")
    fun countPendingUploads(userId: String): Flow<Int>

    /**
     * Partial upsert that NEVER touches the queue columns (queued/queueSource/queuedAt) on an
     * existing row. The domain [SyncState] deliberately does not carry those columns, so a
     * round-tripped entity always arrives with their INSERT defaults; a plain REPLACE would then
     * wipe a live upload intent off an existing row (a lost upload). Instead this inserts a brand-new
     * row verbatim, or - when the row already exists - updates ONLY the domain columns and leaves the
     * queue columns exactly as they are; the enqueue paths set those via [markQueued].
     *
     * Insert-then-update rather than an ON CONFLICT ... DO UPDATE upsert on purpose: the two steps run
     * inside one [Transaction] (so they are atomic), and the plain INSERT/UPDATE statements work on
     * every SQLite version the app and its tests run on. The IGNORE insert no-ops when the row exists;
     * the domain-column UPDATE no-ops for the just-inserted new row and only bites on the existing one.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: SyncStateEntity): Long

    @Query(
        """
        UPDATE sync_state SET
            userId = :userId,
            cloudFileId = :cloudFileId,
            localHash = :localHash,
            cloudHash = :cloudHash,
            status = :status,
            lastSyncAttemptMs = :lastSyncAttemptMs,
            lastSyncSuccessMs = :lastSyncSuccessMs,
            backedUpAtMs = :backedUpAtMs,
            sizeBytes = :sizeBytes
        WHERE localUri = :localUri
        """
    )
    suspend fun updateDomainColumns(
        localUri: String,
        userId: String,
        cloudFileId: String?,
        localHash: String,
        cloudHash: String?,
        status: SyncStatus,
        lastSyncAttemptMs: Long,
        lastSyncSuccessMs: Long?,
        backedUpAtMs: Long?,
        sizeBytes: Long,
    )

    @Transaction
    suspend fun upsert(entity: SyncStateEntity) {
        val inserted = insertIgnore(entity)
        if (inserted == -1L) {
            updateDomainColumns(
                localUri = entity.localUri,
                userId = entity.userId,
                cloudFileId = entity.cloudFileId,
                localHash = entity.localHash,
                cloudHash = entity.cloudHash,
                status = entity.status,
                lastSyncAttemptMs = entity.lastSyncAttemptMs,
                lastSyncSuccessMs = entity.lastSyncSuccessMs,
                backedUpAtMs = entity.backedUpAtMs,
                sizeBytes = entity.sizeBytes,
            )
        }
    }

    @Transaction
    suspend fun upsertAll(entities: List<SyncStateEntity>) {
        for (entity in entities) upsert(entity)
    }

    @Query("SELECT * FROM sync_state WHERE localUri = :localUri LIMIT 1")
    suspend fun getByUri(localUri: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE cloudFileId = :cloudFileId LIMIT 1")
    suspend fun getByCloudId(cloudFileId: String): SyncStateEntity?

    /**
     * Atomically claim a row for upload: flip it to [uploading] only while it is still [localOnly],
     * returning the number of rows changed. Two upload passes running in parallel (the one-shot and
     * the content-observer WorkManager names) can both select the same LOCAL_ONLY row; whichever
     * claim commits first wins (returns 1), the loser sees 0 and skips, so a photo uploads once. A
     * claimed row reads as UPLOADING, which both the upload selector and reconcile already skip.
     * status is stored as the enum's .name, so the args are the plain string forms.
     */
    @Query("UPDATE sync_state SET status = :uploading WHERE localUri = :localUri AND status = :localOnly")
    suspend fun claimForUpload(localUri: String, uploading: String, localOnly: String): Int

    /**
     * Recover claims stranded by a process death: reset every row still marked [uploading] with no
     * cloud copy back to [localOnly]. Nothing is genuinely in flight in a fresh process, so a row
     * left UPLOADING (which reconcile skips forever) would otherwise never retry. Run once per
     * process before the first upload selection so it cannot clobber a live claim made afterwards.
     */
    @Query("UPDATE sync_state SET status = :localOnly WHERE status = :uploading AND cloudFileId IS NULL")
    suspend fun resetStaleUploadingClaims(uploading: String, localOnly: String)

    /** A cloud photo we just trashed is gone for good, so demote its SYNCED row to LOCAL_ONLY and drop
     *  the dead cloudFileId right away (no 15-minute grace-window wait). Re-adding the on-device copy
     *  to an album then uploads it fresh instead of trying to re-link the trashed id and doing nothing. */
    @Query("UPDATE sync_state SET status = 'LOCAL_ONLY', cloudFileId = NULL WHERE cloudFileId IN (:cloudFileIds) AND status = 'SYNCED'")
    suspend fun demoteSyncedByCloudIds(cloudFileIds: List<String>)

    // Only rows this app actually uploaded carry a backedUpAtMs, so requiring it non-null keeps
    // Free-up-space from deleting a local file that was merely name/size-paired to a cloud photo
    // (such rows have a null backedUpAtMs). A hard floor against removing an un-backed-up original.
    // Account-scoped: a reclaim sweep only ever considers [userId]'s own rows, never those of
    // another signed-in account.
    @Query("SELECT * FROM sync_state WHERE userId = :userId AND status = 'SYNCED' AND backedUpAtMs IS NOT NULL AND backedUpAtMs < :timestampMs")
    suspend fun getSyncedBefore(userId: String, timestampMs: Long): List<SyncStateEntity>

    /** Record an explicit upload intent on a row: mark it queued, why ([source], a QueueSource
     *  constant), and when ([at], epoch millis). Does not touch status; a queued row can be
     *  LOCAL_ONLY (still to upload) or already claimed/synced; the queue flag is orthogonal. */
    @Query("UPDATE sync_state SET queued = 1, queueSource = :source, queuedAt = :at WHERE localUri = :localUri")
    suspend fun markQueued(localUri: String, source: String, at: Long)

    /**
     * Clear the queued flag ONLY while the row is still LOCAL_ONLY. The status guard is mandatory:
     * an UPLOADING row is a live claim and a SYNCED row is already backed up, so clearing queued on
     * either could drop an in-flight or just-finished upload's intent. The success/pairing path uses
     * the unguarded [clearQueuedForSynced] instead, exactly when the row is known to be done.
     */
    @Query("UPDATE sync_state SET queued = 0 WHERE localUri = :localUri AND status = 'LOCAL_ONLY'")
    suspend fun clearQueued(localUri: String)

    /** Unguarded intent clear for the success / pairing path, where the row is known to be backed up
     *  (or paired to an existing cloud copy) so its upload intent is genuinely satisfied. Clears the
     *  whole queue intent, source included: once a photo is backed up it has no pending intent, so if
     *  its cloud copy is later removed and the row demotes to LOCAL_ONLY it must not look like a
     *  stranded manual/album/editor upload and get re-queued. Do NOT use this to cancel a still-pending
     *  upload; that is [clearQueued]'s LOCAL_ONLY-guarded job. */
    @Query("UPDATE sync_state SET queued = 0, queueSource = NULL, queuedAt = NULL WHERE localUri = :localUri")
    suspend fun clearQueuedForSynced(localUri: String)

    /**
     * De-queue every not-yet-started MANUAL upload, returning the rows changed. Backs the user-cancel
     * path: a manual "back up now" the user cancelled must not resurface, so its queued intent is
     * cleared. ALBUM_ADD rows are left queued (an album-add must still upload to join its album),
     * AUTO_FOLDER rows are left queued (continuous folder backup resumes on the next pass, so cancel
     * is a pause for auto but a removal for manual), and EDITOR rows are left alone (the editor owns
     * that upload).
     *
     * The `status NOT IN ('UPLOADING','SYNCED')` guard is mandatory: an UPLOADING row is a live claim
     * (reconcile and a running batch execute concurrently, so de-queuing a claimed row would strand a
     * lost upload) and a SYNCED row is already backed up. Only a still-pending MANUAL row is touched.
     */
    @Query(
        "UPDATE sync_state SET queued = 0, queueSource = NULL " +
            "WHERE queueSource = 'MANUAL' AND status NOT IN ('UPLOADING','SYNCED')"
    )
    suspend fun clearManualQueue(): Int

    /** The reason a row was queued (a QueueSource constant), or null when it carries none. Read so a
     *  failure demotion can re-queue the row under its ORIGINAL source instead of guessing one. */
    @Query("SELECT queueSource FROM sync_state WHERE localUri = :localUri LIMIT 1")
    suspend fun getQueueSource(localUri: String): String?

    @Transaction
    @Query("UPDATE sync_state SET status = :newStatus WHERE localUri = :localUri")
    suspend fun updateStatus(localUri: String, newStatus: SyncStatus)

    @Query("DELETE FROM sync_state WHERE localUri = :localUri")
    suspend fun delete(localUri: String)

    @Query("DELETE FROM sync_state WHERE localUri IN (:localUris) AND status = 'LOCAL_ONLY'")
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)

    /** Wipe a user's sync-state on sign-out so a re-login starts from a clean local↔cloud pairing. */
    @Query("DELETE FROM sync_state WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}
