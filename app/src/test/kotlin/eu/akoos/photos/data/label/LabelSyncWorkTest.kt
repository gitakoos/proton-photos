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

package eu.akoos.photos.data.label

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The label sync job's request, progress and outcome through WorkManager's data, and the state read from it. */
class LabelSyncWorkTest {

    @Test
    fun `a save request survives the trip with its folder answer`() {
        val request = LabelSyncRequest.Save("user-1", createFolderConfirmed = true)

        assertEquals(request, LabelSyncWorkData.request(LabelSyncWorkData.input(request)))
        assertEquals(
            LabelSyncRequest.Save("user-1", createFolderConfirmed = false),
            LabelSyncWorkData.request(LabelSyncWorkData.input(LabelSyncRequest.Save("user-1", false))),
        )
    }

    @Test
    fun `a restore request survives the trip with the copy it names`() {
        val request = LabelSyncRequest.Restore("user-1", DEVICE, "revision-9")

        assertEquals(request, LabelSyncWorkData.request(LabelSyncWorkData.input(request)))
    }

    @Test
    fun `a restore without a well-formed install id or a revision cannot be read`() {
        fun restore(device: String?, revision: String?) = LabelSyncWorkData.request(
            androidx.work.Data.Builder()
                .putString(LabelSyncWorkData.KEY_OPERATION, "RESTORE")
                .putString(LabelSyncWorkData.KEY_USER_ID, "u")
                .putString(LabelSyncWorkData.KEY_DEVICE_ID, device)
                .putString(LabelSyncWorkData.KEY_REVISION, revision)
                .build(),
        )

        assertEquals(LabelSyncRequest.Restore("u", DEVICE, "r"), restore(DEVICE, "r"))
        assertNull(restore(null, "r"))
        assertNull(restore("not-a-device", "r"))
        assertNull(restore(DEVICE, null))
        assertNull(restore(DEVICE, "  "))
    }

    @Test
    fun `a request without an account or with an unknown operation cannot be read`() {
        assertNull(LabelSyncWorkData.request(workDataOf(LabelSyncWorkData.KEY_OPERATION to "SAVE")))
        assertNull(LabelSyncWorkData.request(workDataOf(LabelSyncWorkData.KEY_USER_ID to "u")))
        assertNull(
            LabelSyncWorkData.request(
                workDataOf(LabelSyncWorkData.KEY_OPERATION to "TELEPORT", LabelSyncWorkData.KEY_USER_ID to "u"),
            ),
        )
    }

    @Test
    fun `a save with no folder answer reads as not confirmed`() {
        val data = workDataOf(LabelSyncWorkData.KEY_OPERATION to "SAVE", LabelSyncWorkData.KEY_USER_ID to "u")

        assertEquals(LabelSyncRequest.Save("u", createFolderConfirmed = false), LabelSyncWorkData.request(data))
    }

    @Test
    fun `progress survives the trip`() {
        for (step in LabelBackupStep.entries) {
            val progress = LabelBackupProgress(step)

            assertEquals(progress, LabelSyncWorkData.progress(LabelSyncWorkData.progress(LabelBackupOperation.RESTORE, progress)))
        }
    }

    @Test
    fun `an unknown step reads as preparing`() {
        val data = workDataOf(LabelSyncWorkData.KEY_STEP to "TELEPORTING")

        assertEquals(LabelBackupStep.PREPARING, LabelSyncWorkData.progress(data).step)
    }

    @Test
    fun `an outcome survives the trip`() {
        val outcome = LabelBackupOutcome(LabelBackupOperation.RESTORE, LabelBackupResult.RESTORED, photos = 4, newLabels = 2)

        assertEquals(outcome, LabelSyncWorkData.outcome(LabelSyncWorkData.output(outcome)))
    }

    @Test
    fun `output a run never wrote reads as a failure of the operation its tag names`() {
        val outcome = LabelSyncWorkData.outcome(Data.EMPTY, fallbackOperation = LabelBackupOperation.RESTORE)

        assertEquals(LabelBackupOperation.RESTORE, outcome.operation)
        assertEquals(LabelBackupResult.FAILED, outcome.result)
    }

    @Test
    fun `the operation rides as a tag`() {
        val tag = LabelSyncWorkData.operationTag(LabelBackupOperation.SAVE)

        assertEquals(LabelBackupOperation.SAVE, LabelSyncWorkData.operationFromTags(listOf("other", tag)))
        assertNull(LabelSyncWorkData.operationFromTags(listOf("other")))
    }

    private companion object {
        const val DEVICE = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
    }

    private fun work(
        id: String,
        state: WorkInfo.State,
        op: LabelBackupOperation? = null,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = LabelSyncWorkSnapshot(id, state, op, progress, output)

    @Test
    fun `nothing queued reads as idle`() {
        assertEquals(LabelBackupState.Idle, labelSyncState(emptyList(), emptySet(), emptySet()))
    }

    @Test
    fun `a queued or running job reads as running, a running one with its step`() {
        val queued = labelSyncState(
            listOf(work("a", WorkInfo.State.ENQUEUED, LabelBackupOperation.SAVE)), emptySet(), emptySet(),
        )
        assertEquals(LabelBackupState.Running(LabelBackupOperation.SAVE, LabelBackupProgress.QUEUED), queued)

        val progress = LabelBackupProgress(LabelBackupStep.UPLOADING)
        val running = labelSyncState(
            listOf(
                work(
                    "a", WorkInfo.State.RUNNING, LabelBackupOperation.SAVE,
                    progress = LabelSyncWorkData.progress(LabelBackupOperation.SAVE, progress),
                ),
            ),
            emptySet(), emptySet(),
        )
        assertEquals(LabelBackupState.Running(LabelBackupOperation.SAVE, progress), running)
    }

    @Test
    fun `a finished job reads as finished only when this process watched it and it was not dismissed`() {
        val output = LabelSyncWorkData.output(LabelBackupOutcome(LabelBackupOperation.SAVE, LabelBackupResult.SAVED, 3))
        val done = work("a", WorkInfo.State.SUCCEEDED, LabelBackupOperation.SAVE, output = output)

        assertEquals(LabelBackupState.Idle, labelSyncState(listOf(done), emptySet(), emptySet()))
        assertEquals(LabelBackupState.Idle, labelSyncState(listOf(done), setOf("a"), setOf("a")))
        val finished = labelSyncState(listOf(done), setOf("a"), emptySet()) as LabelBackupState.Finished
        assertEquals("a", finished.runId)
        assertEquals(LabelBackupResult.SAVED, finished.outcome.result)
        assertEquals(3, finished.outcome.photos)
    }

    @Test
    fun `a cancelled job reads as idle`() {
        val cancelled = work("a", WorkInfo.State.CANCELLED, LabelBackupOperation.SAVE)

        assertEquals(LabelBackupState.Idle, labelSyncState(listOf(cancelled), setOf("a"), emptySet()))
    }

    @Test
    fun `a job still to finish wins over an older finished one`() {
        val old = work("old", WorkInfo.State.SUCCEEDED, LabelBackupOperation.SAVE)
        val new = work("new", WorkInfo.State.RUNNING, LabelBackupOperation.RESTORE)

        val state = labelSyncState(listOf(old, new), setOf("old", "new"), emptySet())

        assertEquals(LabelBackupOperation.RESTORE, (state as LabelBackupState.Running).operation)
    }
}
