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

package eu.akoos.photos.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.mlsync.CONFLICT_FAILURE
import eu.akoos.photos.data.mlsync.FACE_WALK_IDLE_TIMEOUT_MS
import eu.akoos.photos.data.mlsync.FaceSnapshotExporter
import eu.akoos.photos.data.mlsync.FaceSnapshotImportResult
import eu.akoos.photos.data.mlsync.FaceSnapshotImporter
import eu.akoos.photos.data.mlsync.FaceSnapshotMode
import eu.akoos.photos.data.mlsync.ML_SYNC_KIND_ORDER
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveFolder
import eu.akoos.photos.data.mlsync.MlDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveResult
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.mlsync.MlRestoreStep
import eu.akoos.photos.data.mlsync.MlSyncFacePause
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncOutcome
import eu.akoos.photos.data.mlsync.MlSyncPartStatus
import eu.akoos.photos.data.mlsync.MlSyncPhase
import eu.akoos.photos.data.mlsync.MlSyncProgress
import eu.akoos.photos.data.mlsync.MlSyncRequest
import eu.akoos.photos.data.mlsync.MlSyncResult
import eu.akoos.photos.data.mlsync.MlSyncSignOut
import eu.akoos.photos.data.mlsync.MlSyncTally
import eu.akoos.photos.data.mlsync.MlSyncWorkData
import eu.akoos.photos.data.mlsync.MlUploadDecision
import eu.akoos.photos.data.mlsync.RETRY_LIMIT_FAILURE
import eu.akoos.photos.data.mlsync.SearchImportMode
import eu.akoos.photos.data.mlsync.SearchSnapshotExportResult
import eu.akoos.photos.data.mlsync.SearchSnapshotExporter
import eu.akoos.photos.data.mlsync.SearchSnapshotImportResult
import eu.akoos.photos.data.mlsync.SearchSnapshotImporter
import eu.akoos.photos.data.mlsync.faceWalkResumes
import eu.akoos.photos.data.mlsync.isStorageFull
import eu.akoos.photos.data.mlsync.mlSyncRunAllowed
import eu.akoos.photos.data.mlsync.partStatus
import eu.akoos.photos.data.mlsync.planRestore
import eu.akoos.photos.data.mlsync.uploadDecision
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.sdk.SdkGate
import eu.akoos.photos.data.semantic.SemanticIndexingScheduler
import eu.akoos.photos.data.semantic.SemanticIndexingState
import eu.akoos.photos.util.MlRail
import eu.akoos.photos.util.MlWalkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one ML sync job, a save or a restore of the face and search snapshots in the Drive "ML data" folder,
 * as a foreground worker, so a long upload or import on a large library survives the screen closing. The
 * request comes from the input data ([MlSyncWorkData.input]), progress goes out through setProgress, and the
 * run ends with output of names and counts only ([MlSyncWorkData.output]).
 * [eu.akoos.photos.data.mlsync.MlSyncController] starts the jobs and reads their state.
 *
 * One job at a time: every request is enqueued as the unique work [UNIQUE_WORK_NAME] under
 * [ExistingWorkPolicy.KEEP], so a second tap while a job is queued or running starts nothing. The Drive SDK's
 * crypto runs in this process, outside the `:crypto` isolation, which is why only one may run.
 *
 * Save: each snapshot is sealed into a file in the app cache and uploaded under this install's own name,
 * faces first. The ML data folder is created only when the request carries the user's agreement; without it
 * a missing folder ends the run as [MlSyncResult.FOLDER_MISSING] before anything is exported, and before the
 * ongoing notification shows, so a save that only learns the folder is missing never flashes one.
 *
 * Restore: the files of [planRestore] are downloaded and imported one at a time. The face walk is stood down
 * before the first face file (paused, then waited out) and the semantic walk before the first search import,
 * each face import runs inside the scheduler's import fence, and both walks are handed back once every import
 * is done, the imported faces regrouped first, so neither walk writes over an import or holds its model beside
 * one. The face pause is taken as a hold ([MlSyncFacePause]), and every run lets go of a hold still left when
 * it ends, so no run leaves the face walk paused for good. Each file must be the install's its Drive name says,
 * or its import is refused. A download is deleted as soon as it has opened and verified, before its import
 * writes, and an import that finds storage too full ends the run as [MlSyncResult.NO_SPACE].
 *
 * A sign-out stops the run before anything else ([MlSyncSignOut]): the job is cancelled, rolling back an
 * import still in its transaction, and the sign-out waits for the run to end. A run that finds its account
 * signing out hands nothing back to it: the walks are reset, and what an import may have committed after the
 * sign-out's wipe is deleted. A request made for an account that is not the signed-in one ends as
 * [MlSyncResult.NOT_SIGNED_IN]. Every Drive, storage and database failure ends as a result code rather than a
 * crash, a job started again after crashes past its cap ([mlSyncRunAllowed]) ends at once, and the sealed
 * files are deleted in every case.
 */
@HiltWorker
class MlSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountManager: AccountManager,
    private val sdkGate: SdkGate,
    private val driveStore: MlDriveStore,
    private val faceExporter: FaceSnapshotExporter,
    private val faceImporter: FaceSnapshotImporter,
    private val searchExporter: SearchSnapshotExporter,
    private val searchImporter: SearchSnapshotImporter,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val semanticIndexingScheduler: SemanticIndexingScheduler,
    private val mlWalkGate: MlWalkGate,
    private val signOut: MlSyncSignOut,
) : CoroutineWorker(context, params) {

    /** Read from the input on its own, so the notification knows it even for a request that is unreadable. */
    private val operation: MlSyncOperation? by lazy { MlSyncWorkData.operation(inputData) }

    /** Tracked whole, the hand-back included, so a sign-out can stop the run and wait for it to end. */
    override suspend fun doWork(): Result = signOut.track {
        try {
            runRequest()
        } finally {
            withContext(NonCancellable) { handBackLeftoverFacePause() }
        }
    }

    private suspend fun runRequest(): Result {
        val request = MlSyncWorkData.request(inputData)
            ?: return finish(MlSyncOutcome(operation, MlSyncResult.INVALID_REQUEST))
        val scratch = TransferScratch(File(applicationContext.cacheDir, SCRATCH_DIR))
        if (!mlSyncRunAllowed(runAttemptCount)) {
            Log.w(TAG, "${request.operation} started $runAttemptCount times before, not run again")
            withContext(Dispatchers.IO) { scratch.clear() }
            return finish(MlSyncOutcome(request.operation, MlSyncResult.FAILED, failure = RETRY_LIMIT_FAILURE))
        }
        val userId = UserId(request.userId)
        if (isSignedIn(userId) != true) return finish(MlSyncOutcome(request.operation, MlSyncResult.NOT_SIGNED_IN))
        if (!sdkGate.isCapable) return finish(MlSyncOutcome(request.operation, MlSyncResult.NOT_CAPABLE))

        return try {
            val outcome = withContext(Dispatchers.IO) {
                scratch.clear()
                whileSignedIn(userId) {
                    when (request) {
                        is MlSyncRequest.Save -> save(userId, request, scratch)
                        is MlSyncRequest.Restore -> restore(userId, request, scratch)
                    }
                }
            }
            finish(outcome ?: MlSyncOutcome(request.operation, MlSyncResult.NOT_SIGNED_IN))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${request.operation} failed: ${className(e)}")
            finish(MlSyncOutcome(request.operation, MlSyncResult.FAILED, failure = className(e)))
        } finally {
            scratch.clear()
            // WorkManager drops the foreground post on exit, but some OEMs leave it behind.
            runCatching { NotificationManagerCompat.from(applicationContext).cancel(NotificationIds.ML_SYNC) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    /**
     * Runs [block] while [userId] stays the signed-in account and returns its result, or null once a sign-out
     * cancelled it. A cancellation of the worker itself still propagates.
     */
    private suspend fun <T : Any> whileSignedIn(userId: UserId, block: suspend () -> T): T? = coroutineScope {
        val signedOut = AtomicBoolean(false)
        val work = async { block() }
        val watcher = launch {
            accountManager.getPrimaryUserId().first { it != userId }
            signedOut.set(true)
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!signedOut.get() || !isActive) throw e
            Log.i(TAG, "account changed, run stopped")
            null
        } finally {
            watcher.cancel()
        }
    }

    // ---- Save ----------------------------------------------------------------------------------------

    private suspend fun save(userId: UserId, request: MlSyncRequest.Save, scratch: TransferScratch): MlSyncOutcome {
        val total = ML_SYNC_KIND_ORDER.size
        val tally = MlSyncTally(MlSyncOperation.SAVE, filesTotal = total)
        report(MlSyncProgress(MlSyncPhase.PREPARING, kind = null, done = 0, total = total))
        // Resolved before anything is exported or the notification shows, so a missing folder the user has not
        // agreed to create costs no export and flashes nothing; only a confirmed request lets the store create it.
        when (val folder = resolveFolder(userId, create = request.createFolderConfirmed)) {
            is MlDriveResult.Ok -> if (folder.value == null) return tally.outcome(MlSyncResult.FOLDER_MISSING)
            MlDriveResult.NotCapable -> return tally.outcome(MlSyncResult.NOT_CAPABLE)
            MlDriveResult.FolderMissing -> return tally.outcome(MlSyncResult.FOLDER_MISSING)
            MlDriveResult.Conflict -> return tally.outcome(MlSyncResult.FAILED, CONFLICT_FAILURE)
            is MlDriveResult.Failed -> return tally.outcome(MlSyncResult.FAILED, folder.cause)
        }
        promoteToForeground()
        val deviceId = try {
            driveStore.deviceId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return tally.outcome(MlSyncResult.FAILED, className(e))
        }
        for ((index, kind) in ML_SYNC_KIND_ORDER.withIndex()) {
            saveKind(userId, kind, deviceId, index, total, tally, scratch)?.let { return tally.outcome(it) }
        }
        return tally.outcome()
    }

    /**
     * The ML data folder, created where missing when [create]. A lookup that fails is tried once more before
     * the save gives up, so one failed Drive call does not end a save the user has just agreed to; creating
     * or finding the folders again is harmless, as the store only ever creates what is missing.
     */
    private suspend fun resolveFolder(userId: UserId, create: Boolean): MlDriveResult<MlDriveFolder?> {
        val first = driveStore.folder(userId, createIfMissing = create)
        if (first !is MlDriveResult.Failed && first != MlDriveResult.Conflict) return first
        Log.i(TAG, "folder lookup failed, trying once more")
        return driveStore.folder(userId, createIfMissing = create)
    }

    /** Exports and uploads the snapshot of [kind]. Returns a result that ends the whole run, or null. */
    private suspend fun saveKind(
        userId: UserId,
        kind: MlDriveFileKind,
        deviceId: String,
        index: Int,
        total: Int,
        tally: MlSyncTally,
        scratch: TransferScratch,
    ): MlSyncResult? {
        report(MlSyncProgress(MlSyncPhase.EXPORTING, kind, index, total))
        val file = try {
            scratch.newFile("save-${kind.prefix}-")
        } catch (e: IOException) {
            tally.record(kind, MlSyncPartStatus.FAILED, className(e))
            return null
        }
        try {
            val exported = try {
                export(userId, kind, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${kind.prefix} export failed: ${className(e)}")
                SnapshotExport.Failed(className(e))
            }
            val written = when (exported) {
                is SnapshotExport.Written -> exported
                SnapshotExport.Empty -> {
                    tally.record(kind, MlSyncPartStatus.EMPTY)
                    return null
                }
                is SnapshotExport.Failed -> {
                    tally.record(kind, MlSyncPartStatus.FAILED, exported.failure)
                    return null
                }
            }
            report(MlSyncProgress(MlSyncPhase.UPLOADING, kind, index, total))
            val name = MlDriveNaming.fileName(kind, deviceId)
            var attempt = 1
            while (true) {
                val result = driveStore.upload(userId, name, file, MlDriveNaming.MEDIA_TYPE)
                when (val decision = uploadDecision(result, attempt)) {
                    MlUploadDecision.Uploaded -> {
                        tally.record(kind, MlSyncPartStatus.DONE)
                        tally.count(
                            faces = written.faces,
                            people = written.people,
                            scanMarkers = written.scanMarkers,
                            searchEmbeddings = written.searchEmbeddings,
                        )
                        return null
                    }
                    MlUploadDecision.Retry -> {
                        Log.i(TAG, "${kind.prefix} upload conflicted, retrying")
                        attempt++
                    }
                    is MlUploadDecision.GiveUp -> {
                        tally.record(kind, decision.status, decision.failure)
                        return decision.endsRun
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    /** Seals this device's snapshot of [kind] into [into]. */
    private suspend fun export(userId: UserId, kind: MlDriveFileKind, into: File): SnapshotExport = when (kind) {
        MlDriveFileKind.FACES -> {
            val export = FileOutputStream(into).buffered().use { faceExporter.export(userId, it) }
            // A face-free library still exports its scan markers, which spare another device the scan.
            if (export.faces == 0 && export.scanMarkers == 0) {
                SnapshotExport.Empty
            } else {
                SnapshotExport.Written(faces = export.faces, people = export.people, scanMarkers = export.scanMarkers)
            }
        }
        MlDriveFileKind.SEARCH ->
            when (val export = FileOutputStream(into).buffered().use { searchExporter.exportTo(userId, it) }) {
                is SearchSnapshotExportResult.Exported -> SnapshotExport.Written(searchEmbeddings = export.embeddings)
                SearchSnapshotExportResult.Empty -> SnapshotExport.Empty
                is SearchSnapshotExportResult.Failed -> SnapshotExport.Failed(export.reason.name)
            }
    }

    private sealed interface SnapshotExport {
        data class Written(
            val faces: Int = 0,
            val people: Int = 0,
            val scanMarkers: Int = 0,
            val searchEmbeddings: Int = 0,
        ) : SnapshotExport

        data object Empty : SnapshotExport

        data class Failed(val failure: String) : SnapshotExport
    }

    // ---- Restore -------------------------------------------------------------------------------------

    private suspend fun restore(
        userId: UserId,
        request: MlSyncRequest.Restore,
        scratch: TransferScratch,
    ): MlSyncOutcome {
        val plan = planRestore(request.files, request.mode)
        if (plan.isEmpty()) return MlSyncOutcome(MlSyncOperation.RESTORE, MlSyncResult.INVALID_REQUEST)
        promoteToForeground()
        val tally = MlSyncTally(MlSyncOperation.RESTORE, filesTotal = plan.size)
        val walks = WalkStandDown(request.faceIndexingPausedByUser)
        try {
            // Face files come first in the plan, so the face walk is down before the first download: a walk
            // that started meanwhile would scan the very photos the snapshot is about to bring.
            if (plan.any { it.file.kind == MlDriveFileKind.FACES }) walks.standDownFaces()
            for ((index, step) in plan.withIndex()) {
                restoreStep(userId, step, index, plan.size, tally, walks, scratch)?.let { return tally.outcome(it) }
            }
            report(MlSyncProgress(MlSyncPhase.FINISHING, kind = null, done = plan.size, total = plan.size))
            return tally.outcome()
        } finally {
            withContext(NonCancellable) { walks.handBack(userId) }
        }
    }

    /** Downloads and imports one file. Returns a result that ends the whole run, or null. */
    private suspend fun restoreStep(
        userId: UserId,
        step: MlRestoreStep,
        index: Int,
        total: Int,
        tally: MlSyncTally,
        walks: WalkStandDown,
        scratch: TransferScratch,
    ): MlSyncResult? {
        val kind = step.file.kind
        report(MlSyncProgress(MlSyncPhase.DOWNLOADING, kind, index, total))
        val file = try {
            scratch.newFile("restore-${kind.prefix}-")
        } catch (e: IOException) {
            tally.record(kind, MlSyncPartStatus.FAILED, className(e))
            return null
        }
        try {
            when (val download = driveStore.download(userId, step.file.revisionUid, file)) {
                is MlDriveResult.Ok -> Unit
                MlDriveResult.NotCapable -> {
                    tally.record(kind, MlSyncPartStatus.FAILED)
                    return MlSyncResult.NOT_CAPABLE
                }
                MlDriveResult.Conflict, MlDriveResult.FolderMissing -> {
                    tally.record(kind, MlSyncPartStatus.FAILED, download.javaClass.simpleName)
                    return null
                }
                is MlDriveResult.Failed -> {
                    tally.record(kind, MlSyncPartStatus.FAILED, download.cause)
                    return null
                }
            }
            report(MlSyncProgress(MlSyncPhase.IMPORTING, kind, index, total))
            val status = try {
                when (kind) {
                    MlDriveFileKind.FACES -> importFaces(userId, step, file, tally, walks)
                    MlDriveFileKind.SEARCH -> importSearch(userId, step, file, tally, walks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The importers roll their transaction back on a failure, so the data here is as it was.
                Log.w(TAG, "${kind.prefix} import failed: ${className(e)}")
                (if (isStorageFull(e)) MlSyncPartStatus.NO_SPACE else MlSyncPartStatus.FAILED)
                    .also { tally.record(kind, it, className(e)) }
            }
            // Full storage would fail every later file and a retry alike, so the run ends here.
            return if (status == MlSyncPartStatus.NO_SPACE) MlSyncResult.NO_SPACE else null
        } finally {
            file.delete()
        }
    }

    /** Imports the face file [file] of [step] and returns the status it ended in. */
    private suspend fun importFaces(
        userId: UserId,
        step: MlRestoreStep,
        file: File,
        tally: MlSyncTally,
        walks: WalkStandDown,
    ): MlSyncPartStatus {
        walks.standDownFaces()
        val mode = if (step.replace) FaceSnapshotMode.Replace else FaceSnapshotMode.Merge
        walks.faceImportStarted = true
        // Inside the scheduler's import fence as well: a walk the user resumes from the face card meanwhile, or
        // one a stand-down that could not be written left running, writes nothing over the imported faces.
        val result = faceIndexingScheduler.withImportFence {
            FileInputStream(file).use { input ->
                // Opened and verified, the download is spent: deleted before the import writes, freeing its space.
                faceImporter.import(userId, input, mode, expectedDeviceId = step.file.deviceId) {
                    runCatching { input.close() }
                    file.delete()
                }
            }
        }
        val status = result.partStatus()
        tally.record(MlDriveFileKind.FACES, status)
        when (result) {
            is FaceSnapshotImportResult.Imported -> {
                walks.facesImported = true
                tally.count(
                    faces = result.faces + result.facesUpdated,
                    people = result.people,
                    scanMarkers = result.scanMarkers,
                )
            }
            is FaceSnapshotImportResult.NamesStaged ->
                tally.count(faces = result.facesUpdated, people = result.people)
            FaceSnapshotImportResult.WrongAccount,
            FaceSnapshotImportResult.UnsupportedVersion,
            FaceSnapshotImportResult.Unreadable,
            FaceSnapshotImportResult.NotEnoughSpace -> Unit
        }
        return status
    }

    /** Imports the search file [file] of [step] and returns the status it ended in. */
    private suspend fun importSearch(
        userId: UserId,
        step: MlRestoreStep,
        file: File,
        tally: MlSyncTally,
        walks: WalkStandDown,
    ): MlSyncPartStatus {
        walks.pauseSemantic()
        val mode = if (step.replace) SearchImportMode.Replace else SearchImportMode.Merge
        // The importer opens the file afresh for its second try with refreshed address keys. Opened and
        // verified, the download is spent: deleted before the first row is written, to free its space.
        val result = searchImporter.importFrom(
            userId,
            mode,
            expectedDeviceId = step.file.deviceId,
            releaseSnapshot = { file.delete() },
        ) {
            FileInputStream(file)
        }
        val failure = (result as? SearchSnapshotImportResult.Failed)?.reason?.name
        val status = result.partStatus()
        tally.record(MlDriveFileKind.SEARCH, status, failure)
        if (result is SearchSnapshotImportResult.Imported) {
            tally.count(searchEmbeddings = result.imported, searchKept = result.kept)
        }
        return status
    }

    /**
     * The indexing walks a restore stands down for its imports and hands back once they are done, as the
     * importers ask: a running face walk would detect a photo the snapshot brings all over again and write
     * over its faces and names, and a semantic walk would embed photos the import is filling while its model
     * session sits beside the import. The face walk is paused through its persisted pause switch, which
     * also keeps a gallery refresh from starting a new walk mid-import, taken as a hold ([MlSyncFacePause]) so
     * the pause is never mistaken for the user's, and then waited out; the semantic walk is paused through its
     * in-memory switch. Each wait is bounded, as the face import fence keeps a walk still winding down from
     * writing over an import either way.
     */
    private inner class WalkStandDown(private val facePausedByUserAtStart: Boolean) {
        private var facesDown = false

        /** Whether an ML sync run holds the face pause: this one turned it on, or found an earlier run's hold. */
        private var faceHeld = false
        private var semanticDown = false
        private var semanticPausedHere = false

        /** Set once a face file imported whole, so the hand-back regroups its faces into people. */
        var facesImported = false

        /** Set as a face import begins, so a hand-back to an account that is signing out deletes what it wrote. */
        var faceImportStarted = false

        suspend fun standDownFaces() {
            if (facesDown) return
            facesDown = true
            faceHeld = MlSyncFacePause.hold(applicationContext)
            // The store's update reaches the scheduler on its own; this also stops a running walk at its next
            // photo right away and has it read as paused. Without a hold the switch is the user's, so the walk
            // is paused already.
            if (faceHeld) faceIndexingScheduler.setPaused(true)
            // A pass winding down finishes the photos it has in flight and puts its model sessions away first.
            withTimeoutOrNull(FACE_WALK_IDLE_TIMEOUT_MS) { faceIndexingScheduler.awaitIdle() }
            awaitRailFree(MlRail.FACE)
        }

        suspend fun pauseSemantic() {
            if (semanticDown) return
            semanticDown = true
            // The semantic switch lives in memory only; a walk the user paused reads as Paused.
            if (semanticIndexingScheduler.progress.value.state != SemanticIndexingState.Paused) {
                semanticIndexingScheduler.setPaused(true)
                semanticPausedHere = true
            }
            awaitRailFree(MlRail.SEMANTIC)
        }

        /**
         * Regroups the imported faces and restarts the walks this run stood down, for [userId] while it is
         * still the signed-in account and not signing out. The app has no account switcher, so any other
         * answer means the account is leaving. Unpausing a scheduler would then start a walk for the account
         * it last ran, the one leaving, so each is reset instead, as the sign-out cleanup does too: that lifts
         * the pause without a walk, and the semantic reset drops the embeddings. The faces, people and staged
         * names an import of this run may have committed after the sign-out's wipe are deleted here as well,
         * since the run can outlive the sign-out's wait. The face pause and its hold are lifted in one write
         * before the walk is asked for, so a process death between the two never leaves a pause that reads as
         * the user's.
         */
        suspend fun handBack(userId: UserId) {
            // An unreadable account state takes the path that deletes nothing.
            val signedIn = isSignedIn(userId) ?: true
            if (facesDown) {
                if (signedIn) {
                    // Forced, so it groups the imported faces even while the user keeps the walk paused.
                    if (facesImported) faceIndexingScheduler.requestRecluster(userId)
                    if (faceWalkResumes(faceHeld, facePausedByUserAtStart)) {
                        MlSyncFacePause.lift(applicationContext)
                        faceIndexingScheduler.requestIndex(userId)
                    }
                } else {
                    MlSyncFacePause.lift(applicationContext)
                    faceIndexingScheduler.reset()
                    if (faceImportStarted) {
                        try {
                            faceImporter.forgetAccount(userId)
                        } catch (e: Exception) {
                            Log.w(TAG, "faces of a signed-out account not deleted: ${className(e)}")
                        }
                    }
                }
            }
            if (signedIn) {
                if (semanticPausedHere) semanticIndexingScheduler.requestIndex(userId)
            } else if (semanticDown) {
                // Whoever paused the walk: a search import of this run may have committed after the wipe.
                semanticIndexingScheduler.reset(userId)
                if (semanticPausedHere) semanticIndexingScheduler.setPaused(false)
            }
        }
    }

    /** Waits, at most [WALK_SETTLE_TIMEOUT_MS], for a walk on [rail] to release the model gate. */
    private suspend fun awaitRailFree(rail: MlRail) {
        withTimeoutOrNull(WALK_SETTLE_TIMEOUT_MS) { mlWalkGate.activeRail.first { it != rail } }
    }

    /**
     * Hands the face walk back when an ML sync run still holds its pause as this run ends, however it ended.
     * A restore's own hand-back has let go of the pause it held by then, and only one ML sync job runs at a
     * time, so a hold found here was left by an earlier run that never reached its hand-back: one killed
     * mid-import whose retry ended before its stand-down (an unreadable request, a signed-out account, a
     * device the SDK cannot run on), or a later job after such a run.
     */
    private suspend fun handBackLeftoverFacePause() {
        if (MlSyncFacePause.read(applicationContext)?.held != true) return
        Log.i(TAG, "handing back a face pause an earlier run held")
        MlSyncFacePause.lift(applicationContext)
        signedInUserId()?.let { faceIndexingScheduler.requestIndex(it) }
    }

    // ---- Plumbing ------------------------------------------------------------------------------------

    /**
     * Whether [userId] is the signed-in account and not signing out, or null when the account state cannot be
     * read. An account signing out is still the signed-in one until its sign-out ends, so it is asked apart.
     */
    private suspend fun isSignedIn(userId: UserId): Boolean? = try {
        accountManager.getPrimaryUserId().first() == userId && !signOut.isSigningOut(userId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The signed-in account unless it is signing out, or null when there is none or it cannot be read. */
    private suspend fun signedInUserId(): UserId? = try {
        accountManager.getPrimaryUserId().first()?.takeUnless { signOut.isSigningOut(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Shows the ongoing notification and keeps the run going with the screen off. Called once a save has
     * found its folder and once a restore has a plan, so a run that ends straight away shows nothing.
     */
    private suspend fun promoteToForeground() {
        bestEffort("foreground") { setForeground(foregroundInfo()) }
    }

    private suspend fun report(progress: MlSyncProgress) {
        val op = operation ?: return
        bestEffort("progress") { setProgress(MlSyncWorkData.progress(op, progress)) }
    }

    private fun finish(outcome: MlSyncOutcome): Result {
        Log.i(TAG, "${outcome.operation} ended: ${outcome.result}")
        val output = MlSyncWorkData.output(outcome)
        return when (outcome.result) {
            MlSyncResult.SUCCESS, MlSyncResult.PARTIAL, MlSyncResult.NOTHING_TO_SAVE -> Result.success(output)
            else -> Result.failure(output)
        }
    }

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what not updated: ${className(e)}")
        }
    }

    /**
     * The ongoing notification: the operation's title over an indeterminate bar, as neither the upload nor
     * the download reports bytes, with a Cancel action that stops this worker by its id.
     */
    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        ensureChannel(context)
        val restoring = operation == MlSyncOperation.RESTORE
        val title = if (restoring) R.string.ml_sync_restore_title else R.string.ml_sync_save_title
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (restoring) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
        // The type has to match the manifest's SystemForegroundService entry (dataSync) on Android 10+.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.ML_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.ML_SYNC, notification)
        }
    }

    /**
     * Where a run keeps the sealed snapshot files it uploads or has just downloaded, under the app cache. Only
     * this worker writes here and only one runs at a time, so a run starts by clearing what a killed run left.
     */
    private class TransferScratch(private val dir: File) {
        fun newFile(prefix: String): File {
            dir.mkdirs()
            if (!dir.isDirectory) throw IOException("the transfer directory is unavailable")
            return File.createTempFile(prefix, MlDriveNaming.FILE_EXTENSION, dir)
        }

        fun clear() {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        const val TAG = "ml_sync_worker"
        const val UNIQUE_WORK_NAME = "ml_sync"
        const val CHANNEL_ID = "ml_sync"

        private const val SCRATCH_DIR = "mlsync/transfer"

        /** How long a restore waits for a stood-down walk to put its model session away before importing
         *  anyway; the import's single transaction still holds the walk's writes back until it commits. */
        private const val WALK_SETTLE_TIMEOUT_MS = 30_000L

        private fun className(e: Throwable): String =
            e.javaClass.simpleName.ifEmpty { e.javaClass.name.substringAfterLast('.') }

        /** Lazily creates the ML sync notification channel. Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.ml_sync_channel_name),
                description = context.getString(R.string.ml_sync_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }

        /**
         * Enqueues [request] as the one ML sync job and returns its id. Under [ExistingWorkPolicy.KEEP] a job
         * already queued or running stays and this request is dropped. The operation also rides as a tag, so a
         * reader knows it while the job is still queued.
         */
        fun enqueue(context: Context, request: MlSyncRequest): UUID {
            ensureChannel(context)
            val work = OneTimeWorkRequestBuilder<MlSyncWorker>()
                .setInputData(MlSyncWorkData.input(request))
                .addTag(TAG)
                .addTag(MlSyncWorkData.operationTag(request.operation))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
            return work.id
        }
    }
}
