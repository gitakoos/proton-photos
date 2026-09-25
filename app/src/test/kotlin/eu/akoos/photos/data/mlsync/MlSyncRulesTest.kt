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

package eu.akoos.photos.data.mlsync

import androidx.work.Data
import androidx.work.WorkInfo
import eu.akoos.photos.data.mlsync.MlDriveFileKind.FACES
import eu.akoos.photos.data.mlsync.MlDriveFileKind.SEARCH
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.CONFLICT
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.DAMAGED
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.DONE
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.EMPTY
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.FAILED
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.MODEL_MISMATCH
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.NAMES_ONLY
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.NO_SPACE
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.TOO_NEW
import eu.akoos.photos.data.mlsync.MlSyncPartStatus.WRONG_ACCOUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the ML sync decisions: which files a restore reads and in what order and mode, when a save
 * retries an upload, how the files' statuses fold into the run's result, when a job started again after a
 * crash stops, whether a snapshot is the file it was picked as, whose face pause a restore keeps and when it
 * hands the face walk back, whether a first save asks for the folder, and what the facade reports for each
 * state of the unique work.
 */
class MlSyncRulesTest {

    private val device = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    private fun file(kind: MlDriveFileKind, uid: String, time: Long) = MlRestoreFile(kind, uid, time, device)

    private fun plan(vararg files: MlRestoreFile, mode: FaceSnapshotMode = FaceSnapshotMode.Replace) =
        planRestore(files.toList(), mode)

    // ---- Restore plan -----------------------------------------------------------------------------------

    @Test
    fun `faces are restored before search whatever the request order`() {
        val steps = plan(
            file(SEARCH, "s1", 500L),
            file(FACES, "f1", 100L),
            file(SEARCH, "s2", 900L),
            file(FACES, "f2", 300L),
        )
        assertEquals(listOf(FACES, FACES, SEARCH, SEARCH), steps.map { it.file.kind })
    }

    @Test
    fun `within a kind the newest file goes first and equal times order by revision`() {
        val steps = plan(
            file(FACES, "b", 100L),
            file(FACES, "c", 300L),
            file(FACES, "a", 100L),
        )
        assertEquals(listOf("c", "a", "b"), steps.map { it.file.revisionUid })
    }

    @Test
    fun `only the first file of a kind takes a Replace`() {
        val steps = plan(
            file(FACES, "f-old", 100L),
            file(FACES, "f-new", 200L),
            file(SEARCH, "s-new", 200L),
            file(SEARCH, "s-old", 100L),
        )
        assertEquals(
            listOf("f-new" to true, "f-old" to false, "s-new" to true, "s-old" to false),
            steps.map { it.file.revisionUid to it.replace },
        )
    }

    @Test
    fun `a Merge request merges every file`() {
        val steps = plan(file(FACES, "f", 1L), file(SEARCH, "s", 1L), mode = FaceSnapshotMode.Merge)
        assertTrue(steps.none { it.replace })
        assertEquals(2, steps.size)
    }

    @Test
    fun `a repeated revision is read once and a blank one never`() {
        val steps = plan(
            file(FACES, "f1", 100L),
            file(FACES, "f1", 100L),
            file(SEARCH, " ", 100L),
            file(SEARCH, "s1", 100L),
        )
        assertEquals(listOf("f1", "s1"), steps.map { it.file.revisionUid })
    }

    @Test
    fun `a kind is capped at the newest files`() {
        val many = (1..MAX_RESTORE_FILES_PER_KIND + 3).map { file(SEARCH, "s$it", it.toLong()) }
        val steps = planRestore(many + file(FACES, "f", 1L), FaceSnapshotMode.Merge)
        val search = steps.filter { it.file.kind == SEARCH }.map { it.file.serverModifiedAtMs }
        assertEquals(MAX_RESTORE_FILES_PER_KIND, search.size)
        assertEquals((MAX_RESTORE_FILES_PER_KIND + 3 downTo 4).map { it.toLong() }, search)
        assertEquals(1, steps.count { it.file.kind == FACES })
    }

    @Test
    fun `an empty request plans nothing`() {
        assertTrue(planRestore(emptyList(), FaceSnapshotMode.Replace).isEmpty())
    }

    // ---- Save upload -------------------------------------------------------------------------------------

    @Test
    fun `a finished upload is done`() {
        val ok = MlDriveResult.Ok(MlDriveUpload("node", "revision", createdFile = true))
        assertEquals(MlUploadDecision.Uploaded, uploadDecision(ok, attempt = 1))
        assertEquals(MlUploadDecision.Uploaded, uploadDecision(ok, attempt = 2))
    }

    @Test
    fun `a conflict is retried once and then gives up`() {
        assertEquals(MlUploadDecision.Retry, uploadDecision(MlDriveResult.Conflict, attempt = 1))
        assertEquals(
            MlUploadDecision.GiveUp(CONFLICT, endsRun = null, failure = CONFLICT_FAILURE),
            uploadDecision(MlDriveResult.Conflict, attempt = MAX_UPLOAD_ATTEMPTS),
        )
    }

    @Test
    fun `a missing folder or an incapable device ends the run`() {
        assertEquals(
            MlUploadDecision.GiveUp(FAILED, endsRun = MlSyncResult.FOLDER_MISSING, failure = null),
            uploadDecision(MlDriveResult.FolderMissing, attempt = 1),
        )
        assertEquals(
            MlUploadDecision.GiveUp(FAILED, endsRun = MlSyncResult.NOT_CAPABLE, failure = null),
            uploadDecision(MlDriveResult.NotCapable, attempt = 1),
        )
    }

    @Test
    fun `any other failure ends only the file, never retried`() {
        val failed = MlDriveResult.Failed(MlDriveResult.Failed.NAME_TAKEN)
        assertEquals(
            MlUploadDecision.GiveUp(FAILED, endsRun = null, failure = MlDriveResult.Failed.NAME_TAKEN),
            uploadDecision(failed, attempt = 1),
        )
    }

    // ---- Results -----------------------------------------------------------------------------------------

    @Test
    fun `statuses fold into the run's result`() {
        assertEquals(MlSyncResult.SUCCESS, foldResult(listOf(DONE, DONE)))
        assertEquals(MlSyncResult.SUCCESS, foldResult(listOf(DONE, EMPTY)))
        assertEquals(MlSyncResult.SUCCESS, foldResult(listOf(NAMES_ONLY, MODEL_MISMATCH)))
        assertEquals(MlSyncResult.NOTHING_TO_SAVE, foldResult(listOf(EMPTY, EMPTY)))
        assertEquals(MlSyncResult.PARTIAL, foldResult(listOf(DONE, CONFLICT)))
        assertEquals(MlSyncResult.PARTIAL, foldResult(listOf(WRONG_ACCOUNT, MODEL_MISMATCH)))
        assertEquals(MlSyncResult.FAILED, foldResult(listOf(FAILED, EMPTY)))
        assertEquals(MlSyncResult.FAILED, foldResult(listOf(DAMAGED, TOO_NEW)))
        assertEquals(MlSyncResult.FAILED, foldResult(emptyList()))
    }

    @Test
    fun `a kind reports its best file and its first failure`() {
        assertEquals(DONE, mergeKindStatus(WRONG_ACCOUNT, DONE))
        assertEquals(DONE, mergeKindStatus(DONE, FAILED))
        assertEquals(DONE, mergeKindStatus(NAMES_ONLY, DONE))
        assertEquals(NAMES_ONLY, mergeKindStatus(MODEL_MISMATCH, NAMES_ONLY))
        assertEquals(EMPTY, mergeKindStatus(FAILED, EMPTY))
        assertEquals(WRONG_ACCOUNT, mergeKindStatus(WRONG_ACCOUNT, DAMAGED))
        assertEquals(DAMAGED, mergeKindStatus(null, DAMAGED))
    }

    @Test
    fun `a tally of mixed files reads as partial with the first failure named`() {
        val tally = MlSyncTally(MlSyncOperation.RESTORE, filesTotal = 3)
        tally.record(FACES, DONE)
        tally.count(faces = 10, people = 2, scanMarkers = 40)
        tally.record(FACES, WRONG_ACCOUNT)
        tally.record(SEARCH, FAILED, "SQLiteFullException")
        val outcome = tally.outcome()
        assertEquals(MlSyncResult.PARTIAL, outcome.result)
        assertEquals(DONE, outcome.faces)
        assertEquals(FAILED, outcome.search)
        assertEquals("SQLiteFullException", outcome.failure)
        assertEquals(MlSyncCounts(faces = 10, people = 2, scanMarkers = 40, filesDone = 1, filesTotal = 3), outcome.counts)
    }

    @Test
    fun `a run-ending result keeps what the files did`() {
        val tally = MlSyncTally(MlSyncOperation.SAVE, filesTotal = 2)
        tally.record(FACES, DONE)
        tally.record(SEARCH, FAILED)
        val outcome = tally.outcome(MlSyncResult.FOLDER_MISSING)
        assertEquals(MlSyncResult.FOLDER_MISSING, outcome.result)
        assertEquals(DONE, outcome.faces)
        val early = MlSyncTally(MlSyncOperation.SAVE, filesTotal = 2).outcome(MlSyncResult.FAILED, "NameTaken")
        assertEquals("NameTaken", early.failure)
        assertEquals(null, early.faces)
    }

    @Test
    fun `import results map to file statuses`() {
        assertEquals(DONE, FaceSnapshotImportResult.Imported(1, 0, 1, 1, regroupRequested = false).partStatus())
        assertEquals(NAMES_ONLY, FaceSnapshotImportResult.NamesStaged(people = 2, facesUpdated = 0).partStatus())
        assertEquals(WRONG_ACCOUNT, FaceSnapshotImportResult.WrongAccount.partStatus())
        assertEquals(TOO_NEW, FaceSnapshotImportResult.UnsupportedVersion.partStatus())
        assertEquals(DAMAGED, FaceSnapshotImportResult.Unreadable.partStatus())
        assertEquals(NO_SPACE, FaceSnapshotImportResult.NotEnoughSpace.partStatus())
        assertEquals(DONE, SearchSnapshotImportResult.Imported(imported = 3, kept = 1).partStatus())
        assertEquals(MODEL_MISMATCH, SearchSnapshotImportResult.SkippedModelMismatch("other", 768).partStatus())
        val expected = mapOf(
            SearchImportFailure.WRONG_ACCOUNT to WRONG_ACCOUNT,
            SearchImportFailure.TOO_NEW to TOO_NEW,
            SearchImportFailure.DAMAGED to DAMAGED,
            SearchImportFailure.NOT_A_SEARCH_SNAPSHOT to DAMAGED,
            SearchImportFailure.NO_SPACE to NO_SPACE,
            SearchImportFailure.NO_ACCOUNT to FAILED,
            SearchImportFailure.ERROR to FAILED,
        )
        SearchImportFailure.entries.forEach { reason ->
            assertEquals("$reason", expected.getValue(reason), SearchSnapshotImportResult.Failed(reason).partStatus())
        }
    }

    // ---- Crashes and picked files ------------------------------------------------------------------------

    @Test
    fun `a job started again after crashes stops at the cap`() {
        // The first start, then two more after the process died under the job.
        assertTrue(mlSyncRunAllowed(runAttemptCount = 0))
        assertTrue(mlSyncRunAllowed(runAttemptCount = 1))
        assertTrue(mlSyncRunAllowed(runAttemptCount = MAX_RUN_ATTEMPTS - 1))
        assertFalse(mlSyncRunAllowed(runAttemptCount = MAX_RUN_ATTEMPTS))
        assertFalse(mlSyncRunAllowed(runAttemptCount = 50))
        // The run ends with a name the output keeps.
        assertEquals(RETRY_LIMIT_FAILURE, MlSyncWorkData.failureName(RETRY_LIMIT_FAILURE))
    }

    @Test
    fun `a snapshot is taken only from the install its name carries`() {
        val other = "0f8b2c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
        assertTrue(snapshotFromExpectedDevice(headerDeviceId = device, expectedDeviceId = device))
        assertFalse(snapshotFromExpectedDevice(headerDeviceId = other, expectedDeviceId = device))
        assertFalse(snapshotFromExpectedDevice(headerDeviceId = "", expectedDeviceId = device))
        // A file picked from storage has no Drive name to hold it to.
        assertTrue(snapshotFromExpectedDevice(headerDeviceId = other, expectedDeviceId = null))
    }

    @Test
    fun `a restore step keeps the install its file was picked as`() {
        val steps = plan(file(FACES, "f1", 1L), file(SEARCH, "s1", 1L))
        assertTrue(steps.all { it.file.deviceId == device })
    }

    // ---- Face walk hand-back -----------------------------------------------------------------------------

    @Test
    fun `the face walk comes back unless the user had paused it`() {
        assertTrue(faceWalkResumes(heldByMlSync = true, pausedByUserAtStart = false))
        // A pause turned on after the tap without a hold, and a stand-down the store refused: prefer resuming.
        assertTrue(faceWalkResumes(heldByMlSync = false, pausedByUserAtStart = false))
        // Resumed by the user between the tap and the run, then held here: back on.
        assertTrue(faceWalkResumes(heldByMlSync = true, pausedByUserAtStart = true))
        assertFalse(faceWalkResumes(heldByMlSync = false, pausedByUserAtStart = true))
    }

    @Test
    fun `only a pause no run holds is the user's`() {
        assertTrue(faceIndexingPausedByUser(pauseSwitchOn = true, heldByMlSync = false))
        assertFalse(faceIndexingPausedByUser(pauseSwitchOn = false, heldByMlSync = false))
        assertFalse(faceIndexingPausedByUser(pauseSwitchOn = false, heldByMlSync = true))
        // Unreadable: taken as the app's, so the restore hands the walk back.
        assertFalse(faceIndexingPausedByUser(pauseSwitchOn = null, heldByMlSync = null))
        assertFalse(faceIndexingPausedByUser(pauseSwitchOn = true, heldByMlSync = null))
    }

    @Test
    fun `a pause a killed restore left behind is handed back by the next restore`() {
        // The switch is still on with the hold an earlier restore wrote with it, when the next Restore is tapped.
        val pausedByUser = faceIndexingPausedByUser(pauseSwitchOn = true, heldByMlSync = true)
        assertFalse(pausedByUser)
        // Its stand-down finds the earlier hold and takes it over, and its hand-back restarts the walk.
        assertTrue(faceWalkResumes(heldByMlSync = true, pausedByUserAtStart = pausedByUser))
        // Even had its own hold not been written, a pause that was not the user's is never kept.
        assertTrue(faceWalkResumes(heldByMlSync = false, pausedByUserAtStart = pausedByUser))
    }

    @Test
    fun `a pause the user set before the restore stays`() {
        val pausedByUser = faceIndexingPausedByUser(pauseSwitchOn = true, heldByMlSync = false)
        assertTrue(pausedByUser)
        // The stand-down finds the user's switch on and holds nothing, so the hand-back keeps it.
        assertFalse(faceWalkResumes(heldByMlSync = false, pausedByUserAtStart = pausedByUser))
    }

    // ---- First save --------------------------------------------------------------------------------------

    @Test
    fun `a save with no folder yet asks before anything runs`() {
        assertEquals(MlSaveStep.ASK_TO_CREATE_FOLDER, saveStep(MlDriveResult.Ok(null)))
        assertEquals(MlSaveStep.ASK_TO_CREATE_FOLDER, saveStep(MlDriveResult.FolderMissing))
    }

    @Test
    fun `a save with a folder runs`() {
        assertEquals(MlSaveStep.START, saveStep(MlDriveResult.Ok(MlDriveFolder("folder"))))
    }

    @Test
    fun `a failed folder lookup starts the save, which looks again and reports what it finds`() {
        assertEquals(MlSaveStep.START, saveStep(MlDriveResult.Failed("IOException")))
        assertEquals(MlSaveStep.START, saveStep(MlDriveResult.Conflict))
        assertEquals(MlSaveStep.NOT_CAPABLE, saveStep(MlDriveResult.NotCapable))
    }

    // ---- Remote listing ----------------------------------------------------------------------------------

    @Test
    fun `the listing flags this device and labels only its own files`() {
        val own = "0f8b2c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
        val other = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        fun pick(kind: MlDriveFileKind, deviceId: String) = MlDriveImport(
            kind,
            deviceId,
            MlDriveFile(
                nodeUid = "node-$deviceId-$kind",
                name = MlDriveNaming.fileName(kind, deviceId),
                sizeBytes = 2_048L,
                activeRevisionUid = "rev-$deviceId-$kind",
                serverModifiedAtMs = 42L,
                serverCreatedAtMs = 1L,
                claimedModifiedAtMs = 40L,
            ),
        )
        val entries = remoteSnapshots(listOf(pick(FACES, other), pick(SEARCH, own)), own, "Model X")
        assertEquals(
            listOf(
                MlRemoteSnapshot(FACES, other, isThisDevice = false, deviceLabel = null, 42L, 2_048L, "rev-$other-FACES", savedAtMs = 40L),
                MlRemoteSnapshot(SEARCH, own, isThisDevice = true, deviceLabel = "Model X", 42L, 2_048L, "rev-$own-SEARCH", savedAtMs = 40L),
            ),
            entries,
        )
    }

    @Test
    fun `the listing shows the saving install's own time and falls back to the server time without one`() {
        val device = "0f8b2c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
        fun pick(claimed: Long?) = MlDriveImport(
            FACES,
            device,
            MlDriveFile(
                nodeUid = "node",
                name = MlDriveNaming.fileName(FACES, device),
                sizeBytes = 2_048L,
                activeRevisionUid = "rev",
                serverModifiedAtMs = 1_000L,
                serverCreatedAtMs = 1L,
                claimedModifiedAtMs = claimed,
            ),
        )
        assertEquals(8_200_000L, remoteSnapshots(listOf(pick(8_200_000L)), device, "Model X").single().savedAtMs)
        assertEquals(1_000L, remoteSnapshots(listOf(pick(null)), device, "Model X").single().savedAtMs)
    }

    // ---- Facade state ------------------------------------------------------------------------------------

    private fun work(
        id: String,
        state: WorkInfo.State,
        operation: MlSyncOperation? = MlSyncOperation.RESTORE,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ) = MlSyncWorkSnapshot(id, state, operation, progress, output)

    @Test
    fun `no work is idle`() {
        assertEquals(MlSyncState.Idle, mlSyncState(emptyList(), emptySet(), emptySet()))
    }

    @Test
    fun `a queued job runs from the start`() {
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED).forEach { state ->
            assertEquals(
                MlSyncState.Running(MlSyncOperation.SAVE, MlSyncProgress.QUEUED),
                mlSyncState(listOf(work("a", state, MlSyncOperation.SAVE)), emptySet(), emptySet()),
            )
        }
    }

    @Test
    fun `a running job reports its progress`() {
        val progress = MlSyncProgress(MlSyncPhase.IMPORTING, SEARCH, done = 1, total = 2)
        val data = MlSyncWorkData.progress(MlSyncOperation.RESTORE, progress)
        assertEquals(
            MlSyncState.Running(MlSyncOperation.RESTORE, progress),
            mlSyncState(listOf(work("a", WorkInfo.State.RUNNING, progress = data)), emptySet(), emptySet()),
        )
        // Without the tag, the progress still names the operation.
        assertEquals(
            MlSyncState.Running(MlSyncOperation.RESTORE, progress),
            mlSyncState(listOf(work("a", WorkInfo.State.RUNNING, operation = null, progress = data)), emptySet(), emptySet()),
        )
    }

    @Test
    fun `a finished job this process watched reports its outcome until dismissed`() {
        val outcome = MlSyncOutcome(MlSyncOperation.RESTORE, MlSyncResult.SUCCESS, faces = DONE)
        val works = listOf(work("a", WorkInfo.State.SUCCEEDED, output = MlSyncWorkData.output(outcome)))
        assertEquals(MlSyncState.Finished("a", outcome), mlSyncState(works, setOf("a"), emptySet()))
        assertEquals(MlSyncState.Idle, mlSyncState(works, setOf("a"), setOf("a")))
    }

    @Test
    fun `a failed job reports its outcome and one without output reads as failed`() {
        val outcome = MlSyncOutcome(MlSyncOperation.SAVE, MlSyncResult.FOLDER_MISSING)
        val failed = listOf(work("a", WorkInfo.State.FAILED, MlSyncOperation.SAVE, output = MlSyncWorkData.output(outcome)))
        assertEquals(MlSyncState.Finished("a", outcome), mlSyncState(failed, setOf("a"), emptySet()))
        val crashed = listOf(work("b", WorkInfo.State.FAILED, MlSyncOperation.SAVE))
        assertEquals(
            MlSyncState.Finished("b", MlSyncOutcome(MlSyncOperation.SAVE, MlSyncResult.FAILED)),
            mlSyncState(crashed, setOf("b"), emptySet()),
        )
    }

    @Test
    fun `a result kept from an earlier process stays hidden`() {
        val works = listOf(work("old", WorkInfo.State.SUCCEEDED))
        assertEquals(MlSyncState.Idle, mlSyncState(works, emptySet(), emptySet()))
    }

    @Test
    fun `a cancelled job is idle`() {
        val works = listOf(work("a", WorkInfo.State.CANCELLED))
        assertEquals(MlSyncState.Idle, mlSyncState(works, setOf("a"), emptySet()))
    }

    @Test
    fun `a live job outranks a finished one`() {
        val works = listOf(
            work("old", WorkInfo.State.SUCCEEDED),
            work("new", WorkInfo.State.ENQUEUED, MlSyncOperation.SAVE),
        )
        assertEquals(
            MlSyncState.Running(MlSyncOperation.SAVE, MlSyncProgress.QUEUED),
            mlSyncState(works, setOf("old", "new"), emptySet()),
        )
    }
}
