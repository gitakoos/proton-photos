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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import me.proton.core.domain.entity.UserId

/**
 * Takes [row]'s backup stamp and Drive pairing off and queues its device file to be backed up again,
 * for a device file that no longer holds what its backup read. [sha1] is the file's hash as it is now.
 * The Drive copy stays as it is.
 */
internal suspend fun SyncStateRepository.requeueChangedDeviceCopy(
    userId: UserId,
    row: SyncState,
    sha1: String,
    sizeBytes: Long,
) {
    upsert(
        row.copy(
            status = SyncStatus.LOCAL_ONLY,
            cloudFileId = null,
            cloudHash = null,
            localHash = sha1,
            backedUpAtMs = null,
            lastSyncSuccessMs = null,
            sizeBytes = sizeBytes.takeIf { it > 0L } ?: row.sizeBytes,
            linkBasis = LinkBasis.PROVEN,
        ),
        userId,
    )
    markQueued(row.localUri, QueueSource.MANUAL, System.currentTimeMillis())
}
