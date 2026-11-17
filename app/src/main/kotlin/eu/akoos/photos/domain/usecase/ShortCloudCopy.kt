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
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository

/**
 * The release time of 2.3.9, the last version whose photo editor measured the copy it uploads after
 * writing its EXIF. Versions after it recorded the size first, so the upload of an edited copy of a backed-up
 * photo left out the EXIF block at the end. Edited copies backed up since then are uploaded once more.
 */
internal const val EDITOR_SHORT_COPIES_SINCE_MS = 1_782_929_030_000L

/** The name the photo editor gives a copy of a backed-up photo: `<name>_edit_<yyyyMMdd_HHmmss>.jpg`. */
private val EDITOR_COPY_NAME = Regex("""_edit_\d{8}_\d{6}\.jpe?g$""", RegexOption.IGNORE_CASE)

/**
 * Whether a Drive copy holding [driveBytes] is too small to be a whole copy of a [deviceBytes] file. Drive's
 * link size is the encrypted size of what it stores, and encrypting a block only adds bytes, so a whole copy
 * is never smaller than the file. A size of 0 or less, or none, proves nothing. Pure.
 */
internal fun driveCopyShortOf(driveBytes: Long?, deviceBytes: Long): Boolean =
    driveBytes != null && driveBytes > 0L && deviceBytes > 0L && driveBytes < deviceBytes

/**
 * Whether [row]'s recorded hash is the hash of its device file's bytes, so a Drive copy carrying that hash
 * claims to hold the device file whole: the row recorded the device bytes as the very bytes uploaded (its
 * original hash is its hash). A row with no device bytes recorded apart proves nothing, since its upload may
 * have been stripped or compressed under a setting changed since, and a smaller Drive copy is then expected.
 * Pure.
 */
internal fun hashStandsForDeviceBytes(row: SyncState): Boolean {
    if (row.localHash.isEmpty()) return false
    val original = row.originalHash
    return !original.isNullOrEmpty() && original.equals(row.localHash, ignoreCase = true)
}

/**
 * Whether [row], the backup row of a device file named [displayName] of type [mimeType], is a copy the photo
 * editor saved of a backed-up photo and uploaded on a version that cut it short: a backed-up JPEG with the
 * editor's copy name, backed up by this app since [EDITOR_SHORT_COPIES_SINCE_MS] and before [checkStartedMs].
 * A copy the regular backup uploaded under such a name is included as well, since nothing on the row tells the
 * two apart; it is uploaded once more, which costs a second Drive copy and loses nothing. Pure.
 */
internal fun maybeShortEditorCopy(row: SyncState, displayName: String, mimeType: String, checkStartedMs: Long): Boolean {
    val backedUpAtMs = row.backedUpAtMs ?: return false
    return row.status == SyncStatus.SYNCED &&
        !row.cloudFileId.isNullOrEmpty() &&
        row.localHash.isNotEmpty() &&
        backedUpAtMs >= EDITOR_SHORT_COPIES_SINCE_MS &&
        backedUpAtMs < checkStartedMs &&
        mimeType.equals("image/jpeg", ignoreCase = true) &&
        EDITOR_COPY_NAME.containsMatchIn(displayName)
}

/**
 * Takes [row]'s backup stamp and its pairing to a short Drive copy off and queues its device file to be backed
 * up again whole. The device file is never touched, and the short copy stays on Drive. The row keeps its hash,
 * which still names the device bytes; the short copy is kept out of every match by its link id.
 */
internal suspend fun SyncStateRepository.requeueAfterShortCopy(userId: me.proton.core.domain.entity.UserId, row: SyncState) {
    upsert(
        row.copy(
            status = SyncStatus.LOCAL_ONLY,
            cloudFileId = null,
            cloudHash = null,
            backedUpAtMs = null,
            lastSyncSuccessMs = null,
        ),
        userId,
    )
    markQueued(row.localUri, QueueSource.MANUAL, System.currentTimeMillis())
}
