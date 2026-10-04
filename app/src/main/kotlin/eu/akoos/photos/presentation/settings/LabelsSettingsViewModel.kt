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
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.label.LabelBackupController
import eu.akoos.photos.data.label.LabelBackupRunner
import eu.akoos.photos.data.label.LabelBackupStart
import eu.akoos.photos.data.label.LabelBackupState
import eu.akoos.photos.data.label.LabelCreation
import eu.akoos.photos.data.label.LabelSavedCopy
import eu.akoos.photos.data.label.PhotoLabelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** Whether the Drive section may show: an account ([signedIn]) on a device the Drive copy runs on. */
internal data class LabelBackupAccess(val signedIn: Boolean, val driveAvailable: Boolean)

/** The restore drawer: the listing, then the pick of one saved copy. */
internal sealed interface LabelRestoreSheet {
    data object Hidden : LabelRestoreSheet

    /** The Labels folder is being listed and each copy opened. */
    data object Loading : LabelRestoreSheet

    /** The saved copies, newest first, and the install whose copy is picked. */
    data class Pick(val copies: List<LabelSavedCopy>, val selectedId: String?) : LabelRestoreSheet

    /** Nothing is saved yet. */
    data object Empty : LabelRestoreSheet

    /** The listing failed, or no copy could be read. */
    data object Failed : LabelRestoreSheet
}

/**
 * The Settings Labels page: the account's labels with their photo counts, create / rename / merge / delete, and
 * the Drive copy of them over [LabelBackupController]. The account is read as the outer flow, so a sign-in shows
 * the Drive section in place and a sign-out hides it; it starts hidden, so it never flashes before the account is
 * known. While a save or restore runs the labels are [locked] and the page offers no edit.
 */
@HiltViewModel
class LabelsSettingsViewModel @Inject constructor(
    private val labelRepo: PhotoLabelRepository,
    private val backup: LabelBackupController,
    private val accountManager: AccountManager,
) : ViewModel() {

    /** The labels in manager order, null until the first read lands. */
    val labels: StateFlow<List<LabelUsage>?> = labelRepo.observeLabelUsage()
        .map<List<LabelUsage>, List<LabelUsage>?> { sortLabelsForManager(it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while the labels are saved to or restored from Drive, when they cannot be edited. */
    val locked: StateFlow<Boolean> = labelRepo.observeLocked()
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    internal val backupAccess: StateFlow<LabelBackupAccess> = accountManager.getPrimaryUserId()
        .map { userId -> LabelBackupAccess(signedIn = userId != null, driveAvailable = backup.isAvailable(userId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabelBackupAccess(false, false))

    /** The one save or restore: idle, running with its step, or finished until dismissed. */
    val backupState: StateFlow<LabelBackupState> = backup.state

    /** True while a Save tap looks the Labels folder up, before any job exists. */
    private val _preparingSave = MutableStateFlow(false)
    val preparingSave: StateFlow<Boolean> = _preparingSave.asStateFlow()

    /** True while the user is asked whether the Labels folder may be created; nothing runs until they agree. */
    private val _folderPrompt = MutableStateFlow(false)
    val folderPrompt: StateFlow<Boolean> = _folderPrompt.asStateFlow()

    /** Starts a save that may not create the Labels folder: a missing one only asks ([folderPrompt]). */
    fun saveToDrive() = launchBackup {
        if (_preparingSave.value) return@launchBackup
        val userId = currentUser() ?: return@launchBackup
        _preparingSave.value = true
        val started = try {
            backup.save(userId, createFolderConfirmed = false)
        } finally {
            _preparingSave.value = false
        }
        if (started == LabelBackupStart.FOLDER_MISSING) _folderPrompt.value = true
    }

    /** The user agreed to the folder: the save starts, now allowed to create it. */
    fun confirmCreateFolder() {
        _folderPrompt.value = false
        // The question may have come from a job that found the folder gone; its result is answered now.
        backup.dismissResult()
        launchBackup {
            val userId = currentUser() ?: return@launchBackup
            backup.save(userId, createFolderConfirmed = true)
        }
    }

    /** The user declined the folder: nothing is created and nothing is saved. */
    fun dismissFolderPrompt() {
        _folderPrompt.value = false
        backup.dismissResult()
    }

    private val _restoreSheet = MutableStateFlow<LabelRestoreSheet>(LabelRestoreSheet.Hidden)
    internal val restoreSheet: StateFlow<LabelRestoreSheet> = _restoreSheet.asStateFlow()

    private var listing: Job? = null

    /** Opens the restore drawer and lists the saved copies, each with when it was saved and what it holds. */
    fun openRestore() {
        listing?.cancel()
        _restoreSheet.value = LabelRestoreSheet.Loading
        listing = viewModelScope.launch {
            val sheet = try {
                val userId = currentUser()
                if (userId == null) {
                    LabelRestoreSheet.Hidden
                } else {
                    when (val copies = backup.listCopies(userId)) {
                        is LabelBackupRunner.Copies.Found ->
                            LabelRestoreSheet.Pick(copies.copies, copies.copies.firstOrNull()?.deviceId)
                        LabelBackupRunner.Copies.Empty -> LabelRestoreSheet.Empty
                        LabelBackupRunner.Copies.NotCapable, LabelBackupRunner.Copies.Failed -> LabelRestoreSheet.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listing failed: ${e.javaClass.simpleName}")
                LabelRestoreSheet.Failed
            }
            if (_restoreSheet.value == LabelRestoreSheet.Loading) _restoreSheet.value = sheet
        }
    }

    fun selectCopy(deviceId: String) {
        _restoreSheet.update { sheet ->
            if (sheet is LabelRestoreSheet.Pick && sheet.copies.any { it.deviceId == deviceId }) {
                sheet.copy(selectedId = deviceId)
            } else {
                sheet
            }
        }
    }

    /** Restores the picked copy. The drawer has already slid away. */
    fun restoreSelected() {
        val pick = _restoreSheet.value as? LabelRestoreSheet.Pick
        _restoreSheet.value = LabelRestoreSheet.Hidden
        val copy = pick?.copies?.firstOrNull { it.deviceId == pick.selectedId } ?: return
        launchBackup {
            val userId = currentUser() ?: return@launchBackup
            backup.restore(userId, copy)
        }
    }

    fun closeRestore() {
        listing?.cancel()
        listing = null
        _restoreSheet.value = LabelRestoreSheet.Hidden
    }

    /** Stops the running save or restore. */
    fun cancelBackup() = backup.cancel()

    fun dismissBackupResult() = backup.dismissResult()

    /** Creates the label [text] with no photo yet. [onDone] gets how it ended, null when the write failed. */
    fun create(text: String, onDone: (LabelCreation?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                labelRepo.createLabel(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "label create failed: ${e.message}")
                null
            }
            onDone(result)
        }
    }

    /** Renames [from] to [to] on every photo, merging into [to] when that label already exists. */
    fun rename(from: String, to: String) = write { labelRepo.renameLabel(from, to) }

    /** Takes [label] off every photo and forgets it. */
    fun delete(label: String) = write { labelRepo.deleteLabel(label) }

    /** Takes every label of [labels] off every photo and forgets them. */
    fun deleteMany(labels: Collection<String>) = write { labelRepo.deleteLabels(labels) }

    /** Runs [block], one tap at a time. */
    private fun launchBackup(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "backup start failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun currentUser(): UserId? = accountManager.getPrimaryUserId().first()

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "label edit failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "LabelsSettingsVM"
    }
}
