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

package eu.akoos.photos.presentation.common

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import me.proton.core.domain.entity.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One app-wide home for the "undo the thing I just did" snackbar. Any screen offers a reversible
 * action here through [offer]; a single snackbar host (in the nav graph) renders whatever is
 * [pending] and calls [undo] on tap. Because it is a process singleton the pending action and its
 * restore survive navigation and the ViewModel that started it, so a delete from the viewer can
 * still be undone after the viewer closes, and the bar can never re-appear on an unrelated screen.
 *
 * The restore itself runs on the controller's own scope, not the caller's, so leaving the screen
 * mid-undo does not cancel the restore. Screens that need to repaint after a restore observe
 * [restored].
 */
@Singleton
class UndoController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hiddenStorage: HiddenStorageManager,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val accountManager: AccountManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pending = MutableStateFlow<UndoAction?>(null)
    /** The action the undo bar is currently offering, or null when nothing is undoable. */
    val pending: StateFlow<UndoAction?> = _pending.asStateFlow()

    private val _restored = MutableSharedFlow<UndoAction>(extraBufferCapacity = 8)
    /** Emitted after an undo finishes so the visible screen can refresh (a hide-restore needs it). */
    val restored: SharedFlow<UndoAction> = _restored.asSharedFlow()

    /** Offer a fresh undoable action. Replaces any earlier pending one (only the latest is undoable). */
    fun offer(action: UndoAction) {
        _pending.value = action
    }

    /** Drop the pending action without restoring (snackbar timed out, dismissed, or navigated away). */
    fun dismiss() {
        _pending.value = null
    }

    /** Reverse the pending action on the controller's own scope. No-op when nothing is pending. */
    fun undo() {
        val action = _pending.value ?: return
        _pending.value = null
        scope.launch {
            try {
                when (action) {
                    is UndoAction.Hide -> restoreHidden(action.hiddenUris)
                    is UndoAction.Delete -> {
                        val userId = accountManager.getPrimaryUserId().first()
                        // Track which cloud copies actually came back out of trash — only those are
                        // safe to re-mark SYNCED. A restore failure must not skip the local un-trash
                        // below, so it is caught here rather than escaping to the outer handler.
                        val restoredCloud: Set<String> =
                            if (action.cloudLinkIds.isNotEmpty() && userId != null) {
                                runCatching {
                                    cloudRepo.restoreFromCloudTrash(userId, action.cloudLinkIds).restoredLinkIds
                                }.getOrElse { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "cloud restore failed: ${e.message}")
                                    emptySet()
                                }
                            } else emptySet()
                        if (action.localTrashedUris.isNotEmpty()) unTrashLocal(action.localTrashedUris)
                        // Re-link ONLY synced photos whose cloud copy is confirmed back. Leaving a pair
                        // LOCAL_ONLY makes the backup re-upload it (safe, re-creates the cloud copy),
                        // whereas a false SYNCED would silently drop the photo from backup.
                        val safeRelinks = action.syncedRelinks.filter { it.cloudLinkId in restoredCloud }
                        if (safeRelinks.isNotEmpty() && userId != null) relinkSynced(safeRelinks, userId)
                    }
                    is UndoAction.AlbumRemove -> {
                        val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                        cloudRepo.addPhotosToAlbum(userId, action.albumLinkId, action.photoLinkIds)
                    }
                }
                _restored.tryEmit(action)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "undo failed: ${e.message}")
            }
        }
    }

    /** Move each vault URI back to MediaStore, prune the hidden-photo bookkeeping, and re-pair any
     *  restored synced photo with its Drive twin so reconcile does not re-upload it as a duplicate. */
    private suspend fun restoreHidden(hiddenUris: List<String>) = withContext(Dispatchers.IO) {
        val prefsSnapshot = context.settingsDataStore.data.first()
        val folderMap = prefsSnapshot[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
        val nameMap = prefsSnapshot[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
        val cloudIdMap = prefsSnapshot[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
        // (cloudLinkId, restoredUri) pairs for the synced photos, transplanted after the prune below.
        val transplants = mutableListOf<Pair<String, String>>()
        for (hiddenUri in hiddenUris) {
            val sourceFolder = folderMap.firstOrNull { it.startsWith("$hiddenUri|") }?.substringAfter('|')
            val originalName = nameMap.firstOrNull { it.startsWith("$hiddenUri|") }?.substringAfter('|')
            val cloudLinkId = cloudIdMap.firstOrNull { it.startsWith("$hiddenUri|") }?.substringAfter('|')
            val restoredUri = hiddenStorage.restore(hiddenUri, originalDisplayName = originalName, albumFolderName = sourceFolder)
            if (cloudLinkId != null && restoredUri != null) transplants += cloudLinkId to restoredUri
        }
        context.settingsDataStore.edit { prefs ->
            val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - hiddenUris.toSet()
            val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] =
                mapping.filterNot { entry -> hiddenUris.any { entry.startsWith("$it|") } }.toSet()
            val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] =
                folders.filterNot { entry -> hiddenUris.any { entry.startsWith("$it|") } }.toSet()
            val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] =
                names.filterNot { entry -> hiddenUris.any { entry.startsWith("$it|") } }.toSet()
        }
        // Transplant each restored synced photo's SyncState row onto its new URI so reconcile pairs by
        // id, not hash. A content-drifted file (for example a re-encoded video) would otherwise re-upload.
        for ((cloudLinkId, restoredUri) in transplants) {
            transplantHiddenSyncState(syncStateRepo, accountManager, cloudLinkId, restoredUri)
        }
    }

    /** Move each local MediaStore file back out of the device trash by clearing IS_TRASHED. Silent
     *  with MANAGE_MEDIA / all-files access (same direct-update path the mirror rename uses); a URI
     *  the OS refuses is logged and skipped, so the file just stays in the device trash and is still
     *  recoverable there. Pre-R deletes are permanent, so there is nothing to un-trash. */
    private fun unTrashLocal(uris: List<String>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 0)
        }
        for (u in uris) {
            runCatching { context.contentResolver.update(android.net.Uri.parse(u), values, null, null) }
                .onFailure { Log.w(TAG, "un-trash skipped for one uri: ${it.message}") }
        }
    }

    /** Re-mark each restored synced photo SYNCED (local URI paired to its cloud linkId) so the backup
     *  skips it instead of uploading a fresh cloud duplicate while the server finishes moving the
     *  original cloud copy back out of trash. */
    private suspend fun relinkSynced(relinks: List<UndoAction.Delete.Relink>, userId: UserId) {
        val now = System.currentTimeMillis()
        for (r in relinks) {
            runCatching {
                syncStateRepo.upsert(
                    SyncState(
                        localUri = r.localUri,
                        cloudFileId = r.cloudLinkId,
                        localHash = "",
                        cloudHash = null,
                        status = SyncStatus.SYNCED,
                        lastSyncAttemptMs = now,
                        lastSyncSuccessMs = now,
                        backedUpAtMs = now,
                        sizeBytes = r.sizeBytes,
                    ),
                    userId,
                )
            }.onFailure { Log.w(TAG, "re-link SyncState skipped for one uri: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "UndoController"
    }
}

/**
 * Re-pair a just-unhidden synced photo with its Drive twin by transplanting the existing SyncState row
 * onto the restored MediaStore URI. Without this the restored file carries no SyncState, so reconcile
 * can't match it by id and, on any content drift across the hide cycle (a video whose mvhd changed, say),
 * starts a fresh upload that duplicates the cloud entry. A no-op when the photo had no cloud twin
 * ([cloudLinkId] null) or the restore failed ([restoredUri] null).
 */
suspend fun transplantHiddenSyncState(
    syncStateRepo: SyncStateRepository,
    accountManager: AccountManager,
    cloudLinkId: String?,
    restoredUri: String?,
) {
    if (cloudLinkId == null || restoredUri == null) return
    runCatching {
        val userId = accountManager.getPrimaryUserId().first()
        val oldRow = syncStateRepo.getByCloudId(cloudLinkId)
        if (oldRow != null && userId != null) {
            // Write a fresh SYNCED row keyed on the new MediaStore URI, carrying the hash and backed-up
            // timestamp so the row history doesn't reset, then flip the stale HIDDEN row (its localUri no
            // longer exists on disk) to CLOUD_ONLY for the next reconcile pass to clean up.
            syncStateRepo.upsert(
                oldRow.copy(localUri = restoredUri, status = SyncStatus.SYNCED),
                userId,
            )
            syncStateRepo.updateStatusAndDeleteLocal(oldRow.localUri, SyncStatus.CLOUD_ONLY)
        }
    }
}

/** Thin ViewModel so the nav-graph snackbar host can observe [UndoController] and drive it. */
@HiltViewModel
class UndoBarViewModel @Inject constructor(
    private val undoController: UndoController,
) : ViewModel() {
    val pending: StateFlow<UndoAction?> = undoController.pending
    fun undo() = undoController.undo()
    fun dismiss() = undoController.dismiss()
}
