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

import eu.akoos.photos.R
import eu.akoos.photos.data.mlsync.FaceSnapshotMode
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveFileKind.FACES
import eu.akoos.photos.data.mlsync.MlDriveFileKind.SEARCH
import eu.akoos.photos.data.mlsync.MlRemoteSnapshot
import eu.akoos.photos.data.mlsync.MlSyncCounts
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncOperation.RESTORE
import eu.akoos.photos.data.mlsync.MlSyncOperation.SAVE
import eu.akoos.photos.data.mlsync.MlSyncOutcome
import eu.akoos.photos.data.mlsync.MlSyncPartStatus
import eu.akoos.photos.data.mlsync.MlSyncPhase
import eu.akoos.photos.data.mlsync.MlSyncProgress
import eu.akoos.photos.data.mlsync.MlSyncResult
import eu.akoos.photos.data.mlsync.MlSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the rules behind the "Face and search data on Drive" section: when it shows and what its card
 * holds, a running job's words and bar, how the Drive listing groups and names the saved sets per device,
 * when a restore asks Merge or Replace, how a first save waits for the folder question, and what the section
 * says for every way a job can end.
 */
class MlDriveSyncRulesTest {

    private val running = MlSyncState.Running(SAVE, MlSyncProgress.QUEUED)
    private val finished = MlSyncState.Finished("run", MlSyncOutcome(SAVE, MlSyncResult.SUCCESS))

    private fun snapshot(
        kind: MlDriveFileKind,
        device: String,
        time: Long,
        size: Long = 100L,
        thisDevice: Boolean = false,
        label: String? = null,
        savedAt: Long = time,
    ) = MlRemoteSnapshot(
        kind = kind,
        deviceId = device,
        isThisDevice = thisDevice,
        deviceLabel = label,
        serverModifiedAtMs = time,
        sizeBytes = size,
        revisionUid = "$device-${kind.prefix}-$time",
        savedAtMs = savedAt,
    )

    private fun progress(phase: MlSyncPhase, kind: MlDriveFileKind?, done: Int = 0, total: Int = 2) =
        MlSyncProgress(phase, kind, done, total)

    private fun outcome(
        operation: MlSyncOperation?,
        result: MlSyncResult,
        faces: MlSyncPartStatus? = null,
        search: MlSyncPartStatus? = null,
        counts: MlSyncCounts = MlSyncCounts(),
        failure: String? = null,
    ) = MlSyncOutcome(operation, result, faces, search, counts, failure)

    private fun message(outcome: MlSyncOutcome): MlSyncNotice.Message =
        mlSyncNotice(outcome) as MlSyncNotice.Message

    // ---- When the section shows ---------------------------------------------------------------------------

    @Test
    fun `a guest never sees the section`() {
        assertEquals(
            MlDriveCardMode.HIDDEN,
            mlDriveCardMode(mlEnabled = true, signedIn = false, driveAvailable = true, state = MlSyncState.Idle),
        )
        assertEquals(
            MlDriveCardMode.HIDDEN,
            mlDriveCardMode(mlEnabled = true, signedIn = false, driveAvailable = false, state = running),
        )
    }

    @Test
    fun `a device the Drive copy cannot run on hides the section`() {
        assertEquals(
            MlDriveCardMode.HIDDEN,
            mlDriveCardMode(mlEnabled = true, signedIn = true, driveAvailable = false, state = MlSyncState.Idle),
        )
    }

    @Test
    fun `the section is hidden while machine learning is off, even with a job on`() {
        assertEquals(
            MlDriveCardMode.HIDDEN,
            mlDriveCardMode(mlEnabled = false, signedIn = true, driveAvailable = true, state = running),
        )
    }

    @Test
    fun `a signed-in account on a capable device gets the actions, or the progress while a job runs`() {
        assertEquals(
            MlDriveCardMode.ACTIONS,
            mlDriveCardMode(mlEnabled = true, signedIn = true, driveAvailable = true, state = MlSyncState.Idle),
        )
        assertEquals(
            MlDriveCardMode.PROGRESS,
            mlDriveCardMode(mlEnabled = true, signedIn = true, driveAvailable = true, state = running),
        )
    }

    @Test
    fun `a finished job leaves the actions ready while its message shows`() {
        assertEquals(
            MlDriveCardMode.ACTIONS,
            mlDriveCardMode(mlEnabled = true, signedIn = true, driveAvailable = true, state = finished),
        )
    }

    // ---- A running job -----------------------------------------------------------------------------------

    @Test
    fun `the headline names the job as its notification does`() {
        assertEquals(R.string.ml_sync_save_title, mlSyncHeadline(SAVE))
        assertEquals(R.string.ml_sync_restore_title, mlSyncHeadline(RESTORE))
        assertEquals(R.string.ml_sync_channel_name, mlSyncHeadline(null))
    }

    @Test
    fun `every step names the kind of data it works on`() {
        assertEquals(R.string.settings_ml_drive_step_preparing, mlSyncStepLabel(MlSyncProgress.QUEUED))
        assertEquals(
            R.string.settings_ml_drive_step_encrypting_faces,
            mlSyncStepLabel(progress(MlSyncPhase.EXPORTING, FACES)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_encrypting_search,
            mlSyncStepLabel(progress(MlSyncPhase.EXPORTING, SEARCH)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_uploading_faces,
            mlSyncStepLabel(progress(MlSyncPhase.UPLOADING, FACES)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_uploading_search,
            mlSyncStepLabel(progress(MlSyncPhase.UPLOADING, SEARCH)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_downloading_faces,
            mlSyncStepLabel(progress(MlSyncPhase.DOWNLOADING, FACES)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_downloading_search,
            mlSyncStepLabel(progress(MlSyncPhase.DOWNLOADING, SEARCH)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_importing_faces,
            mlSyncStepLabel(progress(MlSyncPhase.IMPORTING, FACES)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_importing_search,
            mlSyncStepLabel(progress(MlSyncPhase.IMPORTING, SEARCH)),
        )
        assertEquals(
            R.string.settings_ml_drive_step_finishing,
            mlSyncStepLabel(progress(MlSyncPhase.FINISHING, kind = null, done = 2)),
        )
    }

    @Test
    fun `a file step that names no kind shows no step line`() {
        assertNull(mlSyncStepLabel(progress(MlSyncPhase.UPLOADING, kind = null)))
        assertNull(mlSyncStepLabel(progress(MlSyncPhase.IMPORTING, kind = null)))
    }

    @Test
    fun `the bar runs indeterminate while nothing measurable moves`() {
        assertNull(mlSyncProgressFraction(MlSyncProgress.QUEUED))
        assertNull(mlSyncProgressFraction(progress(MlSyncPhase.PREPARING, kind = null)))
        assertNull(mlSyncProgressFraction(progress(MlSyncPhase.FINISHING, kind = null, done = 2)))
        assertNull(mlSyncProgressFraction(progress(MlSyncPhase.UPLOADING, FACES, total = 0)))
    }

    @Test
    fun `each file fills two halves of its share of the bar`() {
        assertEquals(0f, mlSyncProgressFraction(progress(MlSyncPhase.EXPORTING, FACES, done = 0))!!, 0f)
        assertEquals(0.25f, mlSyncProgressFraction(progress(MlSyncPhase.UPLOADING, FACES, done = 0))!!, 0f)
        assertEquals(0.5f, mlSyncProgressFraction(progress(MlSyncPhase.DOWNLOADING, SEARCH, done = 1))!!, 0f)
        assertEquals(0.75f, mlSyncProgressFraction(progress(MlSyncPhase.IMPORTING, SEARCH, done = 1))!!, 0f)
        assertEquals(
            0.5f,
            mlSyncProgressFraction(progress(MlSyncPhase.IMPORTING, FACES, done = 0, total = 1))!!,
            0f,
        )
    }

    @Test
    fun `the bar never runs past full`() {
        assertEquals(1f, mlSyncProgressFraction(progress(MlSyncPhase.UPLOADING, SEARCH, done = 2))!!, 0f)
    }

    // ---- The restore list ----------------------------------------------------------------------------------

    @Test
    fun `the listing groups into one set per device, the most recently saved first`() {
        val devices = remoteDevices(
            listOf(
                snapshot(FACES, "old", time = 100L),
                snapshot(SEARCH, "new", time = 900L),
                snapshot(SEARCH, "old", time = 150L),
                snapshot(FACES, "new", time = 880L),
            ),
        )
        assertEquals(listOf("new", "old"), devices.map { it.deviceId })
        assertEquals(listOf(900L, 150L), devices.map { it.savedAtMs })
        assertEquals(listOf("new-search-900", "new-faces-880"), devices[0].files.map { it.revisionUid })
    }

    @Test
    fun `a set holding both kinds offers both, faces first, and one kind offers no choice`() {
        val both = remoteDevices(listOf(snapshot(SEARCH, "d", time = 2L), snapshot(FACES, "d", time = 1L))).single()
        assertEquals(listOf(FACES, SEARCH), restorableKinds(both))
        val searchOnly = remoteDevices(listOf(snapshot(SEARCH, "s", time = 2L))).single()
        assertEquals(listOf(SEARCH), restorableKinds(searchOnly))
    }

    @Test
    fun `unticking a kind leaves its file out of the restore`() {
        val device = remoteDevices(listOf(snapshot(SEARCH, "d", time = 2L), snapshot(FACES, "d", time = 1L))).single()
        val pick = MlRestoreSheet.Pick(listOf(device), "d", device.kinds)
        val facesOnly = toggledRestoreKinds(pick, SEARCH)
        assertEquals(setOf(FACES), facesOnly)
        assertEquals(listOf(FACES), filesToRestore(device, facesOnly).map { it.kind })
        assertEquals(setOf(FACES, SEARCH), toggledRestoreKinds(pick.copy(kinds = facesOnly), SEARCH))
        assertTrue(filesToRestore(device, emptySet()).isEmpty())
    }

    @Test
    fun `a kind the picked set does not hold cannot be ticked`() {
        val device = remoteDevices(listOf(snapshot(FACES, "d", time = 1L))).single()
        val pick = MlRestoreSheet.Pick(listOf(device), "d", device.kinds)
        assertEquals(setOf(FACES), toggledRestoreKinds(pick, SEARCH))
        assertEquals(emptySet<MlDriveFileKind>(), toggledRestoreKinds(pick.copy(selectedId = null, kinds = emptySet()), FACES))
    }

    @Test
    fun `a set shows and orders by its own saved time, not the server time`() {
        val devices = remoteDevices(
            listOf(
                snapshot(FACES, "a", time = 100L, savedAt = 7_300L),
                snapshot(FACES, "b", time = 900L, savedAt = 200L),
            ),
        )
        assertEquals(listOf("a", "b"), devices.map { it.deviceId })
        assertEquals(7_300L, devices[0].savedAtMs)
    }

    @Test
    fun `sets saved at the same moment order by device id`() {
        val devices = remoteDevices(listOf(snapshot(FACES, "b", time = 500L), snapshot(FACES, "a", time = 500L)))
        assertEquals(listOf("a", "b"), devices.map { it.deviceId })
    }

    @Test
    fun `a set adds up the size of its files and ignores a negative one`() {
        val device = remoteDevices(
            listOf(snapshot(FACES, "d", time = 1L, size = 300L), snapshot(SEARCH, "d", time = 2L, size = -5L)),
        ).single()
        assertEquals(300L, device.sizeBytes)
    }

    @Test
    fun `this install's set reads as this device`() {
        val device = remoteDevices(listOf(snapshot(FACES, "me", time = 1L, thisDevice = true, label = "Pixel 8")))
            .single()
        assertEquals(MlDeviceName.ThisDevice, device.name)
    }

    @Test
    fun `another install reads as another device until its label is known`() {
        val devices = remoteDevices(
            listOf(
                snapshot(FACES, "x", time = 3L),
                snapshot(FACES, "y", time = 2L, label = "  Galaxy Tab  "),
                snapshot(FACES, "z", time = 1L, label = "   "),
            ),
        )
        assertEquals(
            listOf(MlDeviceName.Another, MlDeviceName.Labeled("Galaxy Tab"), MlDeviceName.Another),
            devices.map { it.name },
        )
    }

    @Test
    fun `a set lists what it holds`() {
        val both = remoteDevices(listOf(snapshot(FACES, "a", time = 1L), snapshot(SEARCH, "a", time = 1L))).single()
        val faces = remoteDevices(listOf(snapshot(FACES, "b", time = 1L))).single()
        val search = remoteDevices(listOf(snapshot(SEARCH, "c", time = 1L))).single()
        assertEquals(R.string.settings_ml_drive_holds_both, mlRemoteDeviceContents(both))
        assertEquals(R.string.settings_ml_drive_holds_faces, mlRemoteDeviceContents(faces))
        assertEquals(R.string.settings_ml_drive_holds_search, mlRemoteDeviceContents(search))
        assertTrue(both.hasFaceData)
        assertTrue(faces.hasFaceData)
        assertFalse(search.hasFaceData)
    }

    @Test
    fun `an empty listing has no sets`() {
        assertTrue(remoteDevices(emptyList()).isEmpty())
    }

    @Test
    fun `only a lone set starts picked`() {
        val one = remoteDevices(listOf(snapshot(FACES, "a", time = 1L)))
        val two = remoteDevices(listOf(snapshot(FACES, "a", time = 1L), snapshot(FACES, "b", time = 2L)))
        assertEquals("a", initialRestorePick(one))
        assertNull(initialRestorePick(two))
        assertNull(initialRestorePick(emptyList()))
    }

    // ---- Merge or Replace ----------------------------------------------------------------------------------

    @Test
    fun `face data on a device that already holds some asks the user`() {
        assertNull(restoreModeWithoutAsking(hasFaceData = true, needsChoice = true))
    }

    @Test
    fun `face data on a fresh device replaces without asking`() {
        assertEquals(FaceSnapshotMode.Replace, restoreModeWithoutAsking(hasFaceData = true, needsChoice = false))
    }

    @Test
    fun `search data alone never asks, as it carries no names`() {
        assertEquals(FaceSnapshotMode.Replace, restoreModeWithoutAsking(hasFaceData = false, needsChoice = true))
        assertEquals(FaceSnapshotMode.Replace, restoreModeWithoutAsking(hasFaceData = false, needsChoice = false))
    }

    // ---- A first save and its folder question -------------------------------------------------------------

    @Test
    fun `a save looking up its folder shows as a save being prepared`() {
        assertEquals(
            MlSyncState.Running(SAVE, MlSyncProgress.QUEUED),
            mlSyncShownState(MlSyncState.Idle, preparingSave = true),
        )
        assertEquals(
            MlDriveCardMode.PROGRESS,
            mlDriveCardMode(
                mlEnabled = true,
                signedIn = true,
                driveAvailable = true,
                state = mlSyncShownState(MlSyncState.Idle, preparingSave = true),
            ),
        )
        assertEquals(MlSyncState.Idle, mlSyncShownState(MlSyncState.Idle, preparingSave = false))
        assertEquals(finished, mlSyncShownState(finished, preparingSave = false))
    }

    @Test
    fun `a job that runs is shown as it is`() {
        val restoring = MlSyncState.Running(RESTORE, progress(MlSyncPhase.IMPORTING, FACES))
        assertEquals(restoring, mlSyncShownState(restoring, preparingSave = true))
    }

    @Test
    fun `a missing folder shows only the folder question, never a failure`() {
        assertEquals(MlSyncNotice.AskToCreateFolder, mlSyncSheet(folderPrompt = true, state = MlSyncState.Idle))
        // A job that still found no folder asks the same question.
        val noFolder = MlSyncState.Finished("run", outcome(SAVE, MlSyncResult.FOLDER_MISSING))
        assertEquals(MlSyncNotice.AskToCreateFolder, mlSyncSheet(folderPrompt = false, state = noFolder))
        assertEquals(MlSyncNotice.AskToCreateFolder, mlSyncSheet(folderPrompt = true, state = noFolder))
        // Whatever it carries, a save that ended without a folder never reads as "Could not save to Drive".
        for (faces in listOf(null, MlSyncPartStatus.FAILED, MlSyncPartStatus.DONE)) {
            val ended = MlSyncState.Finished("run", outcome(SAVE, MlSyncResult.FOLDER_MISSING, faces = faces))
            assertEquals("$faces", MlSyncNotice.AskToCreateFolder, mlSyncSheet(folderPrompt = false, state = ended))
        }
    }

    @Test
    fun `nothing opens over the section while no question or result waits`() {
        assertNull(mlSyncSheet(folderPrompt = false, state = MlSyncState.Idle))
        assertNull(mlSyncSheet(folderPrompt = false, state = running))
    }

    @Test
    fun `a finished job's message opens when no folder question waits`() {
        assertEquals(mlSyncNotice(finished.outcome), mlSyncSheet(folderPrompt = false, state = finished))
    }

    // ---- What a finished job says ------------------------------------------------------------------------

    @Test
    fun `a save that found no folder asks before creating one`() {
        assertEquals(MlSyncNotice.AskToCreateFolder, mlSyncNotice(outcome(SAVE, MlSyncResult.FOLDER_MISSING)))
        assertEquals(MlSyncNotice.AskToCreateFolder, mlSyncNotice(outcome(null, MlSyncResult.FOLDER_MISSING)))
    }

    @Test
    fun `only a missing folder on a save asks before creating one`() {
        for (operation in listOf(SAVE, RESTORE, null)) {
            for (result in MlSyncResult.entries) {
                val notice = mlSyncNotice(outcome(operation, result))
                val asks = operation != RESTORE && result == MlSyncResult.FOLDER_MISSING
                assertEquals("$operation $result", asks, notice == MlSyncNotice.AskToCreateFolder)
            }
        }
    }

    @Test
    fun `a successful save lists what it saved`() {
        val notice = message(
            outcome(
                SAVE,
                MlSyncResult.SUCCESS,
                faces = MlSyncPartStatus.DONE,
                search = MlSyncPartStatus.DONE,
                counts = MlSyncCounts(faces = 120, people = 4, scanMarkers = 900, searchEmbeddings = 850),
            ),
        )
        assertEquals(R.string.settings_ml_drive_saved, notice.title)
        assertEquals(
            listOf(
                MlCountPart(R.plurals.settings_ml_drive_count_faces, 120),
                MlCountPart(R.plurals.settings_ml_drive_count_people, 4),
                MlCountPart(R.plurals.settings_ml_drive_count_search, 850),
            ),
            notice.counts,
        )
        assertTrue(notice.lines.isEmpty())
    }

    @Test
    fun `counts of zero are left out of the list`() {
        val notice = message(
            outcome(
                SAVE,
                MlSyncResult.SUCCESS,
                search = MlSyncPartStatus.DONE,
                faces = MlSyncPartStatus.EMPTY,
                counts = MlSyncCounts(searchEmbeddings = 30),
            ),
        )
        assertEquals(listOf(MlCountPart(R.plurals.settings_ml_drive_count_search, 30)), notice.counts)
        assertTrue(message(outcome(SAVE, MlSyncResult.SUCCESS)).counts.isEmpty())
    }

    @Test
    fun `a restore that took only names or no search data says so`() {
        val notice = message(
            outcome(
                RESTORE,
                MlSyncResult.SUCCESS,
                faces = MlSyncPartStatus.NAMES_ONLY,
                search = MlSyncPartStatus.MODEL_MISMATCH,
                counts = MlSyncCounts(faces = 10, people = 2),
            ),
        )
        assertEquals(R.string.settings_ml_drive_restored, notice.title)
        assertEquals(
            listOf(R.string.settings_ml_drive_names_only, R.string.settings_ml_drive_search_other_model),
            notice.lines,
        )
    }

    @Test
    fun `a full restore needs no note`() {
        val notice = message(
            outcome(
                RESTORE,
                MlSyncResult.SUCCESS,
                faces = MlSyncPartStatus.DONE,
                search = MlSyncPartStatus.DONE,
                counts = MlSyncCounts(faces = 10, people = 2, searchEmbeddings = 40),
            ),
        )
        assertTrue(notice.lines.isEmpty())
        assertEquals(3, notice.counts.size)
    }

    @Test
    fun `a partial save names the part that failed`() {
        val notice = message(
            outcome(
                SAVE,
                MlSyncResult.PARTIAL,
                faces = MlSyncPartStatus.DONE,
                search = MlSyncPartStatus.FAILED,
                counts = MlSyncCounts(faces = 5, people = 1),
            ),
        )
        assertEquals(R.string.settings_ml_drive_saved_partly, notice.title)
        assertEquals(2, notice.counts.size)
        assertEquals(
            listOf(R.string.settings_ml_drive_search_not_saved, R.string.settings_ml_drive_try_later),
            notice.lines,
        )
    }

    @Test
    fun `a partial restore names the part that failed and why`() {
        val notice = message(
            outcome(
                RESTORE,
                MlSyncResult.PARTIAL,
                faces = MlSyncPartStatus.TOO_NEW,
                search = MlSyncPartStatus.DONE,
                counts = MlSyncCounts(searchEmbeddings = 40),
            ),
        )
        assertEquals(R.string.settings_ml_drive_restored_partly, notice.title)
        assertEquals(
            listOf(R.string.settings_ml_drive_faces_not_restored, R.string.settings_ml_drive_too_new),
            notice.lines,
        )
    }

    @Test
    fun `a failed restore says why when the file status tells`() {
        assertEquals(
            listOf(R.string.settings_ml_drive_wrong_account),
            message(outcome(RESTORE, MlSyncResult.FAILED, faces = MlSyncPartStatus.WRONG_ACCOUNT)).lines,
        )
        assertEquals(
            listOf(R.string.settings_ml_drive_damaged),
            message(outcome(RESTORE, MlSyncResult.FAILED, search = MlSyncPartStatus.DAMAGED)).lines,
        )
        assertEquals(
            listOf(R.string.settings_ml_drive_too_new),
            message(
                outcome(
                    RESTORE,
                    MlSyncResult.FAILED,
                    faces = MlSyncPartStatus.DAMAGED,
                    search = MlSyncPartStatus.TOO_NEW,
                ),
            ).lines,
        )
        assertEquals(R.string.settings_ml_drive_restore_failed, message(outcome(RESTORE, MlSyncResult.FAILED)).title)
    }

    @Test
    fun `a restore that ran out of storage says so and lists what it imported before`() {
        val partly = message(
            outcome(
                RESTORE,
                MlSyncResult.NO_SPACE,
                faces = MlSyncPartStatus.DONE,
                search = MlSyncPartStatus.NO_SPACE,
                counts = MlSyncCounts(faces = 5, people = 1, filesDone = 1, filesTotal = 2),
            ),
        )
        assertEquals(R.string.settings_ml_drive_restored_partly, partly.title)
        assertEquals(2, partly.counts.size)
        assertEquals(
            listOf(R.string.settings_ml_drive_search_not_restored, R.string.settings_ml_restore_no_space),
            partly.lines,
        )

        val nothing = message(outcome(RESTORE, MlSyncResult.NO_SPACE, faces = MlSyncPartStatus.NO_SPACE))
        assertEquals(R.string.settings_ml_drive_restore_failed, nothing.title)
        assertTrue(nothing.counts.isEmpty())
        assertEquals(
            listOf(R.string.settings_ml_drive_faces_not_restored, R.string.settings_ml_restore_no_space),
            nothing.lines,
        )
    }

    @Test
    fun `any other failure asks to try again later`() {
        for (result in listOf(MlSyncResult.FAILED, MlSyncResult.INVALID_REQUEST, MlSyncResult.NOT_SIGNED_IN)) {
            val save = message(outcome(SAVE, result, faces = MlSyncPartStatus.FAILED, failure = "IOException"))
            assertEquals(R.string.settings_ml_drive_save_failed, save.title)
            assertEquals(listOf(R.string.settings_ml_drive_try_later), save.lines)
            assertTrue(save.counts.isEmpty())
        }
        assertEquals(
            listOf(R.string.settings_ml_drive_try_later),
            message(outcome(RESTORE, MlSyncResult.FAILED, search = MlSyncPartStatus.CONFLICT)).lines,
        )
    }

    @Test
    fun `nothing to save explains what fills the data first`() {
        val notice = message(outcome(SAVE, MlSyncResult.NOTHING_TO_SAVE))
        assertEquals(R.string.settings_ml_drive_nothing_title, notice.title)
        assertEquals(listOf(R.string.settings_ml_drive_nothing_message), notice.lines)
    }

    @Test
    fun `a device that cannot use Drive says so`() {
        val notice = message(outcome(SAVE, MlSyncResult.NOT_CAPABLE))
        assertEquals(R.string.settings_ml_drive_not_capable, notice.title)
        assertTrue(notice.lines.isEmpty())
    }
}
