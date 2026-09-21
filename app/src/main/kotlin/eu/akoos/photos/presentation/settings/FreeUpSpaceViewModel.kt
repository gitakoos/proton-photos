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

import android.app.PendingIntent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Backs the Free up space screen: the photos whose device copy can be reclaimed, shown before
 * anything is deleted, and the progress of the sweep that deletes them.
 *
 * The screen exists because the reclaim is a permanent local delete of potentially thousands of
 * files, and the Storage row it replaced started that with nothing but a spinner. Listing the exact
 * rows first lets the user check them, and every row here is `SYNCED`, so the backed-up badge each
 * cell draws is a check rather than a decoration.
 *
 * The sweep is deliberately tied to this screen's lifetime: leaving cancels it. Nothing is lost by
 * that, since each photo is committed on its own, and [load] on the next open lists what is left.
 */
@HiltViewModel
class FreeUpSpaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val syncStateRepo: SyncStateRepository,
    private val localRepo: eu.akoos.photos.domain.repository.LocalMediaRepository,
    private val accountManager: AccountManager,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        /** The rows the sweep will act on, in the order it will act on them. */
        val candidates: List<SyncState> = emptyList(),
        val running: Boolean = false,
        /** The Drive check that runs before a single file is deleted. Its own flag because it is the
         *  part with nothing to count: the screen says what it is doing instead of showing 0 of 0. */
        val verifying: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Terminal message for the finished sweep, shown on this screen rather than the one the
         *  button used to live on. */
        val message: String? = null,
        /** Android 11+ batch consent for files this app does not own. */
        val pendingIntent: PendingIntent? = null,
        /**
         * How much device space the listed photos actually occupy.
         *
         * Resolved when the list is built rather than summed off the rows: a sync row's own
         * `sizeBytes` is 0 on rows minted by a path that never had the local file's size to hand, so
         * summing the column alone reports 0 B for a real list of photos. MediaStore is asked once
         * for every local item and the row's own value is used only where MediaStore has nothing
         * (the same "trust the non-zero one" rule `HiddenFolderRecords` already applies).
         */
        val reclaimableBytes: Long = 0L,
    ) {
        val isEmpty: Boolean get() = !loading && candidates.isEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** URIs handed to the system consent dialog, settled once it returns. */
    private var pendingLocalUris: List<String> = emptyList()

    init { load() }

    /**
     * Re-read what can be reclaimed. Called on open and after a sweep, so a screen returned to shows
     * what is actually left rather than the list it was opened with.
     */
    fun load() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(loading = false, candidates = emptyList()) }
                return@launch
            }
            _uiState.update { it.copy(loading = true) }
            // Long.MAX_VALUE and protectDownloaded = false are what the Storage button always passed:
            // an explicit tap reclaims every backed-up copy, including ones downloaded back.
            val rows = freeUpSpace.candidates(userId, Long.MAX_VALUE, protectDownloaded = false)
            _uiState.update {
                it.copy(loading = false, candidates = rows, reclaimableBytes = totalBytes(rows))
            }
        }
    }

    fun start() {
        if (_uiState.value.running) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update {
                it.copy(running = true, verifying = true, done = 0, total = it.candidates.size, message = null)
            }
            try {
                // Check against Drive before deleting anything. This reclaims every backed-up photo,
                // including copies the user downloaded back, so acting on a sync_state that nothing
                // has verified lately could take a last copy. The check confirms only the on-device
                // candidates' cloud links are active right now (one batched call per 50), which is
                // bounded by the on-device count. The refresh-plus-reconcile pass this replaced walked
                // the entire Drive volume first, so on a large library it ran past the point the sweep
                // could start and nothing was reclaimed.
                //
                // A thrown transient error (a rate-limited verification batch) is deliberately NOT
                // swallowed: sweeping against links that could not be confirmed is the situation this
                // exists to prevent, so it falls through to the catch and the sweep does not run.
                val candidates = freeUpSpace.candidates(userId, Long.MAX_VALUE, protectDownloaded = false)
                val verified = freeUpSpace.verifyActiveBackups(userId, candidates)
                // Settle the grid onto the confirmed set before a single file is deleted: only links
                // that came back active survive here, so a candidate whose cloud copy is gone or
                // trashed is dropped rather than reclaimed.
                _uiState.update {
                    it.copy(
                        verifying = false,
                        candidates = verified,
                        reclaimableBytes = totalBytes(verified),
                        done = 0,
                        total = verified.size,
                    )
                }
                val result = freeUpSpace.reclaimCandidates(
                    candidates = verified,
                    onProgress = { done, total ->
                        _uiState.update { it.copy(done = done, total = total) }
                    },
                )
                when (result) {
                    is FreeUpSpaceUseCase.FreeUpResult.Done -> {
                        _uiState.update { it.copy(running = false, verifying = false, message = freedMessage(result.freed)) }
                        load()
                    }
                    is FreeUpSpaceUseCase.FreeUpResult.NeedsPermission -> {
                        pendingLocalUris = result.localUris
                        _uiState.update { it.copy(running = false, verifying = false, pendingIntent = result.pendingIntent) }
                    }
                }
            } catch (e: Exception) {
                // Cancellation is the ordinary way this ends: the user left the screen, which stops
                // the sweep by design. Everything already reclaimed stays reclaimed.
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        running = false,
                        verifying = false,
                        message = context.getString(R.string.settings_free_up_error, e.message ?: ""),
                    )
                }
            }
        }
    }

    /** The system consent dialog returned OK: it did the deleting, so only the rows move here. */
    fun onPermissionGranted() {
        viewModelScope.launch {
            val uris = pendingLocalUris
            pendingLocalUris = emptyList()
            uris.forEach { syncStateRepo.updateStatusAndDeleteLocal(it, SyncStatus.CLOUD_ONLY) }
            _uiState.update { it.copy(pendingIntent = null, message = freedMessage(uris.size)) }
            load()
        }
    }

    fun onPermissionDenied() {
        pendingLocalUris = emptyList()
        _uiState.update { it.copy(pendingIntent = null) }
        load()
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Device bytes the [rows] occupy. One MediaStore read for the whole library rather than a query
     * per photo, matching how the upload pass resolves the same kind of per-uri fact.
     */
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
}
