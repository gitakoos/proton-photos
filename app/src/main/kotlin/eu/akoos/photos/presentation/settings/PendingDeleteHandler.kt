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
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/**
 * Checks the stored delete queue again right before the system trash dialog is asked for. The queue
 * can wait for days, and in that time the Drive copy may have been trashed elsewhere or the device
 * file edited by another app, which would leave the trash dialog removing the only copy.
 */
class PendingDeleteVerifier @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val freeUpSpace: FreeUpSpaceUseCase,
) {
    /** [confirmed] may go into the trash request; [failed] no longer may and leave the queue. */
    data class Outcome(val confirmed: Set<String>, val failed: Set<String>)

    /**
     * Splits [uris] into the ones whose device copy is still backed up and unchanged under [userId]
     * (a SYNCED row of that account with a backup stamp, an active Drive link, and the device file
     * as backed up; [FreeUpSpaceUseCase.confirmForDeletion]) and the rest. A Drive lookup that fails
     * throws, so the caller asks for nothing on an unconfirmed answer.
     */
    suspend fun verify(userId: UserId, uris: Set<String>): Outcome {
        if (uris.isEmpty()) return Outcome(emptySet(), emptySet())
        val paired = syncStateRepo.cloudPairedLinkIds(userId, uris.toList()).keys
        val rows = syncStateRepo.getByUris(paired.toList())
            .filter { it.status == SyncStatus.SYNCED && it.backedUpAtMs != null && it.localUri in uris }
        val confirmed = freeUpSpace.confirmForDeletion(userId, rows).mapTo(HashSet()) { it.localUri }
        return Outcome(confirmed, uris - confirmed)
    }
}

/**
 * Drains the upload pipeline's pending delete queue through a foreground OS consent
 * dialog. The upload worker on Android 11+ cannot directly delete foreign owned
 * camera roll items (`contentResolver.delete()` returns 0 rows for items it does
 * not own); it queues the URIs into [SettingsKeys.PENDING_DELETE_URIS] instead, and
 * this handler runs them through [MediaStore.createTrashRequest] inside an
 * Activity context where the OS will surface the system trash dialog the user
 * needs to accept.
 *
 * Mounted once at the top of the NavGraph so the handler is alive for the entire
 * authenticated session. It is intentionally side effect only: the handler renders
 * no UI of its own, only the OS dialog when the queue stabilizes with items in it.
 * The dialog opens above the app lock, and "Allow" trashes the photos, so it waits
 * until the lock is open; the queue keeps meanwhile.
 */
@HiltViewModel
class PendingDeleteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
    private val verifier: PendingDeleteVerifier,
    private val accountManager: AccountManager,
) : ViewModel() {

    val pendingUris: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /**
     * [uris] checked again right now ([PendingDeleteVerifier]); null when there is no account or Drive
     * could not answer, and then nothing is asked for and the queue stays as it is.
     */
    suspend fun verify(uris: Set<String>): PendingDeleteVerifier.Outcome? {
        val userId = accountManager.getPrimaryUserId().first() ?: return null
        return try {
            verifier.verify(userId, uris)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Takes [uris], which no longer passed the check, out of the queue so they are never asked for. */
    fun dropFromQueue(uris: Set<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                context.settingsDataStore.edit { p ->
                    p[SettingsKeys.PENDING_DELETE_URIS] = (p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()) - uris
                }
            }
        }
    }

    /**
     * The user accepted the OS consent dialog for [uris], the URIs that passed [verify] just before it.
     * The system has already moved the files to the trash at this point, which takes them out of
     * MediaStore's normal queries, so all we do here is collapse the SyncState rows to CLOUD_ONLY and
     * pop the URIs from the pending queue so the handler doesn't re-prompt.
     */
    fun onConsentGranted(uris: Set<String>) {
        viewModelScope.launch {
            runCatching { syncStateRepo.updateStatuses(uris, SyncStatus.CLOUD_ONLY) }
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
    val appLock = LocalAppLock.current
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
        // The batch is read once the lock is open, checked against Drive and the device files right then,
        // and launched in the same turn as a second look at the lock. Only what passed goes into the request.
        appLock.runWhenOpen {}
        val snapshot = viewModel.pendingUris.value
        if (snapshot.isEmpty()) return@LaunchedEffect
        val outcome = viewModel.verify(snapshot) ?: return@LaunchedEffect
        appLock.runWhenOpen {
            val verified = outcome.confirmed
            val uris: List<Uri> = verified.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            if (uris.isNotEmpty()) {
                inFlight = true
                lastBatch = verified
                lastPromptedSize = snapshot.size - outcome.failed.size
                runCatching {
                    val pi = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                    launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }.onFailure { inFlight = false }
            }
            // After the launch, so the queue shrinking does not cancel this effect before it.
            viewModel.dropFromQueue(outcome.failed)
        }
    }
}
