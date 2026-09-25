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

package eu.akoos.photos.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.data.mlsync.FaceSnapshotMode
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveResult
import eu.akoos.photos.data.mlsync.MlRemoteSnapshot
import eu.akoos.photos.data.mlsync.MlSyncController
import eu.akoos.photos.data.mlsync.MlSyncStart
import eu.akoos.photos.data.mlsync.MlSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** Whether the Drive section may show: an account ([signedIn]) on a device the Drive copy runs on. */
internal data class MlDriveAccess(val signedIn: Boolean, val driveAvailable: Boolean)

/** The restore flow's sheet: the listing, the pick of one device's set, and the Merge or Replace question. */
internal sealed interface MlRestoreSheet {
    data object Hidden : MlRestoreSheet

    /** The ML data folder is being listed. */
    data object Loading : MlRestoreSheet

    /** The saved sets, one per device, the one picked so far, and which of its kinds of data stay ticked. */
    data class Pick(
        val devices: List<MlRemoteDevice>,
        val selectedId: String?,
        val kinds: Set<MlDriveFileKind> = emptySet(),
    ) : MlRestoreSheet

    /** Nothing is saved yet. */
    data object Empty : MlRestoreSheet

    /** The listing failed. */
    data object Failed : MlRestoreSheet

    /** This device already holds face data, so the user chooses how the picked [files] join it, Merge first. */
    data class ChooseMode(val files: List<MlRemoteSnapshot>) : MlRestoreSheet
}

/**
 * The "Face and search data on Drive" section's state and actions, over [MlSyncController]. Kept apart from
 * [SettingsViewModel] so the settings hub does not carry the Drive flow.
 *
 * The account is read from [AccountManager.getPrimaryUserId] as the outer flow, so a sign-in shows the
 * section in place and a sign-out hides it; it starts hidden, so it never flashes before the account is
 * known. The save and restore themselves run as the controller's one background job, which outlives this
 * screen; a result that lands while the screen is closed waits in [syncState] until the user sees it.
 */
@HiltViewModel
class MlDriveSyncViewModel @Inject constructor(
    private val controller: MlSyncController,
    private val accountManager: AccountManager,
) : ViewModel() {

    internal val access: StateFlow<MlDriveAccess> = accountManager.getPrimaryUserId()
        .map { userId -> MlDriveAccess(signedIn = userId != null, driveAvailable = controller.isAvailable(userId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MlDriveAccess(false, false))

    /** The one save or restore job: idle, running with its progress, or finished until dismissed. */
    val syncState: StateFlow<MlSyncState> = controller.state

    private val _restoreSheet = MutableStateFlow<MlRestoreSheet>(MlRestoreSheet.Hidden)
    internal val restoreSheet: StateFlow<MlRestoreSheet> = _restoreSheet.asStateFlow()

    /** True while a tap is being handed to the controller, so Save and Restore cannot be tapped twice. */
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /** True while a Save tap looks the ML data folder up, before any job exists; the card shows the save's
     *  first step meanwhile ([mlSyncShownState]). */
    private val _preparingSave = MutableStateFlow(false)
    val preparingSave: StateFlow<Boolean> = _preparingSave.asStateFlow()

    /** True while the user is asked whether the ML data folder may be created; nothing runs until they agree. */
    private val _folderPrompt = MutableStateFlow(false)
    val folderPrompt: StateFlow<Boolean> = _folderPrompt.asStateFlow()

    private var listing: Job? = null

    /** The tap being handed to the controller, so Cancel can stop a save still looking up its folder. */
    private var startJob: Job? = null

    /**
     * Starts a save that may not create the ML data folder. The controller looks the folder up first: when
     * there is none yet nothing runs, and the user is only asked whether to create it ([folderPrompt]); every
     * later save goes straight through.
     */
    fun save() = launchStart {
        val userId = currentUser() ?: return@launchStart
        _preparingSave.value = true
        val started = try {
            controller.save(userId, createFolderConfirmed = false)
        } finally {
            _preparingSave.value = false
        }
        if (started == MlSyncStart.FOLDER_MISSING) _folderPrompt.value = true
    }

    /** The user agreed to the folder: the save runs, now allowed to create it. */
    fun confirmCreateFolder() {
        _folderPrompt.value = false
        // The question may have come from a job that found the folder gone; its result is answered now.
        controller.dismissResult()
        launchStart {
            val userId = currentUser() ?: return@launchStart
            controller.save(userId, createFolderConfirmed = true)
        }
    }

    /** The user declined the folder: nothing is created and nothing is saved. */
    fun dismissFolderPrompt() {
        _folderPrompt.value = false
        controller.dismissResult()
    }

    fun dismissResult() = controller.dismissResult()

    /** Stops a save still looking up its folder, and the job. */
    fun cancel() {
        if (_preparingSave.value) startJob?.cancel()
        controller.cancel()
    }

    /** Opens the restore sheet and lists what every device of the account saved. */
    fun openRestore() {
        listing?.cancel()
        _restoreSheet.value = MlRestoreSheet.Loading
        listing = viewModelScope.launch {
            val sheet = try {
                val userId = currentUser()
                if (userId == null) MlRestoreSheet.Hidden else listedSheet(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listing failed: ${e.javaClass.simpleName}")
                MlRestoreSheet.Failed
            }
            if (_restoreSheet.value == MlRestoreSheet.Loading) _restoreSheet.value = sheet
        }
    }

    fun selectRestoreDevice(deviceId: String) {
        _restoreSheet.update { sheet ->
            val device = (sheet as? MlRestoreSheet.Pick)?.devices?.firstOrNull { it.deviceId == deviceId }
            if (sheet is MlRestoreSheet.Pick && device != null) {
                // A newly picked set starts with all its kinds ticked; picking the same one again keeps the ticks.
                if (sheet.selectedId == deviceId) sheet else sheet.copy(selectedId = deviceId, kinds = device.kinds)
            } else {
                sheet
            }
        }
    }

    /** Ticks or unticks [kind] of the picked set's data. */
    fun toggleRestoreKind(kind: MlDriveFileKind) {
        _restoreSheet.update { sheet ->
            if (sheet is MlRestoreSheet.Pick) sheet.copy(kinds = toggledRestoreKinds(sheet, kind)) else sheet
        }
    }

    /**
     * Restores the picked set. The sheet has already slid away; a set with face data on any device but a
     * fresh one asks Merge or Replace first, any other restore starts under Replace.
     */
    fun restoreSelected() {
        val pick = _restoreSheet.value as? MlRestoreSheet.Pick
        _restoreSheet.value = MlRestoreSheet.Hidden
        val device = pick?.devices?.firstOrNull { it.deviceId == pick.selectedId } ?: return
        val files = filesToRestore(device, pick.kinds)
        if (files.isEmpty()) return
        val withFaces = files.any { it.kind == MlDriveFileKind.FACES }
        launchStart {
            val userId = currentUser() ?: return@launchStart
            val needsChoice = withFaces && needsModeChoice(userId)
            val mode = restoreModeWithoutAsking(withFaces, needsChoice)
            if (mode == null) {
                _restoreSheet.value = MlRestoreSheet.ChooseMode(files)
            } else {
                controller.restore(userId, files, mode)
            }
        }
    }

    /** The answer to the Merge or Replace question. */
    fun restoreWith(mode: FaceSnapshotMode) {
        val choice = _restoreSheet.value as? MlRestoreSheet.ChooseMode
        _restoreSheet.value = MlRestoreSheet.Hidden
        val files = choice?.files ?: return
        launchStart {
            val userId = currentUser() ?: return@launchStart
            controller.restore(userId, files, mode)
        }
    }

    fun closeRestore() {
        listing?.cancel()
        listing = null
        _restoreSheet.value = MlRestoreSheet.Hidden
    }

    private suspend fun listedSheet(userId: UserId): MlRestoreSheet =
        when (val listed = controller.listRemote(userId)) {
            is MlDriveResult.Ok -> {
                val devices = remoteDevices(listed.value)
                if (devices.isEmpty()) {
                    MlRestoreSheet.Empty
                } else {
                    val initial = initialRestorePick(devices)
                    val initialKinds = devices.firstOrNull { it.deviceId == initial }?.kinds.orEmpty()
                    MlRestoreSheet.Pick(devices, initial, initialKinds)
                }
            }
            MlDriveResult.FolderMissing -> MlRestoreSheet.Empty
            MlDriveResult.NotCapable, MlDriveResult.Conflict, is MlDriveResult.Failed -> MlRestoreSheet.Failed
        }

    /** The controller's answer; when it cannot be read the user is asked, so a Replace never runs unasked. */
    private suspend fun needsModeChoice(userId: UserId): Boolean = try {
        controller.restoreNeedsModeChoice(userId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "named people unreadable: ${e.javaClass.simpleName}")
        true
    }

    /**
     * Runs [block], one at a time, with [starting] set throughout. What the controller answers needs no
     * handling here beyond a save's missing folder ([save]): a started job shows through [syncState], one
     * already running is on screen already, and an account or device that cannot use Drive hides the section.
     */
    private fun launchStart(block: suspend () -> Unit) {
        if (_starting.value) return
        _starting.value = true
        startJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
            } finally {
                _starting.value = false
            }
        }
    }

    private suspend fun currentUser(): UserId? = accountManager.getPrimaryUserId().first()

    private companion object {
        const val TAG = "MlDriveSync"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
