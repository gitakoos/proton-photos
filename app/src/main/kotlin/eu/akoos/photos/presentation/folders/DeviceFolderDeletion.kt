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

package eu.akoos.photos.presentation.folders

import android.os.Build
import androidx.annotation.StringRes
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.presentation.common.DeleteRow
import eu.akoos.photos.presentation.common.DeleteRowKind
import eu.akoos.photos.presentation.common.deleteConfirmRows
import eu.akoos.photos.presentation.common.deleteRowDescRes
import eu.akoos.photos.presentation.common.deleteRowTitleRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * What deleting a whole device folder adds to the delete every surface already runs: which photos the
 * folder holds, and how its confirmation words the delete.
 *
 * A folder delete takes the device files only. Drive copies are never touched: a backed-up photo stays
 * in Drive and becomes cloud-only. The delete itself is
 * [eu.akoos.photos.domain.usecase.DeletePhotoUseCase] behind the owning screen's system-consent
 * launcher, the path a selection delete takes, so nothing here removes a file.
 */
object DeviceFolderDeletion {

    /** The one row the confirmation offers: the delete it asks for, and the words it wears. A null
     *  [descRes] leaves the row with its title alone. */
    data class Choice(
        val row: DeleteRow,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int?,
    )

    /**
     * Every device row of the folder as the item the delete runs on: [GalleryItem.Synced] where
     * [pairedLinkIds] records a Drive copy for the row's uri, [GalleryItem.LocalOnly] otherwise.
     *
     * The type is what moves each photo's sync record afterwards: a backed-up photo becomes cloud-only,
     * a device-only one leaves no copy behind. So it follows the sync record's own pairing, never a
     * match by name. [listed] supplies the Drive copy as the cloud listing holds it; one the listing
     * has not caught up with yet stands in from its link id and the device row, the only parts of it
     * the delete reads.
     */
    fun items(
        rows: List<LocalMediaItem>,
        pairedLinkIds: Map<String, String>,
        listed: Map<String, CloudPhoto>,
    ): List<GalleryItem> =
        rows.map { row ->
            val linkId = pairedLinkIds[row.uri] ?: return@map GalleryItem.LocalOnly(row)
            GalleryItem.Synced(listed[linkId] ?: standIn(linkId, row), row)
        }

    private fun standIn(linkId: String, row: LocalMediaItem) = CloudPhoto(
        linkId = linkId,
        shareId = "",
        volumeId = "",
        captureTime = row.dateTaken / 1000L,
        displayName = row.displayName,
        mimeType = row.mimeType,
        sizeBytes = row.sizeBytes,
        thumbnailUrl = null,
        revisionId = "",
    )

    /** Whether a device delete on [sdkInt] lands in the system trash, where it can still be restored,
     *  rather than removing the files for good as Android 10 and older do. */
    fun hasDeviceTrash(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    /**
     * The row the confirmation offers for deleting [items], or null for an empty folder.
     *
     * With an account and a Drive copy behind every photo, it is the selection sheet's neutral
     * "remove from device" row, the Drive backup kept. Otherwise at least one photo has no other copy,
     * so it is the red device delete, which is always the case without an account.
     *
     * The words follow [sdkInt]. Where the device has no trash, the red row reads as permanent.
     */
    fun choice(items: Collection<GalleryItem>, isSignedIn: Boolean, sdkInt: Int): Choice? {
        if (items.isEmpty()) return null
        val trash = hasDeviceTrash(sdkInt)
        val row = deleteConfirmRows(
            hasLocal = true,
            hasCloud = isSignedIn && items.all { it is GalleryItem.Synced },
            reclaimableBytes = items.sumOf { deviceBytes(it) },
        ).first { it.freeUpSpace && !it.deleteFromCloud }
        return Choice(row, titleRes(row.kind, trash), descRes(row.kind, trash))
    }

    private fun deviceBytes(item: GalleryItem): Long = when (item) {
        is GalleryItem.LocalOnly -> item.local.sizeBytes
        is GalleryItem.Synced -> item.local.sizeBytes
        is GalleryItem.CloudOnly -> 0L
    }

    @StringRes
    private fun titleRes(kind: DeleteRowKind, trash: Boolean): Int =
        if (!trash && kind == DeleteRowKind.TrashLocal) R.string.delete_button_permanently else deleteRowTitleRes(kind)

    @StringRes
    private fun descRes(kind: DeleteRowKind, trash: Boolean): Int? =
        if (!trash && kind == DeleteRowKind.TrashLocal) null else deleteRowDescRes(kind)
}

/**
 * A folder delete put to the user and not answered yet. [items] is null while the folder is still
 * being read, which takes a device query, so the sheet can go up the instant the row is tapped and fill
 * its count in when it lands.
 */
data class DeviceFolderDeleteRequest(
    val bucketName: String,
    val items: List<GalleryItem>? = null,
)

/**
 * The question a folder delete starts with, shared by the Albums grid's host and the folder's own
 * screen so both read the same photos and put up the same sheet. Mirrors
 * [eu.akoos.photos.presentation.common.MoveToFolderController]: not a singleton, each owning ViewModel
 * gets its own and passes its scope.
 *
 * Nothing here deletes. [take] hands the photos to the owner, which runs them through the delete its
 * selection already uses, so the system trash dialog, the busy sheet and the failure report are that
 * path's own.
 */
class DeviceFolderDeleteController @Inject constructor(
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val syncStateRepo: SyncStateRepository,
    private val driveRepo: DrivePhotoRepository,
) {

    private val _request = MutableStateFlow<DeviceFolderDeleteRequest?>(null)

    /** The folder delete awaiting an answer, or null when none is. */
    val request: StateFlow<DeviceFolderDeleteRequest?> = _request.asStateFlow()

    /**
     * Ask about deleting [bucketName], and start reading what that would take.
     *
     * The answer is filed against the folder it was asked for, so a second folder asked about while the
     * first is still being read cannot have the first one's photos land under its name. A folder found
     * empty, or one that could not be read, leaves nothing to agree to, so the question comes down.
     */
    fun ask(scope: CoroutineScope, bucketName: String) {
        if (bucketName.isEmpty()) return
        _request.value = DeviceFolderDeleteRequest(bucketName)
        scope.launch {
            val items = try {
                itemsOf(bucketName)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            _request.update { current ->
                when {
                    current == null || current.bucketName != bucketName -> current
                    items.isEmpty() -> null
                    else -> current.copy(items = items)
                }
            }
        }
    }

    /** Drop the question. Nothing has been touched. */
    fun dismiss() {
        _request.value = null
    }

    /** The photos the user just agreed to delete, taking the question down. Null while they are still
     *  being read, which keeps an answer from landing on a count that was never on screen. */
    fun take(): List<GalleryItem>? {
        val items = _request.value?.items ?: return null
        _request.value = null
        return items
    }

    /**
     * Everything [bucketName] holds on the device, read for the one bucket the way its card on the
     * Albums grid counts it, so the delete leaves nothing behind for that card to go on showing.
     *
     * Which of them has a Drive copy comes from their sync records alone, the same uris-bounded read a
     * folder hide pairs by, never from the name match a grid badge may draw on.
     */
    private suspend fun itemsOf(bucketName: String): List<GalleryItem> {
        val rows = localMediaRepo.queryByBucket(bucketName)
        if (rows.isEmpty()) return emptyList()
        // Without an account no photo on the device has a Drive copy this session can act on.
        val userId = accountManager.getPrimaryUserId().first()
            ?: return DeviceFolderDeletion.items(rows, emptyMap(), emptyMap())
        val linkByUri = syncStateRepo.cloudPairedLinkIds(userId, rows.map { it.uri })
        return DeviceFolderDeletion.items(rows, linkByUri, listedCopies(linkByUri.values))
    }

    /** The cloud listing's copy of each of [linkIds] it holds. */
    private suspend fun listedCopies(linkIds: Collection<String>): Map<String, CloudPhoto> {
        if (linkIds.isEmpty()) return emptyMap()
        return driveRepo.observePhotosByLinkIds(linkIds.distinct()).first().associateBy { it.linkId }
    }
}
