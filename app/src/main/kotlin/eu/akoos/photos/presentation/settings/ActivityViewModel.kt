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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
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
    data class Download(val id: UUID, val albumName: String, val done: Int, val total: Int)

    data class UiState(
        val uploadDone: Int = 0,
        val uploadTotal: Int = 0,
        val uploadEvents: List<UploadEvent> = emptyList(),
        val downloads: List<Download> = emptyList(),
        /** In-flight gallery multi-downloads (not WorkManager) from [TransferCenter]. */
        val galleryDownloads: List<TransferCenter.Active> = emptyList(),
        /** In-flight "make available offline" batches from [TransferCenter]. */
        val offlineTransfers: List<TransferCenter.Active> = emptyList(),
        /** Local URIs of photos that are on the device but not yet backed up (issue #16). */
        val pendingUris: List<String> = emptyList(),
        /** Persisted log of finished uploads/downloads for the History tab (newest first). */
        val history: List<TransferCenter.HistoryEntry> = emptyList(),
    ) {
        val isUploading: Boolean get() = uploadTotal > 0 && uploadDone < uploadTotal
        val pendingCount: Int get() = pendingUris.size
        val hasActivity: Boolean
            get() = isUploading || uploadEvents.isNotEmpty() || downloads.isNotEmpty() ||
                galleryDownloads.isNotEmpty() || offlineTransfers.isNotEmpty() || pendingUris.isNotEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Backup upload → done/total + a recent per-file event list. A trimmed version of the Sync
        // card's mapping: no byte-speed meter here, just the file list and the count.
        viewModelScope.launch {
            upload.progress.collect { evt ->
                _uiState.update { s ->
                    when (evt.status) {
                        // End of batch — clear the counters so the screen reads "nothing uploading",
                        // leaving only the pending list (if any).
                        UploadStatus.Idle -> s.copy(uploadDone = 0, uploadTotal = 0, uploadEvents = emptyList())
                        // Deferral frames carry no per-file payload.
                        UploadStatus.WaitingForWifi, UploadStatus.PreparingBackup -> s
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
                            )).takeLast(30)
                            s.copy(uploadDone = evt.doneIdx, uploadTotal = evt.totalCount, uploadEvents = next)
                        }
                    }
                }
            }
        }
        // Pending-upload photos (issue #16): LOCAL_ONLY rows are on the device but not on Drive; the
        // row carries the device URI, so the screen can draw a thumbnail grid, not just a count.
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            syncStateRepo.observeAll(userId).collect { states ->
                val pending = states
                    .filter { it.status == SyncStatus.LOCAL_ONLY }
                    .mapNotNull { it.localUri?.takeIf { u -> u.isNotBlank() } }
                _uiState.update { it.copy(pendingUris = pending) }
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
                        galleryDownloads = list.filter { t -> t.kind == TransferCenter.Kind.DOWNLOAD },
                        offlineTransfers = list.filter { t -> t.kind == TransferCenter.Kind.OFFLINE },
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

    /** Wipe the History tab. */
    fun clearHistory() {
        viewModelScope.launch { transferCenter.clearHistory() }
    }
}
