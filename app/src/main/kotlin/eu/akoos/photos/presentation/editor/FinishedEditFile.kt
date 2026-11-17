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

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.repository.ShortCloudCopyStore
import eu.akoos.photos.domain.repository.SyncStateRepository
import me.proton.core.domain.entity.UserId
import java.io.File

/** The size and SHA-1 an edit's upload carries, both read from the finished file. */
internal class FinishedEditFile(val sizeBytes: Long, val sha1: String)

/**
 * Runs [writes] on [file] (the EXIF put back, the upload strip), then measures and hashes it. The upload
 * sends exactly the length recorded here, so a size taken before a write would cut the written bytes off.
 * The hash must stay SHA-1 (not SHA-256): Drive pins the digest algorithm, and Drive web rejects a
 * differently derived ContentHash ("Cannot build photo payload...").
 */
internal inline fun finishEditFile(file: File, writes: () -> Unit): FinishedEditFile {
    writes()
    return FinishedEditFile(
        sizeBytes = file.length(),
        sha1 = eu.akoos.photos.data.localcrypto.PlainContent.sha1Hex(file),
    )
}

/**
 * Whether the copy uploaded for a synced edit holds the bytes of its device copy. The upload is a second
 * encoding of the edit, so only the bytes show it: the device file's SHA-1 [deviceSha1] is the upload's
 * [uploadSha1], or the device bytes put through the upload strip ([strippedDeviceSha1], read only when the
 * first does not match) are. A device file that could not be read proves nothing.
 */
internal inline fun editCopyOnDrive(deviceSha1: String?, uploadSha1: String, strippedDeviceSha1: () -> String?): Boolean {
    if (deviceSha1 == null) return false
    if (deviceSha1.equals(uploadSha1, ignoreCase = true)) return true
    return strippedDeviceSha1()?.equals(uploadSha1, ignoreCase = true) == true
}

/**
 * Writes [backedUp], the SYNCED row of a synced edit's device copy, once its upload is in. A device copy whose
 * bytes are proven on Drive ([provenDeviceSha1]) records them as what was backed up. Any other has its Drive
 * copy recorded as one that cannot vouch for the device file ([ShortCloudCopyStore.addUnverifiable]) before
 * the row is written, so free-up keeps that file for good.
 */
internal suspend fun recordSyncedEditCopy(
    repo: SyncStateRepository,
    shortCopies: ShortCloudCopyStore,
    userId: UserId,
    provenDeviceSha1: String?,
    backedUp: SyncState,
) {
    val linkId = backedUp.cloudFileId
    if (provenDeviceSha1 == null && linkId != null) {
        try {
            shortCopies.addUnverifiable(listOf(linkId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Free-up still keeps an edited copy whose bytes are not on Drive by its name.
        }
    }
    repo.upsert(backedUp, userId)
    // After the upsert, which clears the recorded device bytes of a row whose hash or link changed.
    if (provenDeviceSha1 != null) repo.setOriginalHash(backedUp.localUri, provenDeviceSha1)
}
