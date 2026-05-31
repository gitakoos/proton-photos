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

package eu.akoos.photos.presentation.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drains the upload pipeline's pending delete queue through a foreground OS consent
 * dialog. The upload worker on Android 11+ cannot directly delete foreign owned
 * camera roll items (`contentResolver.delete()` returns 0 rows for items it does
 * not own); it queues the URIs into [SettingsKeys.PENDING_DELETE_URIS] instead, and
 * this handler runs them through [MediaStore.createDeleteRequest] inside an
 * Activity context where the OS will surface the system delete dialog the user
 * needs to accept.
 *
 * Mounted once at the top of the NavGraph so the handler is alive for the entire
 * authenticated session. It is intentionally side effect only: the handler renders
 * no UI of its own, only the OS dialog when the queue stabilizes with items in it.
 */
@HiltViewModel
class PendingDeleteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
) : ViewModel() {

    val pendingUris: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /**
     * The user accepted the OS consent dialog for [uris]. The files are already gone
     * from MediaStore at this point (the system handled the actual delete), so all we
     * do here is collapse the SyncState rows to CLOUD_ONLY and pop the URIs from the
     * pending queue so the handler doesn't re-prompt.
     */
    fun onConsentGranted(uris: Set<String>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching { syncStateRepo.updateStatusAndDeleteLocal(uri, SyncStatus.CLOUD_ONLY) }
            }
            context.settingsDataStore.edit { p ->
                val remaining = (p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()) - uris
                p[SettingsKeys.PENDING_DELETE_URIS] = remaining
            }
        }
    }
}

@Composable
fun PendingDeleteHandler(
    viewModel: PendingDeleteViewModel = hiltViewModel(),
) {
    // The OS consent dialog is API 30+. Below R the worker's direct delete already
    // worked, so we should never see entries piling up here.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by viewModel.pendingUris.collectAsStateWithLifecycle()
    // Snapshot of the batch we last handed to the OS dialog. Held so the
    // ActivityResult callback knows which URIs to mark CLOUD_ONLY without racing the
    // pendingUris flow (which can have additional items added by an in flight upload
    // while the user is reading the system dialog).
    var lastBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inFlight by remember { mutableStateOf(false) }
    // High water mark: only re prompt when the queue grows beyond what we already
    // asked about. Survives configuration changes via rememberSaveable so a rotation
    // mid prompt doesn't re fire the dialog.
    var lastPromptedSize by rememberSaveable { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        inFlight = false
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onConsentGranted(lastBatch)
            // Queue drained for this batch — next prompt only fires if it grows again.
            lastPromptedSize = 0
        }
        // Cancel / deny: leave the queue alone so the next size growth re prompts.
    }

    LaunchedEffect(pending.size, inFlight) {
        if (inFlight) return@LaunchedEffect
        if (pending.isEmpty()) {
            lastPromptedSize = 0
            return@LaunchedEffect
        }
        if (pending.size <= lastPromptedSize) return@LaunchedEffect
        // Settle window: an active sync emits per file edits in quick succession.
        // Wait a few seconds so we batch the whole run into one OS dialog instead of
        // popping a dialog after every file. The LaunchedEffect re launches on size
        // change, which cancels this delay — meaning each new item resets the timer.
        delay(3_000L)
        val snapshot = viewModel.pendingUris.value
        if (snapshot.isEmpty()) return@LaunchedEffect
        val uris: List<Uri> = snapshot.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (uris.isEmpty()) return@LaunchedEffect
        inFlight = true
        lastBatch = snapshot
        lastPromptedSize = snapshot.size
        scope.launch {
            runCatching {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure { inFlight = false }
        }
    }
}
