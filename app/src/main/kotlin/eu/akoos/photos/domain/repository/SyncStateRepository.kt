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
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

interface SyncStateRepository {
    /** Every row [userId] holds with all its columns, read again in full on each write to the table.
     *  Legacy: a reader that needs only the pairing, the status or the queue flag takes
     *  [eu.akoos.photos.data.repository.SyncStateSnapshot], one lean read the whole app shares. */
    fun observeAll(userId: UserId): Flow<List<SyncState>>
    /** Live count of LOCAL_ONLY rows that also carry a queued upload intent, the photos genuinely
     *  waiting to back up. Matches the upload processor's selector so pending-count UIs agree. */
    fun countPendingUploads(userId: UserId): Flow<Int>
    suspend fun upsert(state: SyncState, userId: UserId)
    suspend fun upsertAll(states: List<SyncState>, userId: UserId)

    /** Device uris of [userId]'s SYNCED rows, by the cloud id of [cloudFileIds] each is paired to. */
    suspend fun syncedUrisByCloudId(userId: UserId, cloudFileIds: Collection<String>): Map<String, List<String>> =
        observeAll(userId).first()
            .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null && it.cloudFileId in cloudFileIds }
            .groupBy({ it.cloudFileId!! }, { it.localUri })

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

    /** [updateStatusAndDeleteLocal] for many rows, a statement per few hundred instead of one each. */
    suspend fun updateStatuses(localUris: Collection<String>, newStatus: SyncStatus)
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

    /** [markQueued] for every one of [localUris], in as few writes as the SQL limit allows. */
    suspend fun markQueuedAll(localUris: List<String>, source: String, at: Long) {
        for (uri in localUris) markQueued(uri, source, at)
    }

    /** [updateDomainColumnsIfNotSyncedWithCloud] for each of [states], in as few transactions as the SQL
     *  limit allows; returns the uris left untouched because the row is a finished upload by now. */
    suspend fun updateDomainColumnsIfNotSyncedWithCloudAll(states: List<SyncState>, userId: UserId): Set<String> =
        states.filterTo(ArrayList()) { updateDomainColumnsIfNotSyncedWithCloud(it, userId) == 0 }
            .mapTo(HashSet()) { it.localUri }

    /**
     * Up to [limit] of [userId]'s rows at [status] that come after [afterKey] in the order [observeAll]
     * lists them, with the key of the last one. A walk starts at [Long.MIN_VALUE] and passes each page's
     * [SyncStatePage.lastKey] on, so a pass over every row holds one page at a time, not the whole table.
     */
    suspend fun pageByStatus(userId: UserId, status: SyncStatus, afterKey: Long, limit: Int): SyncStatePage {
        val rows = observeAll(userId).first().filter { it.status == status }
        val from = afterKey.coerceIn(0L, rows.size.toLong())
        val page = rows.drop(from.toInt()).take(limit)
        return SyncStatePage(page, from + page.size)
    }

    /** [userId]'s own rows for [localUris]: a uri with no row, or with another account's row, is absent.
     *  The caller keeps the list within the host-variable limit. */
    suspend fun getOwnByUris(userId: UserId, localUris: List<String>): List<SyncState> {
        val wanted = localUris.toHashSet()
        return observeAll(userId).first().filter { it.localUri in wanted }
    }

    /** Which of [cloudFileIds] a SYNCED row of [userId]'s is paired to. The caller keeps the list within
     *  the host-variable limit. */
    suspend fun syncedCloudIdsAmong(userId: UserId, cloudFileIds: Collection<String>): Set<String> =
        observeAll(userId).first().asSequence()
            .filter { it.status == SyncStatus.SYNCED }
            .mapNotNull { it.cloudFileId }
            .filterTo(HashSet()) { it in cloudFileIds }

    /** [userId]'s rows that are queued or whose uri is in [extraUris], in the order [observeAll] lists them:
     *  the only rows an upload pass selects from, without reading every row of a large library. */
    suspend fun getQueuedOrIn(userId: UserId, extraUris: Set<String>): List<SyncState> =
        observeAll(userId).first().filter { it.queued || it.localUri in extraUris }

    /** The rows for [localUris] (a uri with no row is absent), in as few reads as the SQL limit allows. */
    suspend fun getByUris(localUris: List<String>): List<SyncState> = localUris.mapNotNull { getByUri(it) }

    /** [upsert] and [markQueued] for every one of [states], in as few writes as the SQL limit allows. */
    suspend fun seedAndMarkQueued(states: List<SyncState>, userId: UserId, source: String, at: Long) {
        for (state in states) {
            upsert(state, userId)
            markQueued(state.localUri, source, at)
        }
    }
    /** Clear the queued flag only while the row is still LOCAL_ONLY (never on an UPLOADING /
     *  SYNCED row). */
    suspend fun clearQueued(localUri: String)
    /** Unguarded queued clear for the success / pairing path, where the row is known backed up. */
    suspend fun clearQueuedForSynced(localUri: String)

    /** Records the device file's SHA-1 as the backup that just landed read it ([SyncState.originalHash]),
     *  or clears it when null. Called right after that backup's SYNCED write. */
    suspend fun setOriginalHash(localUri: String, originalHash: String?) {}

    /**
     * Records [after] as what [localUri]'s device file holds right after this app rewrote it itself (the
     * date or place the metadata editor writes into it, a metadata strip), so free-up and reconcile take
     * those bytes as the backed-up photo rather than as an edit to back up again. Only a row that is backed
     * up, paired to Drive and recorded as holding [before] (the bytes the rewrite started from) takes it;
     * any other row is left as it is, so a rewrite of a file edited since its backup still reads as changed.
     */
    suspend fun recordOwnDeviceWrite(localUri: String, before: String, after: String) {}

    /**
     * Records [after] as what [localUri]'s device file holds right after this app rewrote it itself, on a
     * backed-up row that recorded no device bytes, only while its hash is still [localHash] and its backup
     * stamp still [backedUpAtMs]: the caller has judged the bytes the rewrite started from backed up by
     * that row as it read it.
     */
    suspend fun recordOwnDeviceWriteOverRow(localUri: String, after: String, localHash: String, backedUpAtMs: Long) {}

    /** Drops the upload a device photo was given only to join an album, once no album is left for it to join.
     *  Only a still pending LOCAL_ONLY row queued for an album add. */
    suspend fun clearAlbumAddIntent(localUri: String) {}

    /** The device photo's Drive copy is gone: keep the folder backup from uploading it again. */
    suspend fun markRemovedFromDrive(localUri: String)
    /** [clearQueuedForSynced] for every one of [localUris], in as few writes as the SQL limit allows. */
    suspend fun clearQueuedForSyncedAll(localUris: List<String>) {
        for (uri in localUris) clearQueuedForSynced(uri)
    }
    /** The reason a row was queued (a [eu.akoos.photos.domain.entity.QueueSource] constant), or null.
     *  Read on a failure demotion so the re-queue preserves the row's original source. */
    suspend fun getQueueSource(localUri: String): String?

    /** De-queue every not-yet-started MANUAL upload (a cancelled "back up now" must not resurface),
     *  leaving ALBUM_ADD / AUTO_FOLDER / EDITOR rows queued and never touching an UPLOADING or SYNCED
     *  row. Returns the rows changed. Called from the user-cancel path only. */
    suspend fun clearManualQueue(): Int

    /** A single cancelled "back up now" upload of [localUri]: de-queued and marked `QueueSource.CANCELLED`,
     *  claimed or not, so no claim release, restart recovery or reconcile queues it again until the user
     *  asks. Never touches a SYNCED row. Returns the rows changed. */
    suspend fun clearManualQueueFor(localUri: String): Int = 0

    /** Puts the `QueueSource.CANCELLED` mark back on [localUri] after a write that cleared the queue columns. */
    suspend fun keepCancelled(localUri: String) {}
}

/** One page of a [SyncStateRepository.pageByStatus] walk: its rows, and the key the next page starts after. */
class SyncStatePage(val rows: List<SyncState>, val lastKey: Long)
