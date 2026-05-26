package me.proton.photos.domain.usecase

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.SyncStateRepository
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
        ) : Result
        data object CloudDeleteFailed : Result
    }

    suspend operator fun invoke(
        userId: UserId,
        items: List<GalleryItem>,
        freeUpSpace: Boolean,
        deleteFromCloud: Boolean,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && localUriStrings.isNotEmpty()) {
            val allLocalUris = localUriStrings.map { Uri.parse(it) }
            val pi = MediaStore.createTrashRequest(context.contentResolver, allLocalUris, true)
            return Result.NeedsMediaWritePermission(
                pendingIntent       = pi,
                cloudLinkIds        = cloudLinkIds,
                itemsBeingDeleted   = items,
                freeUpSpace         = freeUpSpace,
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
            updateSyncStateAfterLocalDelete(items, freeUpSpace)
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
    ): Result {
        if (cloudLinkIds.isNotEmpty()) {
            try {
                cloudRepo.deleteFiles(userId, cloudLinkIds)
            } catch (e: Exception) {
                return Result.CloudDeleteFailed
            }
        }
        updateSyncStateAfterLocalDelete(items, freeUpSpace)
        return Result.Success
    }

    private suspend fun updateSyncStateAfterLocalDelete(items: List<GalleryItem>, freeUpSpace: Boolean) {
        for (item in items) {
            when {
                item is GalleryItem.LocalOnly ->
                    syncStateRepo.updateStatusAndDeleteLocal(item.local.uri, SyncStatus.LOCAL_ONLY)
                item is GalleryItem.Synced && freeUpSpace ->
                    syncStateRepo.updateStatusAndDeleteLocal(item.local.uri, SyncStatus.CLOUD_ONLY)
            }
        }
    }
}
