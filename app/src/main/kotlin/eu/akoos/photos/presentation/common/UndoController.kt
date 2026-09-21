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
import eu.akoos.photos.data.hidden.HiddenVaultRestorer
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
    private val hiddenVaultRestorer: HiddenVaultRestorer,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val accountManager: AccountManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pending = MutableStateFlow<UndoAction?>(null)
    /** The action the undo bar is currently offering, or null when nothing is undoable. */
    val pending: StateFlow<UndoAction?> = _pending.asStateFlow()

    private val _restored = MutableSharedFlow<UndoAction>(extraBufferCapacity = 8)
    /** Emitted after an undo brought something back, so the visible screen can refresh (a
     *  hide-restore needs it). An undo that reversed nothing at all stays silent here: repainting a
     *  grid that is unchanged tells the user the photo is back when it is not. */
    val restored: SharedFlow<UndoAction> = _restored.asSharedFlow()

    private val _undoFailed = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** How many photos an undo could not bring back, emitted whenever that is more than none. The
     *  bar's own message says the undo happened, so this is the only place the shortfall can be
     *  told; without it a photo stays hidden while the screen reads as restored. */
    val undoFailed: SharedFlow<Int> = _undoFailed.asSharedFlow()

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
                val reversedSomething = when (action) {
                    is UndoAction.Hide -> reverseHide(action)
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
                        true
                    }
                    is UndoAction.AlbumRemove -> {
                        val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                        cloudRepo.addPhotosToAlbum(userId, action.albumLinkId, action.photoLinkIds)
                        true
                    }
                }
                if (reversedSomething) _restored.tryEmit(action)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "undo failed: ${e.message}")
                // A hide undo that threw brought nothing back, and the bar has already claimed it
                // did, so the shortfall is reported here rather than left to the log.
                if (action is UndoAction.Hide) _undoFailed.tryEmit(action.count)
            }
        }
    }

    /**
     * Reverse both halves of a hide and answer whether anything actually came back.
     *
     * The client-side half goes first and costs one preference write: dropping the linkIds from the
     * hidden set puts the backed-up and cloud-only photos back in every listing, with nothing on
     * Drive and nothing on the device to touch. The restorer then owns the vault half's whole round
     * trip — the bytes, the index, the per-uri records, the journal and a folder hide's own folder
     * name — so an undone hide leaves the vault exactly as a reveal from the vault screen does.
     *
     * Every photo the two halves could not reach is counted and reported, because the bar has
     * already told the user the hide was undone.
     */
    private suspend fun reverseHide(action: UndoAction.Hide): Boolean {
        var failed = 0
        if (action.cloudLinkIds.isNotEmpty()) {
            try {
                context.settingsDataStore.edit { prefs ->
                    val existing = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = existing - action.cloudLinkIds.toSet()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "undo: hidden set not cleared: ${e.message}")
                failed += action.cloudLinkIds.size
            }
        }
        failed += hiddenVaultRestorer.restoreAll(action.hiddenUris)
        if (failed > 0) _undoFailed.tryEmit(failed)
        return failed < action.count
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

/** Thin ViewModel so the nav-graph snackbar host can observe [UndoController] and drive it. */
@HiltViewModel
class UndoBarViewModel @Inject constructor(
    private val undoController: UndoController,
) : ViewModel() {
    val pending: StateFlow<UndoAction?> = undoController.pending
    val undoFailed: SharedFlow<Int> = undoController.undoFailed
    fun undo() = undoController.undo()
    fun dismiss() = undoController.dismiss()
}
