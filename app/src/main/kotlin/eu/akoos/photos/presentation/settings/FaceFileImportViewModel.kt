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

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.mlsync.FACE_WALK_IDLE_TIMEOUT_MS
import eu.akoos.photos.data.mlsync.MlSyncFacePause
import eu.akoos.photos.data.mlsync.faceIndexingPausedByUser
import eu.akoos.photos.data.mlsync.faceWalkResumes
import eu.akoos.photos.data.mlsync.isStorageFull
import eu.akoos.photos.domain.usecase.FaceIndexImportOutcome
import eu.akoos.photos.domain.usecase.ImportFaceIndexUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Loads a face file saved with an earlier version's "Export face index" (`photosforproton-faces.ppfi`), or
 * a sealed face snapshot saved by hand, into the account. Saving and restoring through Drive replaced the
 * export, but a file made before that is the only copy some people hold of faces they named, so it can
 * still be brought back.
 */
@HiltViewModel
class FaceFileImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val importFaceIndexUseCase: ImportFaceIndexUseCase,
    private val faceIndexingScheduler: FaceIndexingScheduler,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    /** What the last import did, until the screen has shown it. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    private val _inProgress = MutableStateFlow(false)

    /** True while an import runs, so the row reads as busy and cannot be tapped again. */
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    /**
     * Reads the face file at [uri] and folds it into the account, then regroups so the imported faces form
     * people.
     *
     * The face walk is stood down around the import the way a restore from Drive does it: paused, as a hold
     * ([MlSyncFacePause]) so a pause a killed import leaves never reads as the user's, then waited out, and
     * the import itself runs inside the scheduler's import fence, so no pass re-detects the file's photos and
     * writes over their faces and names. The regroup comes next, awaited, and the walk is handed back last,
     * unless the user had paused it before the import ([faceWalkResumes]).
     */
    fun import(uri: Uri) {
        if (_inProgress.value) return
        viewModelScope.launch {
            _inProgress.value = true
            try {
                val userId = accountManager.getPrimaryUserId().firstOrNull() ?: run {
                    _message.value = context.getString(R.string.settings_ai_transfer_failed)
                    return@launch
                }
                val pause = MlSyncFacePause.read(context)
                val pausedByUser = faceIndexingPausedByUser(pause?.switchOn, pause?.held)
                val held = MlSyncFacePause.hold(context)
                try {
                    if (held) faceIndexingScheduler.setPaused(true)
                    withTimeoutOrNull(FACE_WALK_IDLE_TIMEOUT_MS) { faceIndexingScheduler.awaitIdle() }
                    val result = faceIndexingScheduler.withImportFence {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { importFaceIndexUseCase(it) }
                        }.getOrElse { if (isStorageFull(it)) FaceIndexImportOutcome.NotEnoughSpace else null }
                    }
                    // Forced and awaited, so the people are rebuilt before the walk comes back, even while the
                    // user keeps it paused. A names-only import has no faces to group until the scan finds them.
                    if (result is FaceIndexImportOutcome.Success) faceIndexingScheduler.regroupNow(userId)
                    _message.value = when (result) {
                        is FaceIndexImportOutcome.Success ->
                            context.getString(R.string.settings_ai_import_done, result.faces, result.people)
                        // A different model: the names are staged, and the scan puts them back on this device's
                        // own faces by where each one sat.
                        is FaceIndexImportOutcome.ReattachScheduled ->
                            context.getString(R.string.settings_ai_import_reattach, result.people)
                        // A wrong account is a mismatch the user can act on, unlike the generic failure an
                        // unreadable or missing file (a null result) gets.
                        FaceIndexImportOutcome.WrongAccount ->
                            context.getString(R.string.settings_ai_import_wrong_account)
                        FaceIndexImportOutcome.NotEnoughSpace ->
                            context.getString(R.string.settings_ml_restore_no_space)
                        else -> context.getString(R.string.settings_ai_transfer_failed)
                    }
                } finally {
                    // Handed back however the import ended, a cancel included. The imported photos are marked
                    // scanned, so the walk skips them and only picks up what is new.
                    withContext(NonCancellable) {
                        if (faceWalkResumes(held, pausedByUser)) {
                            MlSyncFacePause.lift(context)
                            if (accountManager.getPrimaryUserId().firstOrNull() == userId) {
                                faceIndexingScheduler.requestIndex(userId)
                            }
                        }
                    }
                }
            } finally {
                _inProgress.value = false
            }
        }
    }
}
