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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

private const val TAG = "DeletePhotoUseCase"

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

        // Batch shape up front: item count (overload signal) plus the type mix and cloud/local
        // split, so a delete that later fails is diagnosable from logcat rather than opaque.
        Log.d(
            TAG,
            "delete: items=${items.size} " +
                "[synced=${items.count { it is GalleryItem.Synced }}, " +
                "cloudOnly=${items.count { it is GalleryItem.CloudOnly }}, " +
                "localOnly=${items.count { it is GalleryItem.LocalOnly }}], " +
                "cloudLinks=${cloudLinkIds.size}, localUris=${localUriStrings.size}, " +
                "freeUp=$freeUpSpace, fromCloud=$deleteFromCloud, hide=$hide"
        )

        // Android 11+ with local URIs: defer the cloud delete until the user confirms the
        // system trash dialog. Returning here means cancel ↔ no cloud delete, no divergence.
        // HIDE flows use createDeleteRequest (permanent removal) instead of createTrashRequest:
        // the file is already preserved in the app-private Hidden vault, so leaving a copy in
        // MediaStore Trash for 30 days would just clutter the user's Recently Deleted list.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && localUriStrings.isNotEmpty()) {
            // Parse defensively so one malformed URI string can't abort the whole batch.
            val allLocalUris = localUriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            if (allLocalUris.isNotEmpty()) {
                val pi = try {
                    if (hide)
                        MediaStore.createDeleteRequest(context.contentResolver, allLocalUris)
                    else
                        MediaStore.createTrashRequest(context.contentResolver, allLocalUris, true)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // A foreign or unindexable URI can throw while building the request. Log the
                    // batch size and a MIME sample so a size- or type-specific failure is visible
                    // in logcat, then fail the delete safely instead of crashing the caller.
                    val types = allLocalUris.take(8).joinToString {
                        runCatching { context.contentResolver.getType(it) }.getOrNull() ?: "?"
                    }
                    Log.e(TAG, "Building the trash/delete request failed for ${allLocalUris.size} uri(s); sample types=[$types]", e)
                    return Result.CloudDeleteFailed
                }
                return Result.NeedsMediaWritePermission(
                    pendingIntent       = pi,
                    cloudLinkIds        = cloudLinkIds,
                    itemsBeingDeleted   = items,
                    freeUpSpace         = freeUpSpace,
                    hide                = hide,
                )
            }
            Log.w(TAG, "No parseable local URIs out of ${localUriStrings.size}; continuing to the cloud-only path")
        }

        // No local trash to wait on → run cloud delete (if any) immediately.
        if (cloudLinkIds.isNotEmpty()) {
            try {
                val outcome = cloudRepo.deleteFiles(userId, cloudLinkIds)
                // The bulk endpoint can accept the batch yet reject individual links (per-link
                // code != 1000); when NONE were trashed the delete wholly failed, so surface it
                // the same way a thrown error would instead of silently claiming success.
                if (outcome.trashedLinkIds.isEmpty()) {
                    Log.w(TAG, "Cloud delete: server trashed 0/${cloudLinkIds.size} link(s)")
                    return Result.CloudDeleteFailed
                }
                if (outcome.failedLinkIds.isNotEmpty()) {
                    Log.w(TAG, "Cloud delete: ${outcome.failedLinkIds.size}/${cloudLinkIds.size} link(s) stayed on cloud")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Cloud delete failed for ${cloudLinkIds.size} link(s)", e)
                return Result.CloudDeleteFailed
            }
        }

        // Pre-R: direct delete (no system trash support). Asymmetry note vs the R+ path: the cloud
        // delete above has already run, so a local delete that fails here would leave the cloud copy
        // trashed while the device copy survives. Pre-R local deletes of the app's own media
        // effectively never fail (the storage permission is granted at install), so this is kept
        // best-effort rather than reordered, which would risk the far more common R+ flow.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && localUriStrings.isNotEmpty()) {
            for (uri in localUriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                    .onFailure { Log.w(TAG, "Pre-R local delete failed for one uri", it) }
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
                val outcome = cloudRepo.deleteFiles(userId, cloudLinkIds)
                if (outcome.trashedLinkIds.isEmpty()) {
                    Log.w(TAG, "Cloud delete: server trashed 0/${cloudLinkIds.size} link(s)")
                    return Result.CloudDeleteFailed
                }
                if (outcome.failedLinkIds.isNotEmpty()) {
                    Log.w(TAG, "Cloud delete: ${outcome.failedLinkIds.size}/${cloudLinkIds.size} link(s) stayed on cloud")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Cloud delete failed for ${cloudLinkIds.size} link(s)", e)
                return Result.CloudDeleteFailed
            }
        }
        updateSyncStateAfterLocalDelete(items, freeUpSpace, hide)
        return Result.Success
    }

    private suspend fun updateSyncStateAfterLocalDelete(items: List<GalleryItem>, freeUpSpace: Boolean, hide: Boolean) {
        for (item in items) {
            try {
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // One item's DB update failing must not strand the rest of the batch mid-loop.
                Log.w(TAG, "Sync-state update failed for one item after local delete", e)
            }
        }
    }
}
