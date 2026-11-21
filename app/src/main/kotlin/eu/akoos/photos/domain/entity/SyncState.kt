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

package eu.akoos.photos.domain.entity

data class SyncState(
    val localUri: String,
    val cloudFileId: String?,
    val localHash: String,
    val cloudHash: String?,
    val status: SyncStatus,
    val lastSyncAttemptMs: Long,
    val lastSyncSuccessMs: Long?,
    val backedUpAtMs: Long?,
    val sizeBytes: Long,
    /**
     * Read-only view of the row's explicit upload intent, surfaced so the upload processor can
     * select on "LOCAL_ONLY AND queued" instead of every LOCAL_ONLY row. Populated by [toDomain]
     * on read; the queue columns are still owned by the SyncStateDao queue methods, NOT by the
     * upsert round-trip. The DAO's partial upsert (updateDomainColumns) deliberately OMITS these,
     * so writing a SyncState back never disturbs an existing row's queue state; a brand-new row
     * inserts with the default false/null and is then stamped by markQueued.
     *
     * queued = the row is meant to be backed up; queueSource = why (a [QueueSource] constant);
     * queuedAt = when it was last queued, which reconcile reads to leave a row queued mid-pass alone.
     */
    val queued: Boolean = false,
    val queueSource: String? = null,
    val queuedAt: Long? = null,
    /**
     * Read-only: the SHA-1 of what the device file holds as backed up: as the last backup read it,
     * before any strip or compression, or as this app wrote it itself since (a mirror rewrite at that
     * backup, a download's saved copy, a metadata write). Null for a row backed up before it was
     * recorded, and one whose hash or Drive pairing changed since (the DAO clears it then). Written only
     * by [eu.akoos.photos.domain.repository.SyncStateRepository.setOriginalHash] and
     * [eu.akoos.photos.domain.repository.SyncStateRepository.recordOwnDeviceWrite].
     */
    val originalHash: String? = null,
    /**
     * What [cloudFileId] rests on, a [LinkBasis] value: proof by content or by this app's own upload or
     * download, or a name rule of reconcile's alone. Every writer states it; a row reconcile paired by
     * name keeps its mark on later passes until a content hash proves the link.
     */
    val linkBasis: Int = LinkBasis.PROVEN,
)

/** What a sync row's link to its Drive photo rests on, see [SyncState.linkBasis]. */
object LinkBasis {
    /** The device bytes hash to the Drive copy, or this app uploaded or downloaded the photo itself. */
    const val PROVEN = 0

    /** Reconcile matched the name with the capture second or the size, nothing of the bytes. */
    const val NAME = 1

    /** Reconcile matched the name and capture second of a photo whose upload rewrites its bytes (strip or
     *  compress on), so no hash of the device file can match the Drive copy. */
    const val NAME_REWRITTEN = 2
}
