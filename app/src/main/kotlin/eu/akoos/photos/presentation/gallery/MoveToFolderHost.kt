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

package eu.akoos.photos.presentation.gallery

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import kotlinx.coroutines.flow.SharedFlow

/**
 * Drives a screen's "Move to folder" UI: the target picker, the new-folder name dialog, the system
 * write-consent launcher a foreign-file move needs, and the completion snackbar, the timeline's exact
 * wiring, reusable by any selection screen. Renders the picker only while [show].
 *
 * Wired through plain values and lambdas rather than the controller itself so the owning ViewModel
 * keeps the [kotlinx.coroutines.CoroutineScope] the move work must run on: [onPick] moves the current
 * selection into a folder, [onCreate] moves it into a freshly named one, [onGranted]/[onClear] answer
 * the consent dialog, and the move-completion stream arrives on [moveConfirmation].
 */
@Composable
fun MoveToFolderHost(
    targetFolders: List<DeviceFolderChoice>,
    show: Boolean,
    pendingMoveIntent: IntentSender?,
    moveConfirmation: SharedFlow<String>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onGranted: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var showNameDialog by remember { mutableStateOf(false) }

    // Moving a file the app does not own needs a one-shot system write consent; RESULT_OK replays the
    // move on the deferred URIs, cancel drops them. The intent is an IntentSender from the use case.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onGranted() else onClear()
    }
    LaunchedEffect(pendingMoveIntent) {
        val sender = pendingMoveIntent ?: return@LaunchedEffect
        runCatching { permissionLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .onFailure { onClear() }
    }

    // A completed move confirms where the files landed.
    val movedToFolderTpl = stringResource(R.string.moved_to_folder)
    LaunchedEffect(Unit) {
        moveConfirmation.collect { folderName ->
            snackbarHostState.showSnackbar(movedToFolderTpl.format(folderName))
        }
    }

    if (show) {
        MoveToFolderSheet(
            folders = targetFolders,
            onPick = { name ->
                onPick(name)
                onDismiss()
            },
            onNewFolder = { showNameDialog = true },
            onDismiss = onDismiss,
        )
    }
    if (showNameDialog) {
        NewFolderNameDialog(
            onConfirm = { name ->
                onCreate(name)
                showNameDialog = false
                onDismiss()
            },
            onDismiss = { showNameDialog = false },
        )
    }
}
