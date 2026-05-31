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

package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus

interface SyncStateRepository {
    fun observeAll(userId: UserId): Flow<List<SyncState>>
    suspend fun upsert(state: SyncState, userId: UserId)
    suspend fun upsertAll(states: List<SyncState>, userId: UserId)
    suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus)
    suspend fun getByUri(localUri: String): SyncState?
    suspend fun getByCloudId(cloudFileId: String): SyncState?
    suspend fun getSyncedBefore(timestampMs: Long): List<SyncState>
    /** Deletes LOCAL_ONLY entries whose URIs are no longer in-scope (excluded folders). */
    suspend fun deleteLocalOnlyByUris(localUris: List<String>)
}
