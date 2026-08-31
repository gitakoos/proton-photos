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

import android.content.IntentSender
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.MoveToFolderUseCase
import eu.akoos.photos.domain.usecase.PendingMove
import eu.akoos.photos.presentation.gallery.DeviceFolderChoice
import eu.akoos.photos.presentation.gallery.deviceFolderChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared "Move to folder" flow so every screen with a photo selection can relocate device photos
 * in place into a DCIM/&lt;name&gt;/ folder the same way the timeline does, from one place. Mirrors
 * [PublicLinkController]: not a singleton (each owning ViewModel gets its own instance) and every
 * method takes that ViewModel's [CoroutineScope] so the work is cancelled with it.
 *
 * The picker's targets come from [targetFolders]; a completed move reports its destination folder
 * through [moveConfirmation]; a foreign file needing one-shot write consent surfaces its request on
 * [pendingMoveIntent]. Only device MediaStore rows move, never cloud content.
 */
class MoveToFolderController @Inject constructor(
    private val moveToFolder: MoveToFolderUseCase,
    private val localMediaRepo: LocalMediaRepository,
) {

    /** One-shot system write-consent request a move needs when the selection holds a file the app
     *  does not own. The host launches it and calls [onPermissionGranted] on approval; null otherwise. */
    private val _pendingMoveIntent = MutableStateFlow<IntentSender?>(null)
    val pendingMoveIntent: StateFlow<IntentSender?> = _pendingMoveIntent.asStateFlow()

    /** Destination folder name of a completed move, for the host's snackbar. replay=0 + single buffer
     *  so a paused screen never blocks the emit. */
    private val _moveConfirmation = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val moveConfirmation: SharedFlow<String> = _moveConfirmation.asSharedFlow()

    /** The deferred move stashed while the user approves the system write dialog, replayed by
     *  [onPermissionGranted] and dropped by [clearPending]. */
    private var stashedMove: PendingMove? = null

    /** The completion route stashed alongside [stashedMove], so a move that needed consent still
     *  reports the same way it would have without it: the generic confirmation for a plain move, or
     *  the caller's own callback for a rename. */
    private var stashedOnMoved: ((String) -> Unit)? = null

    /**
     * Existing device folders offered as move targets, grouped from every on-device photo, most
     * populated first. The regrouping runs off-Main so a large library never touches the collector's
     * thread. Each call returns a fresh flow scoped to [scope]; a caller collects it once and holds it.
     */
    fun targetFolders(scope: CoroutineScope): StateFlow<List<DeviceFolderChoice>> =
        localMediaRepo.observeLocalMedia()
            .map { deviceFolderChoices(it) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Move [uris] into [folderName] under DCIM/. A blank name or an empty selection is a no-op. */
    fun move(scope: CoroutineScope, uris: List<String>, folderName: String) {
        if (folderName.isBlank() || uris.isEmpty()) return
        scope.launch { handleMoveResult(moveToFolder(uris, folderName), folderName) { _moveConfirmation.tryEmit(it) } }
    }

    /** Create a device folder "born with photos": the same path as [move], the folder exists purely
     *  because MediaStore now holds [uris] under that bucket. */
    fun createFolder(scope: CoroutineScope, name: String, uris: List<String>) {
        if (name.isBlank() || uris.isEmpty()) return
        scope.launch { handleMoveResult(moveToFolder(uris, name), name) { _moveConfirmation.tryEmit(it) } }
    }

    /** Rename a device folder by relocating every photo in [bucketName] into [newName] under DCIM/.
     *  An empty folder or a blank name is a no-op. The landed name is reported through [onRenamed]
     *  rather than [moveConfirmation], so the caller shows its own "renamed" confirmation and points
     *  the screen at the new folder. A rename that needs write consent still routes its deferred
     *  completion to [onRenamed] through [onPermissionGranted]. */
    fun rename(scope: CoroutineScope, bucketName: String, newName: String, onRenamed: (String) -> Unit) {
        scope.launch {
            val uris = localMediaRepo.queryByBucket(bucketName).map { it.uri }
            if (uris.isEmpty() || newName.isBlank()) return@launch
            handleMoveResult(moveToFolder(uris, newName), newName) { onRenamed(it) }
        }
    }

    /** Replay the deferred move on the foreign files after the user granted the system write request. */
    fun onPermissionGranted(scope: CoroutineScope) {
        val pending = stashedMove ?: run { _pendingMoveIntent.value = null; return }
        val onMoved: (String) -> Unit = stashedOnMoved ?: { _moveConfirmation.tryEmit(it) }
        scope.launch {
            handleMoveResult(moveToFolder.completeAfterPermissionGranted(pending), pending.folderName, onMoved)
        }
    }

    /** User cancelled the system write dialog; drop the deferred files, nothing moves. */
    fun clearPending() {
        stashedMove = null
        stashedOnMoved = null
        _pendingMoveIntent.value = null
    }

    private suspend fun handleMoveResult(
        result: MoveToFolderUseCase.Result,
        folderName: String,
        onMoved: (String) -> Unit,
    ) {
        when (result) {
            is MoveToFolderUseCase.Result.Moved -> {
                stashedMove = null
                stashedOnMoved = null
                _pendingMoveIntent.value = null
                onMoved(folderName)
            }
            is MoveToFolderUseCase.Result.NeedsPermission -> {
                stashedMove = result.pending
                stashedOnMoved = onMoved
                _pendingMoveIntent.value = result.intentSender
            }
            is MoveToFolderUseCase.Result.Failed,
            MoveToFolderUseCase.Result.NothingToDo -> {
                stashedMove = null
                stashedOnMoved = null
                _pendingMoveIntent.value = null
            }
        }
    }
}
