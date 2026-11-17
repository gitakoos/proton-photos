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
import eu.akoos.photos.domain.repository.ShortCloudCopyStore
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
 * Whether [row], the backup row of a device file named [displayName] of type [mimeType], may be a copy the
 * photo editor saved of a backed-up photo and uploaded on a version that could cut it short: a backed-up JPEG
 * with the editor's copy name, backed up since [EDITOR_SHORT_COPIES_SINCE_MS] and before [checkStartedMs].
 * Only a suspect: [editorCopyVerdict] tells whether its Drive copy is short. Pure.
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

/** What the declared size of a suspect editor copy's Drive copy tells ([editorCopyVerdict]). */
internal enum class EditorCopyVerdict {
    /** Drive's copy declares fewer bytes than the file its hash names: back it up again whole. */
    SHORT,

    /** Drive's copy declares the whole file: left as it is. */
    WHOLE,

    /** The device file is not the bytes uploaded, so the whole length is not known here: kept on the device
     *  for good, never uploaded again. */
    UNVERIFIABLE,

    /** The declared size or the device file could not be read now: kept, looked at again later. */
    UNRESOLVED,
}

/**
 * Tells a suspect editor copy ([maybeShortEditorCopy]) short or whole. An upload reads exactly the size it
 * declares ([claimedBytes], the XAttr's Common.Size), so the copy is short when that is below the length of
 * the bytes its hash names. The device file is those bytes when its SHA-1 ([deviceSha1]) is the row's hash,
 * and [deviceBytes] is then their length; a device file holding other bytes (an upload strip took fields
 * out) leaves that length unknown. Pure.
 */
internal fun editorCopyVerdict(row: SyncState, claimedBytes: Long?, deviceSha1: String?, deviceBytes: Long): EditorCopyVerdict = when {
    claimedBytes == null || deviceSha1 == null || deviceBytes <= 0L -> EditorCopyVerdict.UNRESOLVED
    !deviceSha1.equals(row.localHash, ignoreCase = true) -> EditorCopyVerdict.UNVERIFIABLE
    claimedBytes < deviceBytes -> EditorCopyVerdict.SHORT
    else -> EditorCopyVerdict.WHOLE
}

/**
 * Records [row]'s Drive copy as short, and as the copy its device file's whole backup replaces, then queues
 * that file to be backed up again ([requeueAfterShortCopy]).
 */
internal suspend fun ShortCloudCopyStore.recordShortAndRequeue(
    repo: SyncStateRepository,
    userId: me.proton.core.domain.entity.UserId,
    row: SyncState,
) {
    val linkId = row.cloudFileId ?: return
    add(listOf(linkId))
    addReplacement(row.localUri, linkId)
    repo.requeueAfterShortCopy(userId, row)
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
