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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImportAlbumCount
import eu.akoos.photos.data.db.dao.ImportAlbumMemberDao
import eu.akoos.photos.data.db.dao.ImportHistoryDao
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.data.db.dao.ImportUploadedDao
import eu.akoos.photos.data.db.entity.ImportHistoryEntity
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.UndoImportUseCase
import eu.akoos.photos.domain.usecase.UndoResult
import eu.akoos.photos.worker.ImportWorker
import eu.akoos.photos.worker.StageImportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** What the import screen shows, derived from the stage worker's live [WorkInfo] and the staged rows. */
sealed interface ImportUiState {
    /** No import picked and nothing pending review: the screen offers the picker. */
    data object Idle : ImportUiState

    /** The picked zip is being staged into the review queue. [total] is 0 until the zip scan has
     *  counted its media entries, so the screen shows an indeterminate bar in that window. */
    data class Staging(val done: Int, val total: Int) : ImportUiState

    /** The zip has staged rows waiting for review. [rows] is the whole set (excluded entries kept in
     *  the list so they can be brought back), [includedCount] is how many would upload on confirm. */
    data class Review(
        val zipId: String,
        val rows: List<ImportStagedEntity>,
        val includedCount: Int,
    ) : ImportUiState

    /** The confirmed set is uploading. Driven by the upload worker's live progress; the shape mirrors
     *  [Staging] so the same card renders it. */
    data class Uploading(val done: Int, val total: Int) : ImportUiState

    /** The upload finished; [imported] + [alreadyInDrive] + [skipped] + [failed] equals [total].
     *  [alreadyInDrive] counts entries whose exact bytes Drive already held, so nothing was sent for
     *  them. [albumsCreated] is how many albums the run reconstructed, 0 when the run added no albums.
     *  [photosAddedToAlbums] is how many photo links the run filed into albums, new and reused alike, so a
     *  run that only added photos to an album that already existed still reports what it did. */
    data class Done(
        val total: Int,
        val imported: Int,
        val alreadyInDrive: Int,
        val skipped: Int,
        val failed: Int,
        val albumsCreated: Int,
        val photosAddedToAlbums: Int,
        val runId: String,
    ) : ImportUiState

    /** Staging ended in an error; the screen offers to pick a zip again. */
    data object Failed : ImportUiState
}

/**
 * Everything the Recent-imports detail overlay draws for one finished run: the run's [entry] summary
 * (file name, when it ran, its counts), the per-photo upload [rows] the run recorded, and the [photos]
 * still on Drive for the rows not yet undone. A row leaves [photos] the moment its cloud copy is trashed
 * by an undo (trash clears the photo listing), and an undone row drops out of the live-link set, so the
 * grid and the empty state track the undo without a manual refresh.
 *
 * [alreadyInDriveLinkIds] are the links of the ledger rows the run did not upload because Drive already
 * held an identical photo. Those links resolve to real cloud photos too, so they render in [photos]; the
 * set lets the grid badge them and the summary count them apart from the run's fresh imports.
 */
data class ImportDetailData(
    val runId: String,
    val entry: ImportHistoryEntity?,
    val rows: List<ImportUploadedEntity>,
    val photos: List<CloudPhoto>,
    val alreadyInDriveLinkIds: Set<String> = emptySet(),
    val albums: List<ImportAlbumCount> = emptyList(),
)

/**
 * Drives the Google Takeout / Ente import screen. Picking a zip takes a persistable read grant on it
 * (so the stage worker can reopen it after a process death), enqueues [StageImportWorker], and observes
 * both that unique work's [WorkInfo] and the rows it stages, mapping the pair into [ImportUiState].
 *
 * A review left pending by a previous visit or a process kill is resumed on open: any zip with staged
 * rows not yet uploaded re-enters [ImportUiState.Review] without re-staging.
 *
 * The feature is account-only, but the gate lives at the entry point (the Settings row is hidden without
 * an account) and in the worker (a guest stages nothing), so this holds no account state of its own.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val importStagedDao: ImportStagedDao,
    private val importHistoryDao: ImportHistoryDao,
    private val importUploadedDao: ImportUploadedDao,
    private val importAlbumMemberDao: ImportAlbumMemberDao,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val undoImportUseCase: UndoImportUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    // Completed import runs, newest first, for the history list under the picker. Capped so a
    // long-lived install shows a readable tail rather than the whole table.
    val history: StateFlow<List<ImportHistoryEntity>> =
        importHistoryDao.observeAll()
            .map { it.take(20) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The run whose detail overlay is open, or null while the history list is showing.
    private val _detailRunId = MutableStateFlow<String?>(null)
    val detailRunId: StateFlow<String?> = _detailRunId.asStateFlow()

    // The open run's live detail: its upload ledger and the cloud photos still on Drive for the rows
    // not yet undone, plus the matching history summary for the header. Re-derives whenever the ledger
    // changes (an undo flips rows to undone) so the grid and the undo affordance follow it.
    val detail: StateFlow<ImportDetailData?> =
        _detailRunId.flatMapLatest { runId ->
            if (runId == null) {
                flowOf(null)
            } else {
                combine(
                    importUploadedDao.observeByRun(runId),
                    history,
                ) { rows, hist -> rows to hist.firstOrNull { it.runId == runId } }
                    .flatMapLatest { (rows, entry) ->
                        val alreadyIds = rows.filter { it.alreadyInDrive }.map { it.linkId }.toSet()
                        // The links the grid shows: every row an undo has not trashed. That is the union of
                        // the run's real uploads still on Drive and every deduped row's pre-existing link
                        // (a deduped row is never undone), so both a fresh import and an already-present
                        // match render.
                        val shownLinks = rows.filterNot { it.undone }.map { it.linkId }
                        val photosFlow =
                            if (shownLinks.isEmpty()) flowOf(emptyList())
                            else drivePhotoRepository.observePhotosByLinkIds(shownLinks)
                        // The albums this run built, by name, loaded once per run so the detail can list
                        // them under the summary rather than only counting them.
                        val albumsFlow = flow { emit(importAlbumMemberDao.albumCountsForRun(runId)) }
                        combine(photosFlow, albumsFlow) { photos, albums ->
                            ImportDetailData(runId, entry, rows, photos, alreadyIds, albums)
                        }
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // An undo of the open run: whether one is running, and the counts of the last one so the overlay can
    // report "removed N, kept M changed, N failed". Cleared when the detail closes or the screen resets.
    private val _undoInFlight = MutableStateFlow(false)
    val undoInFlight: StateFlow<Boolean> = _undoInFlight.asStateFlow()
    private val _undoResult = MutableStateFlow<UndoResult?>(null)
    val undoResult: StateFlow<UndoResult?> = _undoResult.asStateFlow()

    // The zip being staged or reviewed; retained so exclude / discard act on the right run and a
    // returning observer re-attaches to it. Null while idle. A flow so the review's new-count and
    // has-albums signals can re-derive whenever the run changes.
    private val currentZipId = MutableStateFlow<String?>(null)

    // The run id for the zip currently being imported, held so an in-app cancel and re-confirm continue the
    // same run rather than splitting one archive across two run ids (which would misreport the counts and
    // leave two history rows). Cleared when the run is abandoned: a fresh pick, a discard, or a reset.
    private var activeRunId: String? = null

    private var observeJob: Job? = null

    // The album-reconstruction choice for the run under review, carried into the upload worker on
    // confirm. Reset to NONE whenever a fresh run starts so a previous run's choice never leaks in.
    val albumMode = MutableStateFlow(ImportAlbumMode.NONE)

    /** Sets which of the three album modes the confirmed run uploads under. */
    fun setAlbumMode(mode: ImportAlbumMode) {
        albumMode.value = mode
    }

    // How many genuinely new photos the review would upload (kept, not already in Drive): the confirm
    // button's headline count. Re-derives as the user excludes rows or a fresh run is picked.
    val newCount: StateFlow<Int> =
        currentZipId.flatMapLatest { zipId ->
            if (zipId == null) flowOf(0) else importStagedDao.observeNewCount(zipId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether the run's archive carried any album folder, so the review shows the album-mode toggle
    // only when there is an album to reconstruct.
    val hasAlbums: StateFlow<Boolean> =
        currentZipId.flatMapLatest { zipId ->
            if (zipId == null) flowOf(false) else importStagedDao.observeHasAlbums(zipId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // How many kept rows Drive already holds, shown in the review summary so the run reports its duplicate
    // skips before uploading. Re-derives as rows are excluded or a fresh run is picked.
    val reviewAlreadyCount: StateFlow<Int> =
        currentZipId.flatMapLatest { zipId ->
            if (zipId == null) flowOf(0) else importStagedDao.observeAlreadyCount(zipId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // The export's albums for the review preview: each album folder the kept rows carry with its photo
    // count, so the review lists the albums a confirm would build by name. Re-derives as rows are excluded
    // or a fresh run is picked; empty when nothing is picked or the export has no album.
    val albumSummary: StateFlow<List<ImportAlbumCount>> =
        currentZipId.flatMapLatest { zipId ->
            if (zipId == null) flowOf(emptyList()) else importStagedDao.observeAlbumSummary(zipId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The finished run's albums by name, loaded once the upload reaches Done, so the Done card names the
    // albums the run built rather than only totalling them. Keyed on the Done run id; empty otherwise.
    val doneAlbums: StateFlow<List<ImportAlbumCount>> =
        _uiState.map { (it as? ImportUiState.Done)?.runId?.takeIf { id -> id.isNotEmpty() } }
            .distinctUntilChanged()
            .flatMapLatest { runId ->
                if (runId == null) flowOf(emptyList()) else flow { emit(importAlbumMemberDao.albumCountsForRun(runId)) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Cached primary userId for the undo and the on-demand thumbnail-decrypt requests, kept fresh from
    // the account flow. Null with no signed-in account, in which case the undo is a no-op.
    @Volatile private var primaryUserId: UserId? = null

    init {
        accountManager.getPrimaryUserId().onEach { primaryUserId = it }.launchIn(viewModelScope)
        // Resume a review the user left without finishing (or that a process kill interrupted). Only
        // adopts a pending zip when nothing has been picked yet, so a fresh pick always wins the race.
        viewModelScope.launch {
            val pending = importStagedDao.zipsWithPending().firstOrNull()
            if (pending != null && currentZipId.value == null) observe(pending)
        }
    }

    /** Opens the Recent-imports detail for [runId]. The history rows pass a non-null runId only. */
    fun openHistoryDetail(runId: String) {
        _undoResult.value = null
        _detailRunId.value = runId
    }

    /** Closes the detail overlay and drops any undo result so a re-open starts clean. */
    fun closeHistoryDetail() {
        _detailRunId.value = null
        _undoResult.value = null
    }

    /**
     * Forgets one import run: its history row and its local ledger (the upload records the undo reads and
     * the album-membership rows). Nothing on Drive is touched, so the imported photos and any albums the
     * run built stay exactly as they are; only the local record and its undo option are removed. Closes the
     * detail overlay first when it is the run being deleted, so it does not linger over a gone entry.
     */
    fun deleteHistory(id: Long, runId: String?) {
        if (runId != null && _detailRunId.value == runId) closeHistoryDetail()
        viewModelScope.launch(Dispatchers.IO) {
            if (runId != null) {
                importUploadedDao.deleteRun(runId)
                importAlbumMemberDao.clearForRun(runId)
            }
            importHistoryDao.deleteById(id)
        }
    }

    /**
     * Reverses the run [runId] on a background thread: [UndoImportUseCase] trashes only the photos whose
     * bytes still match what the run uploaded, keeping anything the user has since changed. The in-flight
     * flag guards a double tap, and the result feeds the overlay's summary line. The detail's ledger flow
     * re-emits as rows flip to undone, so the grid empties itself; no manual refresh is needed here.
     */
    fun undoImport(runId: String) {
        if (_undoInFlight.value) return
        _undoInFlight.value = true
        viewModelScope.launch {
            val userId = primaryUserId ?: accountManager.getPrimaryUserId().firstOrNull()
            if (userId == null) {
                _undoInFlight.value = false
                return@launch
            }
            _undoResult.value = runCatching { undoImportUseCase.undo(userId, runId) }.getOrNull()
            _undoInFlight.value = false
        }
    }

    /** Warms the whole run's thumbnails when its detail opens; the set is one import run, so small. */
    fun warmRunThumbnails(linkIds: List<String>) {
        if (linkIds.isEmpty()) return
        primaryUserId?.let { drivePhotoRepository.requestThumbnailDecrypt(it, linkIds) }
    }

    /** Cancels the run's in-flight thumbnail decrypts when its detail leaves. */
    fun cancelRunThumbnails(linkIds: List<String>) {
        linkIds.forEach { drivePhotoRepository.cancelThumbnailDecrypt(it) }
    }

    /** Per-cell on-demand decrypt request, wired to [CloudPhotoCell]; no-op until a userId is known. */
    fun requestThumbnailDecrypt(linkId: String) {
        primaryUserId?.let { drivePhotoRepository.requestThumbnailDecrypt(it, linkId) }
    }

    /** Per-cell decrypt cancel, wired to [CloudPhotoCell] on dispose. */
    fun cancelThumbnailDecrypt(linkId: String) {
        drivePhotoRepository.cancelThumbnailDecrypt(linkId)
    }

    /**
     * Returns to the import home ([ImportUiState.Idle]) so the picker and the refreshed Recent-imports
     * list show. Wired to the Done / Failed screens and their back gesture, so a finished run does not
     * strand the user on a terminal card. Stops observing the finished run and clears the picked zip,
     * the detail overlay and any undo result.
     */
    fun reset() {
        observeJob?.cancel()
        observeJob = null
        currentZipId.value = null
        activeRunId = null
        albumMode.value = ImportAlbumMode.NONE
        _detailRunId.value = null
        _undoResult.value = null
        _undoInFlight.value = false
        _uiState.value = ImportUiState.Idle
    }

    /**
     * Called with the zip the picker returned. Persists the read grant, enqueues the staging pass
     * (unique on the uri with KEEP, so a re-pick of a still-staging zip attaches to the live run rather
     * than a second one), and starts observing its progress and staged rows.
     */
    fun onZipPicked(uri: Uri) {
        val zipUri = uri.toString()
        // Persist read access so a stage restart after a process kill can reopen the same zip.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // Re-picking a zip already imported once leaves that zip's previous upload work in a terminal
        // SUCCEEDED state; without clearing it, its stale WorkInfo would keep the screen on the old run's
        // Done. Cancelling the unique upload work drops it to CANCELLED so the fresh stage below wins.
        WorkManager.getInstance(context).cancelUniqueWork(ImportWorker.uniqueName(zipUri))
        // Optimistic scanning state so the picker button does not flash back before the worker's first
        // progress publish lands.
        _uiState.value = ImportUiState.Staging(done = 0, total = 0)
        // A fresh pick is a new import: drop the default album mode and any prior run id so a later confirm
        // mints its own rather than reusing a finished run's.
        albumMode.value = ImportAlbumMode.NONE
        activeRunId = null
        StageImportWorker.enqueue(context, zipUri)
        observe(zipUri)
    }

    /** Marks the selected entries excluded, so the upload pass skips them; they stay in the review list. */
    fun excludeSelected(entryNames: List<String>) = setExcluded(entryNames, excluded = true)

    /** Brings previously excluded entries back into the import. */
    fun restoreSelected(entryNames: List<String>) = setExcluded(entryNames, excluded = false)

    private fun setExcluded(entryNames: List<String>, excluded: Boolean) {
        if (entryNames.isEmpty()) return
        val zipId = currentZipId.value ?: return
        viewModelScope.launch { importStagedDao.setExcluded(zipId, entryNames, excluded) }
    }

    /**
     * The reject path: drops every staged row for the run and deletes its cached thumbnails, then
     * returns to the picker. Nothing has been uploaded at this point, so no cloud cleanup is involved.
     */
    fun discardImport() {
        val zipId = currentZipId.value ?: return
        observeJob?.cancel()
        currentZipId.value = null
        activeRunId = null
        albumMode.value = ImportAlbumMode.NONE
        _uiState.value = ImportUiState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            importStagedDao.thumbPaths(zipId).forEach { path ->
                path?.let { runCatching { File(it).delete() } }
            }
            importStagedDao.clearForZip(zipId)
        }
    }

    /**
     * Confirms the reviewed set for upload: enqueues the upload worker for [currentZipId] (unique on the
     * uri with KEEP, so a double tap attaches to the live run rather than starting a second one). The
     * optimistic [ImportUiState.Uploading] switches the screen off the review grid at once; the observer
     * then follows the worker's live progress to [ImportUiState.Done].
     */
    fun confirmImport() {
        val zipId = currentZipId.value ?: return
        // One run id for the whole import of this picked zip, reused across an in-app cancel and re-confirm
        // so what was already sent and what the resume sends land in one ledger and one history row, not
        // two. A WorkManager kill and restart already reuses it through the worker's input data. It is
        // minted on the first confirm and cleared only when the run is truly abandoned (a fresh pick,
        // discard, or reset), so the next import gets its own.
        val runId = activeRunId ?: UUID.randomUUID().toString().also { activeRunId = it }
        _uiState.value = ImportUiState.Uploading(done = 0, total = 0)
        ImportWorker.enqueue(context, zipId, runId, albumMode.value)
    }

    /**
     * Stops the running import (or the staging pass) for the open zip. The worker cancels cooperatively and
     * records a partial history row for whatever it already sent; the entries it had not reached stay
     * pending, so [deriveState] drops back to the review, where the run can be confirmed again to resume.
     */
    fun cancelImport() {
        val zipId = currentZipId.value ?: return
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ImportWorker.uniqueName(zipId))
        workManager.cancelUniqueWork(StageImportWorker.uniqueName(zipId))
    }

    /**
     * Observes the upload and stage workers' unique work alongside the staged rows for [zipId], mapping
     * the four into the screen state. Combining them keeps the review list live as the user excludes
     * rows, carries the screen through upload progress once the run is confirmed, and falls back to the
     * picker the moment the last row is discarded.
     */
    private fun observe(zipId: String) {
        currentZipId.value = zipId
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            val workManager = WorkManager.getInstance(context)
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(ImportWorker.uniqueName(zipId)),
                workManager.getWorkInfosForUniqueWorkFlow(StageImportWorker.uniqueName(zipId)),
                importStagedDao.observeForZip(zipId),
                importStagedDao.observeIncludedCount(zipId),
            ) { uploadInfos, stageInfos, rows, included ->
                deriveState(zipId, uploadInfos, stageInfos, rows, included, _uiState.value)
            }.collect { _uiState.value = it }
        }
    }

    /**
     * Maps the upload and stage [WorkInfo] lists plus the staged rows to what the screen shows.
     *
     * An active upload always shows [ImportUiState.Uploading]. Otherwise a run that still has work to
     * stage or send outranks a previous run's terminal upload, so re-picking an already-imported zip
     * lands back in staging/review instead of the old run's stale Done: [hasPendingStaged] is true while
     * a stage pass is live or the review still holds entries a confirm would send, and only a run with
     * nothing pending resolves a SUCCEEDED upload to [ImportUiState.Done]. The excluded leftovers of a
     * finished run carry `uploaded = 0` but are not counted (they never upload), so a normal import with
     * exclusions still reaches Done. A failed upload shows [ImportUiState.Failed]. The empty window right
     * after confirm, before the upload work is visible, holds the optimistic [ImportUiState.Uploading].
     * Absent an upload, an active stage shows [ImportUiState.Staging], any staged rows mean
     * [ImportUiState.Review], and a stage failure with nothing staged shows [ImportUiState.Failed]; the
     * empty window right after a pick keeps the optimistic [fallback] rather than flashing the picker.
     */
    private fun deriveState(
        zipId: String,
        uploadInfos: List<WorkInfo>,
        stageInfos: List<WorkInfo>,
        rows: List<ImportStagedEntity>,
        included: Int,
        fallback: ImportUiState,
    ): ImportUiState {
        val activeUpload = uploadInfos.firstOrNull {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
        if (activeUpload != null) {
            val p = activeUpload.progress
            return ImportUiState.Uploading(
                done = p.getInt(ImportWorker.KEY_DONE, 0),
                total = p.getInt(ImportWorker.KEY_TOTAL, 0),
            )
        }

        val activeStage = stageInfos.firstOrNull {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
        // A live stage pass, or staged rows a confirm would still send ([included] counts exactly the
        // rows that are neither uploaded nor excluded). This is what lets a fresh re-stage of an
        // already-imported zip win over that zip's previous, still-SUCCEEDED upload.
        val hasPendingStaged = activeStage != null || included > 0

        val terminalUpload = uploadInfos.lastOrNull()
        if (terminalUpload != null) {
            when (terminalUpload.state) {
                WorkInfo.State.SUCCEEDED ->
                    // Only a run with nothing left to stage or send is Done; a pending re-stage falls
                    // through to Staging/Review below. The optimistic Staging set on a fresh pick also
                    // suppresses a one-frame stale Done before the new stage work becomes visible.
                    if (!hasPendingStaged && fallback !is ImportUiState.Staging) {
                        val out = terminalUpload.outputData
                        return ImportUiState.Done(
                            total = out.getInt(ImportWorker.KEY_RESULT_TOTAL, 0),
                            imported = out.getInt(ImportWorker.KEY_RESULT_IMPORTED, 0),
                            alreadyInDrive = out.getInt(ImportWorker.KEY_RESULT_ALREADY, 0),
                            skipped = out.getInt(ImportWorker.KEY_RESULT_SKIPPED, 0),
                            failed = out.getInt(ImportWorker.KEY_RESULT_FAILED, 0),
                            albumsCreated = out.getInt(ImportWorker.KEY_RESULT_ALBUMS, 0),
                            photosAddedToAlbums = out.getInt(ImportWorker.KEY_RESULT_ADDED, 0),
                            runId = out.getString(ImportWorker.KEY_RESULT_RUN_ID) ?: "",
                        )
                    }
                WorkInfo.State.FAILED -> return ImportUiState.Failed
                // The active states returned above; a cancelled upload falls through so the still-pending
                // rows can be reviewed and confirmed again.
                else -> Unit
            }
        }
        // Confirm has been pressed but the upload work is not visible yet: hold the optimistic state.
        if (fallback is ImportUiState.Uploading && uploadInfos.isEmpty()) return fallback

        if (activeStage != null) {
            val p = activeStage.progress
            return ImportUiState.Staging(
                done = p.getInt(StageImportWorker.KEY_DONE, 0),
                total = p.getInt(StageImportWorker.KEY_TOTAL, 0),
            )
        }
        if (rows.isNotEmpty()) return ImportUiState.Review(zipId, rows, included)
        return when (stageInfos.lastOrNull()?.state) {
            WorkInfo.State.FAILED -> ImportUiState.Failed
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> ImportUiState.Idle
            // No work visible yet: hold the optimistic scanning state from a fresh pick, else idle.
            else -> if (fallback is ImportUiState.Staging) fallback else ImportUiState.Idle
        }
    }
}
