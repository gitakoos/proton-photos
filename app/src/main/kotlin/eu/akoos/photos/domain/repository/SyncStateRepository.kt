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

package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

interface SyncStateRepository {
    fun observeAll(userId: UserId): Flow<List<SyncState>>
    /** Live count of LOCAL_ONLY rows that also carry a queued upload intent, the photos genuinely
     *  waiting to back up. Matches the upload processor's selector so pending-count UIs agree. */
    fun countPendingUploads(userId: UserId): Flow<Int>
    suspend fun upsert(state: SyncState, userId: UserId)
    suspend fun upsertAll(states: List<SyncState>, userId: UserId)

    /** Guarded domain-column write for reconcile: rewrites [state]'s domain columns UNLESS the row is
     *  already a finished upload (SYNCED with a cloud id), returning the rows changed. Reconcile derives
     *  a demotion from a snapshot that can go stale before it writes, so this leaves a row an upload
     *  promoted in between untouched (0) while still refreshing a genuinely un-synced row (1). */
    suspend fun updateDomainColumnsIfNotSyncedWithCloud(state: SyncState, userId: UserId): Int

    /** Demote a SYNCED row to LOCAL_ONLY only while its cloudFileId still equals [expectedCloudId], the
     *  id reconcile saw in its snapshot; returns the rows changed. A twin genuinely gone still carries
     *  that id and is demoted (1); one an upload re-promoted carries a different id and is skipped (0). */
    suspend fun demoteToLocalIfCloudIdMatches(localUri: String, expectedCloudId: String): Int

    suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus)
    suspend fun getByUri(localUri: String): SyncState?
    suspend fun getByCloudId(cloudFileId: String): SyncState?

    /** Which of [localUris] already have a cloud copy under [userId], keyed to the cloud linkId each
     *  is paired to. Bounded by what is asked for, so a surface acting on one folder never reads the
     *  whole table to classify it; the keys alone answer "is this device file backed up", and the
     *  values are what a client-side hide filters by. */
    suspend fun cloudPairedLinkIds(userId: UserId, localUris: List<String>): Map<String, String>
    /** Atomically flip a row from LOCAL_ONLY to UPLOADING; returns the rows changed (1 = claimed,
     *  0 = another pass got it first or it is no longer LOCAL_ONLY). */
    suspend fun claimForUpload(localUri: String): Int
    /** Reset every UPLOADING row with no cloud copy back to LOCAL_ONLY. Process-death recovery. */
    suspend fun resetStaleUploadingClaims()
    /** [userId]'s free-up-space candidates: only that account's rows, never another account's. */
    suspend fun getSyncedBefore(userId: UserId, timestampMs: Long): List<SyncState>

    /** Every row of [userId]'s the hidden vault claims, the ones at [SyncStatus.HIDDEN]. A vault
     *  holds a handful of photos beside a library of thousands, and the status is indexed, so the
     *  read costs that handful. Account-scoped like every other selector here. */
    suspend fun getVaulted(userId: UserId): List<SyncState>

    /** The cloud ids [userId] has a live device-file row for (SYNCED, or LOCAL_ONLY still carrying a
     *  pairing). A vaulted row whose cloud id is among these names a photo already back on the device
     *  under another row, so its own HIDDEN marker is a leftover the sweep drops. */
    suspend fun cloudIdsWithLivePairing(userId: UserId): Set<String>

    /** Drop every HIDDEN row for [cloudFileId], so a reveal that re-paired the photo leaves no stale
     *  marker on another row for the same cloud copy dropping it from every listing. */
    suspend fun clearHiddenForCloudId(cloudFileId: String)
    /** Deletes LOCAL_ONLY entries whose URIs are no longer in-scope (excluded folders). */
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)

    /** Drop the row keyed by [localUri] outright. For a uri that names nothing on the device any
     *  more and never will again, where leaving the row behind would put a second row on the cloud
     *  copy it is paired to. */
    suspend fun delete(localUri: String)

    /** Record an explicit upload intent on a row: mark it queued, why ([source], a
     *  [eu.akoos.photos.domain.entity.QueueSource] constant), and when ([at], epoch millis). */
    suspend fun markQueued(localUri: String, source: String, at: Long)
    /** Clear the queued flag only while the row is still LOCAL_ONLY (never on an UPLOADING /
     *  SYNCED row). */
    suspend fun clearQueued(localUri: String)
    /** Unguarded queued clear for the success / pairing path, where the row is known backed up. */
    suspend fun clearQueuedForSynced(localUri: String)
    /** The reason a row was queued (a [eu.akoos.photos.domain.entity.QueueSource] constant), or null.
     *  Read on a failure demotion so the re-queue preserves the row's original source. */
    suspend fun getQueueSource(localUri: String): String?

    /** De-queue every not-yet-started MANUAL upload (a cancelled "back up now" must not resurface),
     *  leaving ALBUM_ADD / AUTO_FOLDER / EDITOR rows queued and never touching an UPLOADING or SYNCED
     *  row. Returns the rows changed. Called from the user-cancel path only. */
    suspend fun clearManualQueue(): Int
}
