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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import eu.akoos.photos.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.batteryLowFlow
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.UploadStatus
import eu.akoos.photos.worker.AlbumDownloadWorker
import java.util.UUID
import javax.inject.Inject

/**
 * Backs the Activity screen — a single live view of the background transfers, reachable from the
 * Sync status card. Phase 1 surfaces the backup upload (done/total + the per-file event list, the
 * same [UploadPendingUseCase.progress] stream the Sync card reads) and the list of photos still
 * waiting to upload (issue #16). Downloads and offline pinning fold in later.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val upload: UploadPendingUseCase,
    private val syncStateRepo: SyncStateRepository,
    private val transferCenter: TransferCenter,
    private val accountManager: AccountManager,
) : ViewModel() {

    /** One running album download, from the [AlbumDownloadWorker]'s WorkManager progress. [id] is
     *  the work id so the row's cancel button can stop this exact download. */
    data class Download(val id: UUID, val albumName: String, val done: Int, val total: Int, val coverUri: String?)

    data class UiState(
        val uploadDone: Int = 0,
        val uploadTotal: Int = 0,
        val uploadEvents: List<UploadEvent> = emptyList(),
        val downloads: List<Download> = emptyList(),
        /** In-flight gallery multi-downloads (not WorkManager) from [TransferCenter]. */
        val galleryDownloads: List<TransferCenter.Active> = emptyList(),
        /** In-flight "make available offline" batches from [TransferCenter]. */
        val offlineTransfers: List<TransferCenter.Active> = emptyList(),
        /** In-flight single-photo uploads from [TransferCenter], e.g. the editor's edit-upload. */
        val uploadTransfers: List<TransferCenter.Active> = emptyList(),
        /** Local URIs of photos that are on the device but not yet backed up (issue #16). */
        val pendingUris: List<String> = emptyList(),
        /** Set when the user stops the backup: the still-pending (queued) photos are suppressed from
         *  the active-transfer card so a stopped batch doesn't keep reading as "uploading". Cleared
         *  when a new upload batch actually starts (the next Encrypting/Uploading event). The photos
         *  themselves stay pending (their DB rows are untouched) for a later auto-backup. */
        val uploadStopped: Boolean = false,
        /** Persisted log of finished uploads/downloads for the History tab (newest first). */
        val history: List<TransferCenter.HistoryEntry> = emptyList(),
        /** Whether the OS currently counts the battery as low (at or under its 15% floor). */
        val batteryLow: Boolean = false,
    ) {
        val isUploading: Boolean get() = uploadTotal > 0 && uploadDone < uploadTotal
        val pendingCount: Int get() = pendingUris.size

        /**
         * Backup has work to do and the OS is holding it because the battery is low.
         *
         * The upload workers carry `setRequiresBatteryNotLow(true)`, so under the floor the system
         * never starts them and the upload pipeline emits nothing at all. Without this the screen
         * shows queued photos next to no activity and no reason, which reads as the app being stuck.
         * The state is derived here rather than reported by the pipeline for exactly that reason:
         * there is no run to report it.
         *
         * Narrow on purpose. A batch that is already running is not held, and a backup the user
         * stopped is waiting on the user, not on the battery; naming the wrong cause in either case
         * would send them to fix something that is not the problem.
         */
        val backupHeldByBattery: Boolean
            get() = batteryLow && pendingUris.isNotEmpty() && !isUploading && !uploadStopped
        val hasActivity: Boolean
            get() = isUploading || uploadEvents.isNotEmpty() || downloads.isNotEmpty() ||
                galleryDownloads.isNotEmpty() || offlineTransfers.isNotEmpty() ||
                uploadTransfers.isNotEmpty() || pendingUris.isNotEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())

    /** DEBUG-only preview toggle: when on, [uiState] emits [sampleTestState] so the Activity cards
     *  can be inspected without a live transfer (a real upload/download usually finishes before the
     *  screen opens). The toggle is guarded by BuildConfig.DEBUG, so this has no effect in release. */
    private val _testMode = MutableStateFlow(false)
    val testMode: StateFlow<Boolean> = _testMode.asStateFlow()

    val uiState: StateFlow<UiState> = combine(_uiState, _testMode) { real, test ->
        if (test) sampleTestState else real
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Flip the DEBUG preview on/off. No-op in release builds. */
    fun toggleTestMode() { if (BuildConfig.DEBUG) _testMode.value = !_testMode.value }

    /** Representative upload + download + album + offline entries for the DEBUG preview toggle, so
     *  every Activity card variant renders at once without waiting for a real transfer. */
    private val sampleTestState: UiState by lazy {
        UiState(
            uploadDone = 1,
            uploadTotal = 3,
            uploadEvents = listOf(
                UploadEvent(uri = "sample://uploading/1", displayName = "IMG_0001.jpg", status = UploadEventStatus.Encrypting),
                UploadEvent(uri = "sample://uploading/2", displayName = "IMG_0002.jpg", status = UploadEventStatus.Uploading, sizeBytes = 4_000_000L, doneBytes = 1_500_000L),
            ),
            pendingUris = listOf("sample://queued/1", "sample://queued/2", "sample://queued/3"),
            downloads = listOf(
                Download(id = java.util.UUID(0L, 1L), albumName = "Sample album", done = 2, total = 5, coverUri = null),
            ),
            galleryDownloads = listOf(
                TransferCenter.Active(id = 1L, kind = TransferCenter.Kind.DOWNLOAD, done = 1, total = 4, name = "Photos", items = listOf("sample://dl/1", "sample://dl/2", "sample://dl/3", "sample://dl/4"), cancelable = true),
            ),
            offlineTransfers = listOf(
                TransferCenter.Active(id = 2L, kind = TransferCenter.Kind.OFFLINE, done = 3, total = 8, cancelable = true),
            ),
            history = listOf(
                TransferCenter.HistoryEntry(
                    kind = TransferCenter.Kind.UPLOAD.name, count = 12,
                    at = System.currentTimeMillis() - 2 * 60_000L,
                    uris = listOf("sample://h/1", "sample://h/2", "sample://h/3"),
                ),
                TransferCenter.HistoryEntry(
                    kind = TransferCenter.Kind.DOWNLOAD.name, name = "Summer 2026", count = 8,
                    at = System.currentTimeMillis() - 45 * 60_000L,
                    uris = listOf("sample://h/4", "sample://h/5"),
                ),
                TransferCenter.HistoryEntry(
                    kind = TransferCenter.Kind.OFFLINE.name, count = 5,
                    at = System.currentTimeMillis() - 3 * 3_600_000L,
                ),
            ),
        )
    }

    init {
        // Backup upload → done/total + a recent per-file event list. A trimmed version of the Sync
        // card's mapping: no byte-speed meter here, just the file list and the count.
        viewModelScope.launch {
            upload.progress.collect { evt ->
                _uiState.update { s ->
                    when (evt.status) {
                        // End of batch — clear the counters so the screen reads "nothing uploading",
                        // leaving only the pending list (if any).
                        // A full Drive ends the batch exactly as Idle does, so it clears the same
                        // counters. Leaving them stood the screen on "Backing up 3 of 40" with
                        // nothing running, and since isUploading reads those counters, nothing after
                        // it could resolve the state: the next batch merged into a list that was
                        // never closed. Wi-Fi and listing deferrals hold their frame because they
                        // resume on their own; this one waits on the user freeing space.
                        UploadStatus.Idle,
                        UploadStatus.StorageFull ->
                            s.copy(uploadDone = 0, uploadTotal = 0, uploadEvents = emptyList())
                        // Deferral frames carry no per-file payload.
                        UploadStatus.WaitingForWifi,
                        UploadStatus.PreparingBackup -> s
                        else -> {
                            val uiStatus = when (evt.status) {
                                UploadStatus.Uploading -> UploadEventStatus.Uploading
                                UploadStatus.Encrypting -> UploadEventStatus.Encrypting
                                UploadStatus.Done -> UploadEventStatus.Done
                                UploadStatus.Failed -> UploadEventStatus.Failed
                                UploadStatus.Queued -> UploadEventStatus.Queued
                                else -> UploadEventStatus.Done
                            }
                            val firstPerFile = evt.status == UploadStatus.Uploading ||
                                evt.status == UploadStatus.Encrypting
                            val isNewBatch = firstPerFile && s.uploadTotal > 0 && s.uploadDone >= s.uploadTotal
                            val carry = if (isNewBatch) emptyList() else s.uploadEvents
                            val next = (carry.filter { it.uri != evt.uri } + UploadEvent(
                                uri = evt.uri,
                                displayName = evt.displayName,
                                status = uiStatus,
                                sizeBytes = evt.sizeBytes,
                                doneBytes = evt.doneBytes,
                            )).takeLast(30)
                            // A real per-file event means a batch is genuinely uploading again, so a
                            // prior stop no longer applies: let the queued rows show once more.
                            val stopped = if (firstPerFile) false else s.uploadStopped
                            s.copy(
                                uploadDone = evt.doneIdx,
                                uploadTotal = evt.totalCount,
                                uploadEvents = next,
                                uploadStopped = stopped,
                            )
                        }
                    }
                }
            }
        }
        // Pending-upload photos (issue #16): a LOCAL_ONLY row that is ALSO queued is on the device,
        // not on Drive, and actually meant to be backed up; the row carries the device URI, so the
        // screen can draw a thumbnail grid, not just a count. The `queued` predicate matches the
        // upload processor's selector and the gallery's pending badge, so the three counts agree (a
        // LOCAL_ONLY row with no upload intent is not shown as "pending" here either).
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            syncStateRepo.observeAll(userId)
                .retryOnDbTear("ActivityPending")
                .collect { states ->
                val pending = states
                    .filter { it.status == SyncStatus.LOCAL_ONLY && it.queued }
                    .mapNotNull { it.localUri?.takeIf { u -> u.isNotBlank() } }
                _uiState.update { it.copy(pendingUris = pending) }
            }
        }
        // Battery state, live: the "waiting for battery" note has to clear the moment the phone goes
        // on a charger, so a one-off read at screen open would leave a stale reason on screen.
        viewModelScope.launch {
            context.batteryLowFlow().collect { low ->
                _uiState.update { it.copy(batteryLow = low) }
            }
        }
        // Album downloads run in AlbumDownloadWorker; every instance carries a shared tag, so one
        // tag query surfaces all of them at once with their live done/total and album name.
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagLiveData(AlbumDownloadWorker.TAG)
                .asFlow()
                .collect { infos ->
                    val active = infos
                        .filter { it.state == WorkInfo.State.RUNNING }
                        .map { wi ->
                            Download(
                                id = wi.id,
                                albumName = wi.progress.getString(AlbumDownloadWorker.KEY_ALBUM_NAME).orEmpty(),
                                done = wi.progress.getInt(AlbumDownloadWorker.KEY_PROGRESS_DONE, 0),
                                total = wi.progress.getInt(AlbumDownloadWorker.KEY_PROGRESS_TOTAL, 0),
                                coverUri = wi.progress.getString(AlbumDownloadWorker.KEY_COVER_URI)?.takeIf { it.isNotBlank() },
                            )
                        }
                        .filter { it.total > 0 }
                    _uiState.update { it.copy(downloads = active) }
                }
        }
        // Gallery multi-download + offline pin batches run inside a ViewModel scope, so they route
        // their progress through TransferCenter for this screen to render.
        viewModelScope.launch {
            transferCenter.active.collect { list ->
                _uiState.update {
                    it.copy(
                        // Named DOWNLOAD transfers are album downloads, already shown as their own
                        // cancelable WorkManager rows (state.downloads); exclude them here so an
                        // album download doesn't appear twice.
                        galleryDownloads = list.filter { t ->
                            t.kind == TransferCenter.Kind.DOWNLOAD && t.name.isNullOrBlank()
                        },
                        offlineTransfers = list.filter { t -> t.kind == TransferCenter.Kind.OFFLINE },
                        uploadTransfers = list.filter { t -> t.kind == TransferCenter.Kind.UPLOAD },
                    )
                }
            }
        }
        // Persisted history for the History tab.
        viewModelScope.launch {
            transferCenter.history.collect { entries ->
                _uiState.update { it.copy(history = entries) }
            }
        }
    }

    /** Cancel a running album download from its row's X button. */
    fun cancelDownload(id: UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    /** Stop the whole backup upload from the Uploads tab (same effect as the notification's Stop).
     *  Cooperative: the photo in transit finishes and backs up, remaining queued items are not
     *  started and stay pending for a later trigger. Never cancels the worker, so the in-flight
     *  native crypto is never interrupted. */
    fun cancelUpload() {
        upload.requestStop()
        // Immediately drop the queued photos from the active-transfer card. The item in transit
        // keeps showing (it finishes and backs up); the still-pending ones stay in the DB for a
        // later auto-backup but no longer read as an active upload here.
        _uiState.update { it.copy(uploadStopped = true) }
    }

    /** Stop a running gallery download or offline batch from its row's X button. */
    fun cancelTransfer(id: Long) {
        transferCenter.cancel(id)
    }

    /** Wipe the History tab. */
    fun clearHistory() {
        viewModelScope.launch { transferCenter.clearHistory() }
    }
}
