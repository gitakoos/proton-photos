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
import androidx.work.workDataOf
import eu.akoos.photos.data.mlsync.MlDriveFileKind.FACES
import eu.akoos.photos.data.mlsync.MlDriveFileKind.SEARCH
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_CREATE_FOLDER
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_DONE
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FACES_STATUS
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FAILURE
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FILE_DEVICES
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FILE_KINDS
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FILE_REVISIONS
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_FILE_TIMES
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_KIND
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_MODE
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_OPERATION
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_PHASE
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_RESULT
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_TOTAL
import eu.akoos.photos.data.mlsync.MlSyncWorkData.KEY_USER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [MlSyncWorkData]: every request, progress and outcome survives the trip through WorkManager's
 * data, an unknown or missing value reads as the default that changes nothing on the device, and the output
 * carries only the documented, non-identifying keys.
 */
class MlSyncWorkDataTest {

    private val userId = "user-7f3a"
    private val deviceA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val deviceB = "0f8b2c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"

    private val files = listOf(
        MlRestoreFile(FACES, "volume~link~revision-1", 1_700_000_000_000L, deviceA),
        MlRestoreFile(SEARCH, "volume~link~revision-2", 1_700_000_500_000L, deviceB),
    )

    private fun restoreData(
        kinds: Array<String>? = arrayOf("FACES", "SEARCH"),
        revisions: Array<String>? = arrayOf("r1", "r2"),
        times: LongArray? = longArrayOf(1L, 2L),
        devices: Array<String>? = arrayOf(deviceA, deviceA),
        mode: String? = "Replace",
    ): Data = workDataOf(
        KEY_OPERATION to "RESTORE",
        KEY_USER_ID to userId,
        KEY_MODE to mode,
        KEY_FILE_KINDS to kinds,
        KEY_FILE_REVISIONS to revisions,
        KEY_FILE_TIMES to times,
        KEY_FILE_DEVICES to devices,
    )

    @Test
    fun `a save request round-trips`() {
        listOf(true, false).forEach { confirmed ->
            val request = MlSyncRequest.Save(userId, createFolderConfirmed = confirmed)
            assertEquals(request, MlSyncWorkData.request(MlSyncWorkData.input(request)))
        }
    }

    @Test
    fun `a restore request round-trips`() {
        FaceSnapshotMode.entries.forEach { mode ->
            listOf(true, false).forEach { paused ->
                val request = MlSyncRequest.Restore(userId, files, mode, faceIndexingPausedByUser = paused)
                assertEquals(request, MlSyncWorkData.request(MlSyncWorkData.input(request)))
            }
        }
    }

    @Test
    fun `the input names its operation`() {
        val save = MlSyncRequest.Save(userId, createFolderConfirmed = false)
        val restore = MlSyncRequest.Restore(userId, files, FaceSnapshotMode.Merge, faceIndexingPausedByUser = false)
        assertEquals(MlSyncOperation.SAVE, MlSyncWorkData.operation(MlSyncWorkData.input(save)))
        assertEquals(MlSyncOperation.RESTORE, MlSyncWorkData.operation(MlSyncWorkData.input(restore)))
    }

    @Test
    fun `an unknown or missing operation is no request`() {
        assertNull(MlSyncWorkData.request(workDataOf(KEY_OPERATION to "SYNC", KEY_USER_ID to userId)))
        assertNull(MlSyncWorkData.request(workDataOf(KEY_OPERATION to "save", KEY_USER_ID to userId)))
        assertNull(MlSyncWorkData.request(workDataOf(KEY_USER_ID to userId)))
        assertNull(MlSyncWorkData.operation(Data.EMPTY))
    }

    @Test
    fun `a request without an account is no request`() {
        assertNull(MlSyncWorkData.request(workDataOf(KEY_OPERATION to "SAVE")))
        assertNull(MlSyncWorkData.request(workDataOf(KEY_OPERATION to "SAVE", KEY_USER_ID to " ")))
    }

    @Test
    fun `a save without a folder answer never creates the folder`() {
        val request = MlSyncWorkData.request(workDataOf(KEY_OPERATION to "SAVE", KEY_USER_ID to userId))
        assertEquals(MlSyncRequest.Save(userId, createFolderConfirmed = false), request)
        val odd = MlSyncWorkData.request(
            workDataOf(KEY_OPERATION to "SAVE", KEY_USER_ID to userId, KEY_CREATE_FOLDER to "yes"),
        )
        assertEquals(MlSyncRequest.Save(userId, createFolderConfirmed = false), odd)
    }

    @Test
    fun `an unknown or missing mode reads as Merge`() {
        listOf("REPLACE", "Overwrite", "", null).forEach { mode ->
            val request = MlSyncWorkData.request(restoreData(mode = mode)) as MlSyncRequest.Restore
            assertEquals("mode $mode", FaceSnapshotMode.Merge, request.mode)
        }
        val replace = MlSyncWorkData.request(restoreData(mode = "Replace")) as MlSyncRequest.Restore
        assertEquals(FaceSnapshotMode.Replace, replace.mode)
    }

    @Test
    fun `a restore file of an unknown kind or without a revision is dropped`() {
        val request = MlSyncWorkData.request(
            restoreData(
                kinds = arrayOf("FACES", "PEOPLE", "SEARCH", "SEARCH"),
                revisions = arrayOf("r1", "r2", "", "r4"),
                times = longArrayOf(1L, 2L, 3L, 4L),
                devices = arrayOf(deviceA, deviceA, deviceA, deviceB),
            ),
        ) as MlSyncRequest.Restore
        assertEquals(
            listOf(MlRestoreFile(FACES, "r1", 1L, deviceA), MlRestoreFile(SEARCH, "r4", 4L, deviceB)),
            request.files,
        )
    }

    @Test
    fun `a restore file without a well-formed install id is dropped`() {
        val request = MlSyncWorkData.request(
            restoreData(
                kinds = arrayOf("FACES", "FACES", "SEARCH", "SEARCH"),
                revisions = arrayOf("r1", "r2", "r3", "r4"),
                times = longArrayOf(1L, 2L, 3L, 4L),
                devices = arrayOf(deviceA, "", deviceA.uppercase(), "not-a-device"),
            ),
        ) as MlSyncRequest.Restore
        assertEquals(listOf(MlRestoreFile(FACES, "r1", 1L, deviceA)), request.files)
    }

    @Test
    fun `restore lists that do not line up are no request`() {
        assertNull(MlSyncWorkData.request(restoreData(revisions = arrayOf("r1"))))
        assertNull(MlSyncWorkData.request(restoreData(times = longArrayOf(1L))))
        assertNull(MlSyncWorkData.request(restoreData(devices = arrayOf(deviceA))))
        assertNull(MlSyncWorkData.request(restoreData(kinds = null)))
        assertNull(MlSyncWorkData.request(restoreData(revisions = null)))
        assertNull(MlSyncWorkData.request(restoreData(times = null)))
        assertNull(MlSyncWorkData.request(restoreData(devices = null)))
    }

    @Test
    fun `a missing pause flag reads as not paused by the user`() {
        val request = MlSyncWorkData.request(restoreData()) as MlSyncRequest.Restore
        assertFalse(request.faceIndexingPausedByUser)
    }

    @Test
    fun `progress round-trips`() {
        val progress = MlSyncProgress(MlSyncPhase.UPLOADING, SEARCH, done = 1, total = 2)
        val data = MlSyncWorkData.progress(MlSyncOperation.SAVE, progress)
        assertEquals(progress, MlSyncWorkData.progress(data))
        assertEquals(MlSyncOperation.SAVE, MlSyncWorkData.operation(data))
        val noKind = MlSyncProgress(MlSyncPhase.FINISHING, kind = null, done = 3, total = 3)
        assertEquals(noKind, MlSyncWorkData.progress(MlSyncWorkData.progress(MlSyncOperation.RESTORE, noKind)))
    }

    @Test
    fun `unknown or out of range progress reads safely`() {
        assertEquals(MlSyncProgress.QUEUED, MlSyncWorkData.progress(Data.EMPTY))
        val odd = MlSyncWorkData.progress(
            workDataOf(KEY_PHASE to "COMPRESSING", KEY_KIND to "PEOPLE", KEY_DONE to 9, KEY_TOTAL to 2),
        )
        assertEquals(MlSyncProgress(MlSyncPhase.PREPARING, kind = null, done = 2, total = 2), odd)
        val negative = MlSyncWorkData.progress(workDataOf(KEY_DONE to -4, KEY_TOTAL to -1))
        assertEquals(MlSyncProgress(MlSyncPhase.PREPARING, kind = null, done = 0, total = 0), negative)
    }

    @Test
    fun `an outcome round-trips`() {
        val outcome = MlSyncOutcome(
            operation = MlSyncOperation.RESTORE,
            result = MlSyncResult.PARTIAL,
            faces = MlSyncPartStatus.DONE,
            search = MlSyncPartStatus.MODEL_MISMATCH,
            counts = MlSyncCounts(
                faces = 1_234,
                people = 17,
                scanMarkers = 9_000,
                searchEmbeddings = 0,
                searchKept = 5,
                filesDone = 1,
                filesTotal = 2,
            ),
            failure = "SQLiteFullException",
        )
        assertEquals(outcome, MlSyncWorkData.outcome(MlSyncWorkData.output(outcome)))
        val bare = MlSyncOutcome(MlSyncOperation.SAVE, MlSyncResult.NOT_SIGNED_IN)
        assertEquals(bare, MlSyncWorkData.outcome(MlSyncWorkData.output(bare)))
    }

    @Test
    fun `output the worker never wrote reads as a failure`() {
        val outcome = MlSyncWorkData.outcome(Data.EMPTY, fallbackOperation = MlSyncOperation.SAVE)
        assertEquals(MlSyncOutcome(MlSyncOperation.SAVE, MlSyncResult.FAILED), outcome)
    }

    @Test
    fun `unknown result and status names read as failures`() {
        val outcome = MlSyncWorkData.outcome(
            workDataOf(KEY_RESULT to "EXPLODED", KEY_FACES_STATUS to "HALF_DONE"),
        )
        assertEquals(MlSyncResult.FAILED, outcome.result)
        assertEquals(MlSyncPartStatus.FAILED, outcome.faces)
        assertNull(outcome.search)
    }

    @Test
    fun `the operation in the output wins over the tags`() {
        val output = MlSyncWorkData.output(MlSyncOutcome(MlSyncOperation.RESTORE, MlSyncResult.SUCCESS))
        assertEquals(MlSyncOperation.RESTORE, MlSyncWorkData.outcome(output, MlSyncOperation.SAVE).operation)
    }

    @Test
    fun `only class names pass as a failure`() {
        listOf("IOException", "NameTaken", "RevisionDraftConflictException", "INDEX_CHANGED", "MlSyncWorker\$save\$1")
            .forEach { assertEquals(it, MlSyncWorkData.failureName(it)) }
        listOf(
            "/storage/emulated/0/Android/data/eu.akoos.photos/cache/x",
            "someone@example.com",
            "Could not upload faces-7c9e6679.pfpml",
            "java.io.IOException: No space left on device",
            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "",
            "x".repeat(81),
        ).forEach { assertNull(it, MlSyncWorkData.failureName(it)) }
    }

    @Test
    fun `an unsafe failure never reaches the output`() {
        val output = MlSyncWorkData.output(
            MlSyncOutcome(MlSyncOperation.SAVE, MlSyncResult.FAILED, failure = "faces-7c9e6679.pfpml not found"),
        )
        assertNull(output.getString(KEY_FAILURE))
    }

    @Test
    fun `the output holds only names and counts`() {
        val outcome = MlSyncOutcome(
            MlSyncOperation.RESTORE,
            MlSyncResult.SUCCESS,
            MlSyncPartStatus.DONE,
            MlSyncPartStatus.DONE,
            MlSyncCounts(1, 2, 3, 4, 5, 6, 7),
            "IOException",
        )
        val output = MlSyncWorkData.output(outcome)
        assertEquals(
            setOf(
                "op", "result", "facesStatus", "searchStatus", "failure", "faces", "people", "scanMarkers",
                "searchEmbeddings", "searchKept", "filesDone", "filesTotal",
            ),
            output.keyValueMap.keys,
        )
        output.keyValueMap.values.forEach { value ->
            assertTrue("$value", value is Int || (value is String && MlSyncWorkData.failureName(value) == value))
        }
    }

    @Test
    fun `the operation rides as a tag`() {
        MlSyncOperation.entries.forEach { op ->
            val tags = setOf("eu.akoos.photos.worker.MlSyncWorker", "ml_sync_worker", MlSyncWorkData.operationTag(op))
            assertEquals(op, MlSyncWorkData.operationFromTags(tags))
        }
        assertNull(MlSyncWorkData.operationFromTags(setOf("ml_sync_worker", "ml_sync_op_SYNC")))
        assertNull(MlSyncWorkData.operationFromTags(emptySet()))
    }
}
