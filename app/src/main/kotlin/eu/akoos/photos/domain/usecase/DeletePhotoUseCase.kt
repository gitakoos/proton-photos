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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

class DeletePhotoUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
) {
    sealed interface Result {
        data object Success : Result
        /**
         * Android 11+ wants the user to confirm the system trash dialog. [cloudLinkIds] are the
         * Drive linkIds to delete AFTER the user confirms — passing them here defers the cloud
         * delete so a cancel cannot leave the cloud copy trashed while the local copy survives.
         */
        data class NeedsMediaWritePermission(
            val pendingIntent: android.app.PendingIntent,
            val cloudLinkIds: List<String>,
            val itemsBeingDeleted: List<GalleryItem>,
            val freeUpSpace: Boolean,
            /** Whether this delete is part of a HIDE flow — controls whether the post-confirm
             *  SyncState transitions to HIDDEN (hide) or CLOUD_ONLY (regular free-up-space). */
            val hide: Boolean,
        ) : Result
        data object CloudDeleteFailed : Result
    }

    /**
     * @param hide When true, this is a HIDE operation, not a regular delete:
     *   - The local URI is permanently removed via [MediaStore.createDeleteRequest] (no system
     *     trash, since the file is already preserved in the app-private Hidden vault).
     *   - The post-confirm SyncState is set to [SyncStatus.HIDDEN] instead of [SyncStatus.CLOUD_ONLY]
     *     so reconcile / cleanup skip it and the cloud copy can be rendered with a hidden-eye
     *     overlay rather than appearing as a generic cloud-only photo.
     */
    suspend operator fun invoke(
        userId: UserId,
        items: List<GalleryItem>,
        freeUpSpace: Boolean,
        deleteFromCloud: Boolean,
        hide: Boolean = false,
    ): Result {
        val cloudLinkIds = items.mapNotNull { item ->
            when {
                deleteFromCloud && item is GalleryItem.Synced    -> item.cloud.linkId
                deleteFromCloud && item is GalleryItem.CloudOnly -> item.cloud.linkId
                else -> null
            }
        }
        val localUriStrings = items.mapNotNull { item ->
            when {
                item is GalleryItem.LocalOnly                    -> item.local.uri
                item is GalleryItem.Synced && freeUpSpace        -> item.local.uri
                else -> null
            }
        }

        // Android 11+ with local URIs: defer the cloud delete until the user confirms the
        // system trash dialog. Returning here means cancel ↔ no cloud delete, no divergence.
        // HIDE flows use createDeleteRequest (permanent removal) instead of createTrashRequest:
        // the file is already preserved in the app-private Hidden vault, so leaving a copy in
        // MediaStore Trash for 30 days would just clutter the user's Recently Deleted list.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && localUriStrings.isNotEmpty()) {
            val allLocalUris = localUriStrings.map { Uri.parse(it) }
            val pi = if (hide)
                MediaStore.createDeleteRequest(context.contentResolver, allLocalUris)
            else
                MediaStore.createTrashRequest(context.contentResolver, allLocalUris, true)
            return Result.NeedsMediaWritePermission(
                pendingIntent       = pi,
                cloudLinkIds        = cloudLinkIds,
                itemsBeingDeleted   = items,
                freeUpSpace         = freeUpSpace,
                hide                = hide,
            )
        }

        // No local trash to wait on → run cloud delete (if any) immediately.
        if (cloudLinkIds.isNotEmpty()) {
            try {
                cloudRepo.deleteFiles(userId, cloudLinkIds)
            } catch (e: Exception) {
                return Result.CloudDeleteFailed
            }
        }

        // Pre-R: direct delete (no system trash support).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && localUriStrings.isNotEmpty()) {
            for (uri in localUriStrings.map(Uri::parse)) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            updateSyncStateAfterLocalDelete(items, freeUpSpace, hide)
        }
        return Result.Success
    }

    /**
     * Called by the caller AFTER the user confirmed the Android 11+ system trash dialog. Runs the
     * deferred cloud delete and updates SyncState for the items whose local copy was trashed.
     */
    suspend fun completeAfterPermissionGranted(
        userId: UserId,
        cloudLinkIds: List<String>,
        items: List<GalleryItem>,
        freeUpSpace: Boolean,
        hide: Boolean = false,
    ): Result {
        if (cloudLinkIds.isNotEmpty()) {
            try {
                cloudRepo.deleteFiles(userId, cloudLinkIds)
            } catch (e: Exception) {
                return Result.CloudDeleteFailed
            }
        }
        updateSyncStateAfterLocalDelete(items, freeUpSpace, hide)
        return Result.Success
    }

    private suspend fun updateSyncStateAfterLocalDelete(items: List<GalleryItem>, freeUpSpace: Boolean, hide: Boolean) {
        for (item in items) {
            when {
                item is GalleryItem.LocalOnly ->
                    syncStateRepo.updateStatusAndDeleteLocal(item.local.uri, SyncStatus.LOCAL_ONLY)
                // HIDE wins over freeUpSpace: a hidden synced photo must not appear in the
                // gallery as a plain CLOUD_ONLY photo or the user has no way to tell which
                // cloud photos are hidden. The HIDDEN status also keeps reconcile from
                // demoting it on every refresh and the upload pipeline from re-uploading it.
                item is GalleryItem.Synced && hide ->
                    syncStateRepo.updateStatusAndDeleteLocal(item.local.uri, SyncStatus.HIDDEN)
                item is GalleryItem.Synced && freeUpSpace ->
                    syncStateRepo.updateStatusAndDeleteLocal(item.local.uri, SyncStatus.CLOUD_ONLY)
            }
        }
    }
}
