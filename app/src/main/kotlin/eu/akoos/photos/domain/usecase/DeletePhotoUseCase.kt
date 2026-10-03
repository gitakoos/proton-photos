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

import android.app.PendingIntent
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

/** Device uris one system trash or delete request carries. The request crosses a Binder transaction,
 *  which a library-sized batch outgrows, so a larger delete asks once per chunk. */
internal const val DEVICE_REQUEST_CHUNK = 1_000

/** The device side of a delete, apart from the use case so a test can stand in for MediaStore. */
internal interface DeviceMediaDeletes {
    /** Whether device files go through a system request the user confirms, which Android 11 brings,
     *  rather than [delete]. */
    val asksConsent: Boolean

    /** The Android 11+ system request that trashes [uris], or removes them for good when [permanent]. */
    fun request(uris: List<Uri>, permanent: Boolean): PendingIntent

    /** Removes one device file directly, the only way before Android 11. True once the file is gone. */
    fun delete(uri: Uri): Boolean
}

class DeletePhotoUseCase internal constructor(
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val device: DeviceMediaDeletes,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        syncStateRepo: SyncStateRepository,
        cloudRepo: DrivePhotoRepository,
    ) : this(syncStateRepo, cloudRepo, MediaStoreDeletes(context))

    sealed interface Result {
        /**
         * The delete went through. [keptOnDevice] holds the items whose device file is still there:
         * a direct delete was refused (Android 10 refuses another app's file without a per-file
         * consent), or a follow-up system request could not be built. Their rows and Drive copies
         * are left as they were.
         */
        data class Success(val keptOnDevice: List<GalleryItem> = emptyList()) : Result {
            /** [items] less the ones [keptOnDevice] names: what the delete carried out. */
            fun landed(items: List<GalleryItem>): List<GalleryItem> =
                if (keptOnDevice.isEmpty()) items
                else keptOnDevice.toHashSet().let { kept -> items.filterNot { it in kept } }
        }

        /**
         * Android 11+ wants the user to confirm the system trash dialog. [cloudLinkIds] are the
         * Drive linkIds to delete AFTER the user confirms — passing them here defers the cloud
         * delete so a cancel cannot leave the cloud copy trashed while the local copy survives.
         *
         * A delete of more than [DEVICE_REQUEST_CHUNK] device files asks in chunks, one dialog each:
         * [itemsBeingDeleted] is this request's share, [done] what earlier requests of the same delete
         * already carried out, and [remaining] what still waits for a request of its own.
         */
        data class NeedsMediaWritePermission(
            val pendingIntent: PendingIntent,
            val cloudLinkIds: List<String>,
            val itemsBeingDeleted: List<GalleryItem>,
            val freeUpSpace: Boolean,
            /** Whether this delete is part of a HIDE flow — controls whether the post-confirm
             *  SyncState transitions to HIDDEN (hide) or CLOUD_ONLY (regular free-up-space). */
            val hide: Boolean,
            /** Whether the delete takes the Drive copies too, for the whole delete, not only this chunk. */
            val deleteFromCloud: Boolean = cloudLinkIds.isNotEmpty(),
            val done: List<GalleryItem> = emptyList(),
            val remaining: List<GalleryItem> = emptyList(),
        ) : Result {
            /** Everything the delete has carried out once this request is granted too. */
            val confirmedItems: List<GalleryItem> get() = done + itemsBeingDeleted
        }

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
        userId: UserId?,
        items: List<GalleryItem>,
        freeUpSpace: Boolean,
        deleteFromCloud: Boolean,
        hide: Boolean = false,
    ): Result {
        val (cloudLinkIds, localUriStrings) = computeDeleteTargets(items, freeUpSpace, deleteFromCloud)

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
        if (device.asksConsent && localUriStrings.isNotEmpty()) {
            val chunks = requestChunks(items, freeUpSpace)
            return request(
                chunk = chunks.first(),
                remaining = chunks.drop(1).flatten(),
                done = emptyList(),
                freeUpSpace = freeUpSpace,
                deleteFromCloud = deleteFromCloud,
                hide = hide,
            )
        }

        if (localUriStrings.isEmpty()) {
            if (cloudLinkIds.isNotEmpty() && !trashInCloud(userId, cloudLinkIds)) return Result.CloudDeleteFailed
            return Result.Success()
        }

        // Before Android 11 each device file is removed directly and checked on its own: Android 10
        // refuses another app's file, and a refused photo keeps its row and its Drive copy exactly
        // as they were. The Drive half runs after the device half for that reason.
        val gone = localUriStrings.filterTo(HashSet()) { device.delete(Uri.parse(it)) }
        val kept = items.filter { item -> deviceUriOf(item, freeUpSpace)?.let { it !in gone } == true }
        if (kept.isNotEmpty()) {
            Log.w(TAG, "Direct delete left ${kept.size}/${localUriStrings.size} device file(s) in place")
        }
        val outcome = Result.Success(kept)
        val settled = outcome.landed(items)
        updateSyncStateAfterLocalDelete(settled, freeUpSpace, hide)
        val settledLinks =
            if (kept.isEmpty()) cloudLinkIds
            else computeDeleteTargets(settled, freeUpSpace, deleteFromCloud).cloudLinkIds
        if (settledLinks.isNotEmpty() && !trashInCloud(userId, settledLinks)) return Result.CloudDeleteFailed
        return outcome
    }

    /**
     * Called by the caller AFTER the user confirmed the Android 11+ system trash dialog. Runs the
     * deferred cloud delete and updates SyncState for the items whose local copy was trashed.
     */
    suspend fun completeAfterPermissionGranted(
        userId: UserId?,
        cloudLinkIds: List<String>,
        items: List<GalleryItem>,
        freeUpSpace: Boolean,
        hide: Boolean = false,
    ): Result {
        if (cloudLinkIds.isNotEmpty() && !trashInCloud(userId, cloudLinkIds)) return Result.CloudDeleteFailed
        updateSyncStateAfterLocalDelete(items, freeUpSpace, hide)
        return Result.Success()
    }

    /**
     * Finishes the request [pending] names once its dialog was confirmed, and asks for the next chunk
     * of the same delete when one is left: a [Result.NeedsMediaWritePermission] the caller launches
     * like the first. A chunk whose Drive half fails ends the delete there. A next request that
     * cannot be built ends it too, reporting what it leaves in [Result.Success.keptOnDevice].
     */
    suspend fun completeAfterPermissionGranted(
        userId: UserId?,
        pending: Result.NeedsMediaWritePermission,
    ): Result {
        val landed = completeAfterPermissionGranted(
            userId, pending.cloudLinkIds, pending.itemsBeingDeleted, pending.freeUpSpace, pending.hide,
        )
        if (landed !is Result.Success || pending.remaining.isEmpty()) return landed
        val next = pending.remaining.take(DEVICE_REQUEST_CHUNK)
        val following = request(
            chunk = next,
            remaining = pending.remaining.drop(next.size),
            done = pending.confirmedItems,
            freeUpSpace = pending.freeUpSpace,
            deleteFromCloud = pending.deleteFromCloud,
            hide = pending.hide,
        )
        return if (following is Result.CloudDeleteFailed) Result.Success(keptOnDevice = pending.remaining) else following
    }

    /** The system request for [chunk], or [Result.CloudDeleteFailed] when it cannot be built. */
    private fun request(
        chunk: List<GalleryItem>,
        remaining: List<GalleryItem>,
        done: List<GalleryItem>,
        freeUpSpace: Boolean,
        deleteFromCloud: Boolean,
        hide: Boolean,
    ): Result {
        val targets = computeDeleteTargets(chunk, freeUpSpace, deleteFromCloud)
        val pi = try {
            device.request(targets.localUriStrings.map { Uri.parse(it) }, permanent = hide)
        } catch (e: Exception) {
            // A foreign or unindexable URI can throw while building the request. Fail the delete
            // safely instead of crashing the caller; nothing of this chunk was touched.
            Log.e(TAG, "Building the trash/delete request failed for ${targets.localUriStrings.size} uri(s)", e)
            return Result.CloudDeleteFailed
        }
        return Result.NeedsMediaWritePermission(
            pendingIntent = pi,
            cloudLinkIds = targets.cloudLinkIds,
            itemsBeingDeleted = chunk,
            freeUpSpace = freeUpSpace,
            hide = hide,
            deleteFromCloud = deleteFromCloud,
            done = done,
            remaining = remaining,
        )
    }

    /** Moves [linkIds] to Drive trash; false when none went. A cloud trash needs a signed-in user:
     *  local-only mode never yields cloud targets, so the null check only guards a caller that passes
     *  cloud links with no session, never a real guest flow. */
    private suspend fun trashInCloud(userId: UserId?, linkIds: List<String>): Boolean {
        if (userId == null) {
            Log.w(TAG, "Cloud delete requested with no signed-in user; skipping ${linkIds.size} link(s)")
            return false
        }
        return try {
            val outcome = cloudRepo.deleteFiles(userId, linkIds)
            // The bulk endpoint can accept the batch yet reject individual links (per-link
            // code != 1000); when NONE were trashed the delete wholly failed, so surface it
            // the same way a thrown error would instead of silently claiming success.
            if (outcome.trashedLinkIds.isEmpty()) {
                Log.w(TAG, "Cloud delete: server trashed 0/${linkIds.size} link(s)")
                false
            } else {
                if (outcome.failedLinkIds.isNotEmpty()) {
                    Log.w(TAG, "Cloud delete: ${outcome.failedLinkIds.size}/${linkIds.size} link(s) stayed on cloud")
                }
                true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Cloud delete failed for ${linkIds.size} link(s)", e)
            false
        }
    }

    /**
     * Reconciles each row's SyncStatus after the local MediaStore copy was already removed by the
     * caller above. Despite its name, [SyncStateRepository.updateStatusAndDeleteLocal] only UPDATES
     * the status column; it deletes nothing itself.
     */
    private suspend fun updateSyncStateAfterLocalDelete(items: List<GalleryItem>, freeUpSpace: Boolean, hide: Boolean) {
        for (item in items) {
            try {
                val newStatus = postDeleteSyncStatus(item, freeUpSpace, hide)
                val localUri = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced -> item.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                if (newStatus != null && localUri != null) {
                    syncStateRepo.updateStatusAndDeleteLocal(localUri, newStatus)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // One item's DB update failing must not strand the rest of the batch mid-loop.
                Log.w(TAG, "Sync-state update failed for one item after local delete", e)
            }
        }
    }

    /** Cloud linkIds to trash and device URI strings to delete, produced by [computeDeleteTargets]. */
    data class DeleteTargets(
        val cloudLinkIds: List<String>,
        val localUriStrings: List<String>,
    )

    companion object {
        /**
         * Splits [items] into the cloud linkIds to trash and the device URI strings to delete, the
         * single safety decision of the whole delete path. Pure and side-effect-free so it can be
         * pinned by a plain JVM test.
         *
         * A cloud copy is trashed only when [deleteFromCloud] is set (a Synced or CloudOnly item's
         * linkId). A device URI enters the delete set only when [freeUpSpace] is set (a LocalOnly or
         * Synced item's uri): on a "remove cloud copies, keep local files" delete ([freeUpSpace] false,
         * [deleteFromCloud] true) a LocalOnly item has no cloud copy to trash and is left untouched,
         * since deleting it would destroy the only copy of a photo the user chose to keep. A CloudOnly
         * item never yields a device URI and a LocalOnly item never yields a cloud linkId.
         */
        fun computeDeleteTargets(
            items: List<GalleryItem>,
            freeUpSpace: Boolean,
            deleteFromCloud: Boolean,
        ): DeleteTargets {
            val cloudLinkIds = items.mapNotNull { item ->
                when {
                    deleteFromCloud && item is GalleryItem.Synced    -> item.cloud.linkId
                    deleteFromCloud && item is GalleryItem.CloudOnly -> item.cloud.linkId
                    else -> null
                }
            }
            val localUriStrings = items.mapNotNull { deviceUriOf(it, freeUpSpace) }
            return DeleteTargets(cloudLinkIds, localUriStrings)
        }

        /** The device file a delete of [item] removes, or null when it removes none. */
        private fun deviceUriOf(item: GalleryItem, freeUpSpace: Boolean): String? = when {
            !freeUpSpace -> null
            item is GalleryItem.LocalOnly -> item.local.uri
            item is GalleryItem.Synced -> item.local.uri
            else -> null
        }

        /**
         * [items] cut into the chunks a delete asks the system about one at a time, each holding at
         * most [maxUris] device files. The items with no device file to remove ride with the first
         * chunk, so their Drive half runs once the first dialog is confirmed, as it does for a delete
         * that fits one request.
         */
        fun requestChunks(
            items: List<GalleryItem>,
            freeUpSpace: Boolean,
            maxUris: Int = DEVICE_REQUEST_CHUNK,
        ): List<List<GalleryItem>> {
            val (onDevice, elsewhere) = items.partition { deviceUriOf(it, freeUpSpace) != null }
            if (onDevice.isEmpty()) return listOf(items)
            val chunks = onDevice.chunked(maxUris)
            return listOf(elsewhere + chunks.first()) + chunks.drop(1)
        }

        /**
         * The one SyncState transition the local delete implies for a single [item], or null when its
         * row must be left as-is. A LocalOnly item becomes LOCAL_ONLY; a Synced item becomes HIDDEN on a
         * hide flow, else CLOUD_ONLY when its device copy was freed. HIDE wins over freeUpSpace so a
         * hidden synced photo is never rendered as a plain CLOUD_ONLY one and reconcile / the upload
         * pipeline skip it. A CloudOnly item, or a Synced item that kept its device copy, transitions to
         * nothing.
         */
        fun postDeleteSyncStatus(item: GalleryItem, freeUpSpace: Boolean, hide: Boolean): SyncStatus? =
            when {
                item is GalleryItem.LocalOnly -> SyncStatus.LOCAL_ONLY
                item is GalleryItem.Synced && hide -> SyncStatus.HIDDEN
                item is GalleryItem.Synced && freeUpSpace -> SyncStatus.CLOUD_ONLY
                else -> null
            }

        /**
         * Whether this delete makes [item] disappear from the gallery, as opposed to converting it into
         * a photo that is still listed.
         *
         * Only three of the five delete choices actually remove anything: a Synced photo keeps a copy
         * unless BOTH sides go, so freeing its device space leaves a cloud photo behind and trashing its
         * cloud copy leaves the device file behind. Both of those keep the item on screen, in a
         * different form, which is why a caller cannot treat "delete finished" as "the photo is gone".
         */
        fun removesFromGallery(item: GalleryItem, freeUpSpace: Boolean, deleteFromCloud: Boolean): Boolean =
            when (item) {
                is GalleryItem.LocalOnly -> freeUpSpace
                is GalleryItem.CloudOnly -> deleteFromCloud
                is GalleryItem.Synced -> freeUpSpace && deleteFromCloud
            }
    }
}

/** [DeviceMediaDeletes] over MediaStore, the one the app runs with. */
private class MediaStoreDeletes(private val context: Context) : DeviceMediaDeletes {

    override val asksConsent: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun request(uris: List<Uri>, permanent: Boolean): PendingIntent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("system delete requests start with Android 11")
        }
        val resolver = context.contentResolver
        return try {
            if (permanent) MediaStore.createDeleteRequest(resolver, uris)
            else MediaStore.createTrashRequest(resolver, uris, true)
        } catch (e: Exception) {
            // A MIME sample makes a size- or type-specific refusal visible in logcat.
            val types = uris.take(8).joinToString { runCatching { resolver.getType(it) }.getOrNull() ?: "?" }
            Log.e(TAG, "Request refused for ${uris.size} uri(s); sample types=[$types]")
            throw e
        }
    }

    override fun delete(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val removed = try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            // Android 10 answers another app's file with a RecoverableSecurityException.
            Log.w(TAG, "Direct delete refused for one uri: ${e.javaClass.simpleName}")
            return false
        }
        if (removed) return true
        // Nothing removed: a file already gone counts as deleted, one that cannot be asked about as kept.
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { !it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
