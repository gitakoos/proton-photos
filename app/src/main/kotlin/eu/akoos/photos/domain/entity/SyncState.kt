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
     * queued = the row is meant to be backed up; queueSource = why (a [QueueSource] constant).
     */
    val queued: Boolean = false,
    val queueSource: String? = null,
)
