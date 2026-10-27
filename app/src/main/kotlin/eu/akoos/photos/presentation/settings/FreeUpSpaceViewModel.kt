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
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.freeup.cancelFreeUpActionRequiredNotification
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.repository.FreeUpPendingDeleteQueue
import eu.akoos.photos.domain.repository.isFreeUpPendingOfferStale
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.worker.FreeUpPipelineWorker
import eu.akoos.photos.worker.ManualFreeUpWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Free up space screen: lists reclaimable candidates, enqueues/cancels [ManualFreeUpWorker]
 * (shared [FreeUpPipelineWorker]), and observes WorkInfo phase/counts. The worker owns verification,
 * reclaim, and consent handoff; this ViewModel renders its state.
 */
@HiltViewModel
class FreeUpSpaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val localRepo: eu.akoos.photos.domain.repository.LocalMediaRepository,
    private val accountManager: AccountManager,
    private val pendingDeleteBridge: FreeUpPendingDeleteQueue,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val candidates: List<SyncState> = emptyList(),
        val phase: FreeUpSpacePhase = FreeUpSpacePhase.Idle,
        val checked: Int = 0,
        val checkTotal: Int = 0,
        val processed: Int = 0,
        val removed: Int = 0,
        val deletionTotal: Int = 0,
        /** Non-zero when WorkInfo reports awaiting_consent; drives awaiting-consent copy. */
        val awaitingConsentCount: Int = 0,
        val message: String? = null,
        val reclaimableBytes: Long = 0L,
    ) {
        val isEmpty: Boolean get() = !loading && candidates.isEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** User-initiated stop; cleared by [start] / [continueWork]. Does not freeze WorkInfo counts. */
    private var stoppedLatch = false

    /** Tracks WorkInfo id+state so Done/Error messages fire once per terminal transition. */
    private var lastTerminalWorkKey: String? = null

    /**
     * Work ids observed while active during this ViewModel lifetime. Terminal Done/Error publishes
     * only for these — retained unique-work history must not replay on route reopen.
     */
    private val observedActiveWorkIds = mutableSetOf<String>()

    /**
     * Cumulative frees for the current Start→Done session: direct worker removals plus every
     * granted consent chunk.
     */
    private var sessionFreed = 0

    init {
        load()
        observeWorker()
        observeConsentSettlement()
    }

    fun load() {
        viewModelScope.launch {
            refreshFromStore()
        }
    }

    /**
     * Refresh candidates from the store. While Checking or Freeing, preserves the live grid
     * (candidates + reclaimableBytes) so an empty mid-run query cannot flash "nothing to free".
     */
    private suspend fun refreshFromStore() {
        val userId = accountManager.getPrimaryUserId().first() ?: run {
            _uiState.update { it.copy(loading = false, candidates = emptyList()) }
            return
        }
        _uiState.update { it.copy(loading = true) }
        val rows = freeUpSpace.candidates(userId, Long.MAX_VALUE, protectDownloaded = false)
        val bytes = totalBytes(rows)
        _uiState.update {
            val liveGrid = it.phase == FreeUpSpacePhase.Checking ||
                it.phase == FreeUpSpacePhase.Freeing
            it.copy(
                loading = false,
                candidates = if (liveGrid) it.candidates else rows,
                reclaimableBytes = if (liveGrid) it.reclaimableBytes else bytes,
            )
        }
    }

    /**
     * Enqueue the manual pipeline. No-ops while Checking/Freeing, or while a **fresh** consent
     * chunk is queued. Stale offers are cleared so Continue/Start can re-verify.
     */
    fun start() {
        if (_uiState.value.phase in setOf(
                FreeUpSpacePhase.Checking,
                FreeUpSpacePhase.Freeing,
            )
        ) return
        if (hasFreshPendingConsent()) return
        stoppedLatch = false
        sessionFreed = 0
        _uiState.update {
            it.copy(
                phase = FreeUpSpacePhase.Checking,
                checked = 0,
                checkTotal = 0,
                processed = 0,
                removed = 0,
                deletionTotal = 0,
                awaitingConsentCount = 0,
                message = null,
            )
        }
        ManualFreeUpWorker.enqueue(context)
    }

    /** Resume after Stopped (including awaiting-consent Continue when the queue is empty/stale). */
    fun continueWork() {
        when (_uiState.value.phase) {
            FreeUpSpacePhase.Stopped -> {
                if (hasFreshPendingConsent()) return
                stoppedLatch = false
                ManualFreeUpWorker.enqueue(context)
            }
            else -> Unit
        }
    }

    /** Cancel manual unique work only. */
    fun stop() {
        when (_uiState.value.phase) {
            FreeUpSpacePhase.Checking,
            FreeUpSpacePhase.Freeing,
            -> {
                stoppedLatch = true
                ManualFreeUpWorker.cancel(context)
                _uiState.update { it.copy(phase = FreeUpSpacePhase.Stopped) }
                load()
            }
            else -> Unit
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    /**
     * True when the consent queue holds a non-stale offer. Clears stale offers via
     * [FreeUpPendingDeleteQueue.settleAllPending] so Start/Continue can re-run the pipeline.
     */
    private fun hasFreshPendingConsent(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (pendingDeleteBridge.uris.value.isEmpty()) return false
        if (isFreeUpPendingOfferStale(pendingDeleteBridge.offeredAtMs, nowMs)) {
            pendingDeleteBridge.settleAllPending(granted = false)
            cancelFreeUpActionRequiredNotification(context)
            return false
        }
        return true
    }

    private fun observeWorker() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(ManualFreeUpWorker.UNIQUE_NAME)
                .collect { infos ->
                    val kinds = infos.map { info ->
                        when (info.state) {
                            WorkInfo.State.RUNNING,
                            WorkInfo.State.ENQUEUED,
                            WorkInfo.State.BLOCKED,
                            -> FreeUpWorkInfoKind.Active
                            WorkInfo.State.SUCCEEDED,
                            WorkInfo.State.FAILED,
                            WorkInfo.State.CANCELLED,
                            -> FreeUpWorkInfoKind.Terminal
                        }
                    }
                    val preferredIndex = selectPreferredFreeUpWorkInfoIndex(kinds)
                    val info = preferredIndex?.let { infos.getOrNull(it) }
                    val workState = info?.state
                    val running = workState == WorkInfo.State.RUNNING ||
                        workState == WorkInfo.State.ENQUEUED ||
                        workState == WorkInfo.State.BLOCKED
                    val succeeded = workState == WorkInfo.State.SUCCEEDED
                    val failed = workState == WorkInfo.State.FAILED
                    val cancelled = workState == WorkInfo.State.CANCELLED

                    val workId = info?.id?.toString()
                    if (running && workId != null) {
                        observedActiveWorkIds.add(workId)
                    }

                    val progress = info?.progress
                    val output = info?.outputData
                    val workerPhase = workString(progress, output, FreeUpPipelineWorker.KEY_PHASE)
                    val processed = workInt(progress, output, FreeUpPipelineWorker.KEY_PROCESSED)
                    val total = workInt(progress, output, FreeUpPipelineWorker.KEY_TOTAL)
                    val removed = workInt(progress, output, FreeUpPipelineWorker.KEY_REMOVED)
                    val needsPermission = workInt(
                        progress,
                        output,
                        FreeUpPipelineWorker.KEY_NEEDS_PERMISSION_COUNT,
                    )

                    val current = _uiState.value
                    val terminalKey = info?.let { "${it.id}:$workState" }
                    val observedActive = shouldPublishFreeUpTerminal(workId, observedActiveWorkIds)
                    val isNewTerminal = terminalKey != null &&
                        terminalKey != lastTerminalWorkKey &&
                        (succeeded || failed) &&
                        observedActive
                    if (isNewTerminal) lastTerminalWorkKey = terminalKey

                    val awaitingConsent = workerPhase == FreeUpPipelineWorker.PHASE_AWAITING_CONSENT ||
                        needsPermission > 0
                    val preRAllSkipped = isNewTerminal &&
                        succeeded &&
                        !awaitingConsent &&
                        removed == 0 &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                        (current.checkTotal > 0 || current.candidates.isNotEmpty())
                    // Publish Done for every successful terminal observed this lifetime, including
                    // successful zero-removal (refresh + "nothing removed" copy).
                    val shouldPublishDone = isNewTerminal &&
                        succeeded &&
                        !awaitingConsent
                    val doneMessage = shouldPublishDone ||
                        (current.message != null && current.phase == FreeUpSpacePhase.Done)

                    val phase = deriveFreeUpSpaceState(
                        workRunning = running,
                        workSucceeded = succeeded,
                        workFailed = failed,
                        doneMessage = doneMessage && !running,
                        workerPhase = workerPhase,
                        stopped = stoppedLatch,
                        workCancelled = cancelled,
                    )

                    _uiState.update { state ->
                        var next = state.copy(
                            phase = phase,
                            awaitingConsentCount = if (awaitingConsent) {
                                needsPermission.coerceAtLeast(state.awaitingConsentCount)
                            } else if (phase == FreeUpSpacePhase.Stopped && stoppedLatch) {
                                state.awaitingConsentCount
                            } else {
                                0
                            },
                        )
                        when (workerPhase) {
                            FreeUpPipelineWorker.PHASE_DELETING,
                            FreeUpPipelineWorker.PHASE_AWAITING_CONSENT,
                            -> {
                                next = next.copy(
                                    processed = processed,
                                    removed = removed,
                                    deletionTotal = total,
                                )
                            }
                            else -> {
                                // verifying / enqueued / unknown — drive the check progress bar
                                if (running || succeeded || failed) {
                                    next = next.copy(checked = processed, checkTotal = total)
                                }
                            }
                        }
                        when {
                            shouldPublishDone -> {
                                sessionFreed = sessionFreed.coerceAtLeast(removed)
                                next.copy(
                                    phase = FreeUpSpacePhase.Done,
                                    message = when {
                                        preRAllSkipped ->
                                            context.getString(R.string.free_up_skipped_pre_r)
                                        else -> freedMessage(sessionFreed)
                                    },
                                    awaitingConsentCount = 0,
                                )
                            }
                            isNewTerminal && failed -> next.copy(
                                phase = FreeUpSpacePhase.Error,
                                message = context.getString(
                                    R.string.settings_free_up_error,
                                    "",
                                ),
                            )
                            isNewTerminal && succeeded && awaitingConsent -> {
                                // Seed session with direct removals before consent chunks accumulate.
                                sessionFreed = removed
                                next.copy(
                                    phase = FreeUpSpacePhase.Stopped,
                                    awaitingConsentCount = needsPermission.coerceAtLeast(1),
                                )
                            }
                            else -> next
                        }
                    }

                    if (shouldPublishDone) {
                        refreshFromStore()
                    }
                }
        }
    }

    /**
     * Reconcile typed MediaStore consent outcomes when not mid pipeline run.
     * Grant: refresh store; Done when nothing left, else Stopped for further Continue.
     * Deny / technical clear: Stopped (or Idle), refresh; do not auto-relaunch consent.
     */
    private fun observeConsentSettlement() {
        viewModelScope.launch {
            pendingDeleteBridge.settled.collect { settlement ->
                if (pendingDeleteBridge.uris.value.isEmpty()) {
                    cancelFreeUpActionRequiredNotification(context)
                }
                val phase = _uiState.value.phase
                if (phase == FreeUpSpacePhase.Checking ||
                    phase == FreeUpSpacePhase.Freeing
                ) {
                    return@collect
                }
                if (settlement.granted) {
                    sessionFreed += settlement.uris.size
                    refreshFromStore()
                    val remaining = _uiState.value.candidates
                    if (remaining.isEmpty() && pendingDeleteBridge.uris.value.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                phase = if (sessionFreed > 0) {
                                    FreeUpSpacePhase.Done
                                } else {
                                    FreeUpSpacePhase.Idle
                                },
                                message = if (sessionFreed > 0) freedMessage(sessionFreed) else null,
                                awaitingConsentCount = 0,
                            )
                        }
                    } else {
                        // More chunks or residual candidates — Continue can re-run if queue empty.
                        _uiState.update {
                            it.copy(
                                phase = FreeUpSpacePhase.Stopped,
                                awaitingConsentCount = pendingDeleteBridge.uris.value.size,
                            )
                        }
                    }
                } else {
                    val hasCandidates = _uiState.value.candidates.isNotEmpty()
                    if (hasCandidates) {
                        stoppedLatch = true
                        _uiState.update {
                            it.copy(phase = FreeUpSpacePhase.Stopped, awaitingConsentCount = 0)
                        }
                    } else {
                        stoppedLatch = false
                        _uiState.update {
                            it.copy(phase = FreeUpSpacePhase.Idle, awaitingConsentCount = 0)
                        }
                    }
                    refreshFromStore()
                }
            }
        }
    }

    private suspend fun totalBytes(rows: List<SyncState>): Long {
        if (rows.isEmpty()) return 0L
        val sizeByUri = runCatching {
            localRepo.observeLocalMedia().first().associate { it.uri to it.sizeBytes }
        }.getOrDefault(emptyMap())
        return rows.sumOf { row ->
            row.sizeBytes.takeIf { it > 0L } ?: sizeByUri[row.localUri] ?: 0L
        }
    }

    private fun freedMessage(freed: Int): String =
        if (freed > 0) {
            context.resources.getQuantityString(R.plurals.settings_freed_photos, freed, freed)
        } else {
            context.getString(R.string.settings_free_up_none)
        }

    private fun workString(
        progress: androidx.work.Data?,
        output: androidx.work.Data?,
        key: String,
    ): String? = when {
        progress?.keyValueMap?.containsKey(key) == true -> progress.getString(key)
        output?.keyValueMap?.containsKey(key) == true -> output.getString(key)
        else -> null
    }

    private fun workInt(
        progress: androidx.work.Data?,
        output: androidx.work.Data?,
        key: String,
        default: Int = 0,
    ): Int = when {
        progress?.keyValueMap?.containsKey(key) == true -> progress.getInt(key, default)
        output?.keyValueMap?.containsKey(key) == true -> output.getInt(key, default)
        else -> default
    }
}
