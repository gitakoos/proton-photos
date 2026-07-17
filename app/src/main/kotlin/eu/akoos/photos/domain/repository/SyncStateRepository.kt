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
    suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus)
    suspend fun getByUri(localUri: String): SyncState?
    suspend fun getByCloudId(cloudFileId: String): SyncState?
    /** Atomically flip a row from LOCAL_ONLY to UPLOADING; returns the rows changed (1 = claimed,
     *  0 = another pass got it first or it is no longer LOCAL_ONLY). */
    suspend fun claimForUpload(localUri: String): Int
    /** Reset every UPLOADING row with no cloud copy back to LOCAL_ONLY. Process-death recovery. */
    suspend fun resetStaleUploadingClaims()
    /** [userId]'s free-up-space candidates: only that account's rows, never another account's. */
    suspend fun getSyncedBefore(userId: UserId, timestampMs: Long): List<SyncState>
    /** Deletes LOCAL_ONLY entries whose URIs are no longer in-scope (excluded folders). */
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)

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
