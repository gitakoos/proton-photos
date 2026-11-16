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

package eu.akoos.photos.data.upload

import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A rewrite this app made of a user's device file on its own account (a metadata write or strip, not a
 * pixel edit): the file at [uri] held [before] (SHA-1) with [beforeStamp] (its size and modified time,
 * null when they could not be read) and now, rewritten and complete, holds [after].
 */
data class OwnDeviceWrite(
    val uri: String,
    val before: String,
    val beforeStamp: DeviceFileStamp?,
    val after: String,
)

/**
 * Where an [OwnDeviceWrite] is announced, so the file's backup row can take the new bytes as backed up
 * when the old ones were ([OwnDeviceWriteRecorder]). A rewrite that lands while nothing listens, as in a
 * test, records nothing, and the file is then judged by its modified time like any other change.
 */
object OwnDeviceWrites {
    @Volatile
    private var sink: ((OwnDeviceWrite) -> Unit)? = null

    /** Whether anything listens, so a writer hashes the file around its write only when it counts. */
    val listening: Boolean get() = sink != null

    fun install(onWrite: (OwnDeviceWrite) -> Unit) {
        sink = onWrite
    }

    fun wrote(write: OwnDeviceWrite) {
        sink?.invoke(write)
    }

    internal fun clear() {
        sink = null
    }
}

/**
 * Records what each [OwnDeviceWrites] announcement left on the device on the file's backup row, only when
 * the bytes it rewrote were the backed-up ones. Free-up then deletes the new bytes as backed up, and
 * reconcile keeps the backup stamp, where the file's later modified time would otherwise read as an edit
 * and put a second copy on Drive. A file that held anything else before the write (an edit made
 * elsewhere, a pixel edit) is left to read as changed.
 *
 * The old bytes were the backed-up ones when the row recorded them (its hash, or bytes recorded before).
 * A row backed up before such a record existed, with the original kept beside a stripped upload, recorded
 * only the uploaded bytes; for it the old file counts as backed up exactly when free-up would have
 * deleted it as such ([FreeUpSpaceUseCase.deviceCopyVerdict] on the measures taken before the write), and
 * the record is taken only if the row is still as it was read.
 */
@Singleton
class OwnDeviceWriteRecorder @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
) {
    suspend fun record(write: OwnDeviceWrite) {
        try {
            val row = syncStateRepo.getByUri(write.uri) ?: return
            val recorded = write.before.equals(row.localHash, ignoreCase = true) ||
                write.before.equals(row.originalHash, ignoreCase = true)
            if (recorded) {
                syncStateRepo.recordOwnDeviceWrite(write.uri, write.before, write.after)
                return
            }
            val stamp = write.beforeStamp ?: return
            val backedUpAtMs = row.backedUpAtMs ?: return
            if (!row.originalHash.isNullOrEmpty() || row.status != SyncStatus.SYNCED || row.cloudFileId == null) return
            val paired = row.cloudHash
            val onDrive = !paired.isNullOrEmpty() && cloudRepo.cloudContentHash(write.before) == paired
            val verdict = FreeUpSpaceUseCase.deviceCopyVerdict(
                row, stamp.sizeBytes, stamp.modifiedSec, write.before, onDrive, paired,
            )
            if (verdict == FreeUpSpaceUseCase.DeviceCopyCheck.UNCHANGED) {
                syncStateRepo.recordOwnDeviceWriteOverRow(write.uri, write.after, row.localHash, backedUpAtMs)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing recorded: the file is judged by its modified time, like a change made elsewhere.
        }
    }
}
