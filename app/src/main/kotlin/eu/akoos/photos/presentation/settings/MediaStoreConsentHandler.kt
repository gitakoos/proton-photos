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
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.domain.usecase.nextMediaStoreRequestBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a [MediaStoreConsentHandler] builds the system consent request. Trash and permanent delete
 * are hard-separated sinks: free-up must use [PermanentDelete]; delete-after-backup uses [Trash].
 */
enum class MediaStoreConsentMode {
    Trash,
    PermanentDelete,
}

/**
 * Whether [MediaStoreConsentHandler] should open a new system consent dialog.
 *
 * - [MediaStoreConsentMode.Trash]: growth-based re-prompt — only when [pendingSize] exceeds
 *   [lastPromptedSize]. Deny does **not** permanently stop trash; a later size increase re-prompts.
 * - [MediaStoreConsentMode.PermanentDelete]: prompt while the queue is non-empty unless the user
 *   already denied ([denyStopped]) or a dialog is [inFlight].
 */
fun shouldPromptMediaStoreConsent(
    mode: MediaStoreConsentMode,
    pendingSize: Int,
    lastPromptedSize: Int,
    inFlight: Boolean,
    denyStopped: Boolean,
): Boolean {
    if (inFlight) return false
    if (pendingSize <= 0) return false
    return when (mode) {
        MediaStoreConsentMode.Trash -> pendingSize > lastPromptedSize
        MediaStoreConsentMode.PermanentDelete -> !denyStopped
    }
}

/**
 * How [MediaStoreConsentHandler] reacts when `createTrashRequest` / `createDeleteRequest`
 * or `launcher.launch` fails (distinct from the user denying the system dialog).
 *
 * PermanentDelete retries the same batch once, then stops prompting and invokes its technical
 * failure callback. It must **not** report a technical failure as user denial.
 */
enum class MediaStoreConsentLaunchFailureAction {
    /** PermanentDelete still has a launch retry available for this exact batch. */
    Retry,
    /** PermanentDelete exhausted this batch's launch retry budget. */
    NotifyFailure,
    /** Trash, or a PermanentDelete batch whose exhausted failure was already reported. */
    None,
}

/**
 * Launch-failure accounting independent of `lastAttemptedBatch`.
 *
 * Clearing the attempted-batch launch guard to permit a retry must not make the same batch look
 * new and reset its failure count.
 */
data class MediaStoreConsentRetryState(
    val failureBatch: Set<String> = emptySet(),
    val failureCount: Int = 0,
)

data class MediaStoreConsentFailureTransition(
    val state: MediaStoreConsentRetryState,
    val action: MediaStoreConsentLaunchFailureAction,
)

/**
 * Records one request-creation or launcher failure.
 *
 * A genuinely different PermanentDelete batch starts a fresh budget. With the default one retry,
 * the first failure returns [MediaStoreConsentLaunchFailureAction.Retry] and the second returns
 * [MediaStoreConsentLaunchFailureAction.NotifyFailure].
 */
fun recordMediaStoreConsentLaunchFailure(
    mode: MediaStoreConsentMode,
    batch: Set<String>,
    state: MediaStoreConsentRetryState,
    maxRetries: Int = 1,
): MediaStoreConsentFailureTransition {
    require(maxRetries >= 0) { "maxRetries must be non-negative, was $maxRetries" }
    if (mode != MediaStoreConsentMode.PermanentDelete) {
        return MediaStoreConsentFailureTransition(
            state = state,
            action = MediaStoreConsentLaunchFailureAction.None,
        )
    }
    val failureCount = if (batch == state.failureBatch) state.failureCount + 1 else 1
    val nextState = MediaStoreConsentRetryState(
        failureBatch = batch.toSet(),
        failureCount = failureCount,
    )
    val action = when {
        failureCount <= maxRetries -> MediaStoreConsentLaunchFailureAction.Retry
        failureCount == maxRetries + 1 -> MediaStoreConsentLaunchFailureAction.NotifyFailure
        else -> MediaStoreConsentLaunchFailureAction.None
    }
    return MediaStoreConsentFailureTransition(nextState, action)
}

/**
 * Parameterised MediaStore consent prompt: watches [pendingUris], builds a request with [mode],
 * and reports grant/deny. Callers supply their own URI source and callbacks so trash
 * (`PENDING_DELETE_URIS`) and free-up permanent delete never share a sink.
 *
 * Trash preserves growth-based re-prompting with a [rememberSaveable] int high-water mark only —
 * never saveable URI sets (TransactionTooLarge risk). Permanent delete drains bounded batches;
 * deny stops further batches until the sink is emptied. Its [prepareBatch] must strictly verify
 * that each URI still has an active cloud copy; the safe default permits only Trash identity.
 */
@Composable
fun MediaStoreConsentHandler(
    pendingUris: StateFlow<Set<String>>,
    mode: MediaStoreConsentMode,
    settleDelayMs: Long = 3_000L,
    prepareBatch: suspend (Set<String>) -> Set<String> = { batch ->
        check(mode == MediaStoreConsentMode.Trash) {
            "PermanentDelete requires a strict prepareBatch verifier"
        }
        batch
    },
    onGranted: (Set<String>) -> Unit,
    onDenied: (Set<String>) -> Unit = {},
    onLaunchFailed: (Set<String>) -> Unit = {},
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by pendingUris.collectAsStateWithLifecycle()
    var lastBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inFlight by remember { mutableStateOf(false) }
    // Not saveable: URI sets must not ride in the Bundle across rotation.
    var lastAttemptedBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    // PermanentDelete only: deny stops chunk advance until the sink drains.
    var denyStopped by remember { mutableStateOf(false) }
    // Separate from lastAttemptedBatch: clearing the launch guard must not reset retry accounting.
    var retryState by remember { mutableStateOf(MediaStoreConsentRetryState()) }
    // Trash only: int high-water for growth re-prompt; survives config change.
    var lastPromptedSize by rememberSaveable { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        inFlight = false
        if (result.resultCode == Activity.RESULT_OK) {
            retryState = MediaStoreConsentRetryState()
            if (mode == MediaStoreConsentMode.Trash) {
                // Batch settled — allow remaining / next growth to prompt again.
                lastPromptedSize = 0
            }
            onGranted(lastBatch)
        } else {
            if (mode == MediaStoreConsentMode.PermanentDelete) {
                denyStopped = true
            }
            // Trash: leave lastPromptedSize so only a later size increase re-prompts.
            onDenied(lastBatch)
        }
    }

    LaunchedEffect(pending.size, inFlight, denyStopped, lastPromptedSize, mode) {
        if (pending.isEmpty()) {
            lastAttemptedBatch = emptySet()
            denyStopped = false
            retryState = MediaStoreConsentRetryState()
            lastPromptedSize = 0
            return@LaunchedEffect
        }
        if (!shouldPromptMediaStoreConsent(
                mode = mode,
                pendingSize = pending.size,
                lastPromptedSize = lastPromptedSize,
                inFlight = inFlight,
                denyStopped = denyStopped,
            )
        ) {
            return@LaunchedEffect
        }
        val batch = nextMediaStoreRequestBatch(pending).toSet()
        if (batch.isEmpty()) return@LaunchedEffect
        if (mode == MediaStoreConsentMode.PermanentDelete && batch == lastAttemptedBatch) {
            return@LaunchedEffect
        }
        delay(settleDelayMs)
        val snapshot = pendingUris.value
        if (snapshot.isEmpty()) return@LaunchedEffect
        if (!shouldPromptMediaStoreConsent(
                mode = mode,
                pendingSize = snapshot.size,
                lastPromptedSize = lastPromptedSize,
                inFlight = false,
                denyStopped = denyStopped,
            )
        ) {
            return@LaunchedEffect
        }
        val currentBatch = nextMediaStoreRequestBatch(snapshot).toSet()
        if (currentBatch.isEmpty()) return@LaunchedEffect
        if (mode == MediaStoreConsentMode.PermanentDelete && currentBatch == lastAttemptedBatch) {
            return@LaunchedEffect
        }
        inFlight = true
        // Guard this offered batch while verification/request creation is in progress.
        lastAttemptedBatch = currentBatch
        if (mode == MediaStoreConsentMode.Trash) {
            lastPromptedSize = snapshot.size
        }
        scope.launch {
            val prepared = runCatching {
                prepareBatch(currentBatch)
            }.getOrElse {
                inFlight = false
                if (mode == MediaStoreConsentMode.PermanentDelete) {
                    denyStopped = true
                }
                onLaunchFailed(currentBatch)
                return@launch
            }
            // A hook may only filter the offered batch; it cannot add URIs or revive queue entries.
            val requestBatch = currentBatch.filterTo(linkedSetOf()) { uri ->
                uri in prepared && uri in pendingUris.value
            }
            if (requestBatch.isEmpty()) {
                inFlight = false
                return@launch
            }
            val uris: List<Uri> = requestBatch.mapNotNull {
                runCatching { Uri.parse(it) }.getOrNull()
            }
            if (uris.isEmpty()) {
                inFlight = false
                if (mode == MediaStoreConsentMode.PermanentDelete) {
                    denyStopped = true
                }
                onLaunchFailed(requestBatch)
                return@launch
            }
            lastBatch = requestBatch
            runCatching {
                val resolver = context.contentResolver
                val pi = withContext(Dispatchers.IO) {
                    when (mode) {
                        MediaStoreConsentMode.Trash ->
                            android.provider.MediaStore.createTrashRequest(resolver, uris, true)
                        MediaStoreConsentMode.PermanentDelete ->
                            android.provider.MediaStore.createDeleteRequest(resolver, uris)
                    }
                }
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure {
                inFlight = false
                val transition = recordMediaStoreConsentLaunchFailure(
                    mode = mode,
                    batch = requestBatch,
                    state = retryState,
                )
                retryState = transition.state
                when (transition.action) {
                    MediaStoreConsentLaunchFailureAction.Retry -> {
                        lastAttemptedBatch = emptySet()
                    }
                    MediaStoreConsentLaunchFailureAction.NotifyFailure -> {
                        denyStopped = true
                        onLaunchFailed(requestBatch)
                    }
                    MediaStoreConsentLaunchFailureAction.None -> Unit
                }
            }
        }
    }
}
