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
import eu.akoos.photos.data.label.LabelBackupOperation
import eu.akoos.photos.data.label.LabelBackupOutcome
import eu.akoos.photos.data.label.LabelBackupProgress
import eu.akoos.photos.data.label.LabelBackupResult
import eu.akoos.photos.data.label.LabelBackupState
import eu.akoos.photos.data.label.LabelCount
import eu.akoos.photos.data.label.LabelBackupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelBackupRulesTest {

    private val idle = LabelBackupState.Idle
    private val saving = LabelBackupState.Running(LabelBackupOperation.SAVE)

    private fun finished(op: LabelBackupOperation, result: LabelBackupResult, photos: Int = 0, newLabels: Int = 0) =
        LabelBackupState.Finished("run-1", LabelBackupOutcome(op, result, photos, newLabels))

    @Test
    fun `the section is hidden without an account or a Drive client`() {
        assertEquals(LabelBackupMode.HIDDEN, labelBackupMode(signedIn = false, driveAvailable = true, state = idle))
        assertEquals(LabelBackupMode.HIDDEN, labelBackupMode(signedIn = true, driveAvailable = false, state = idle))
        assertEquals(LabelBackupMode.HIDDEN, labelBackupMode(signedIn = false, driveAvailable = false, state = saving))
    }

    @Test
    fun `an account with Drive sees the actions, held back while a run goes`() {
        assertEquals(LabelBackupMode.ACTIONS, labelBackupMode(true, true, idle))
        assertEquals(LabelBackupMode.ACTIONS, labelBackupMode(true, true, finished(LabelBackupOperation.SAVE, LabelBackupResult.SAVED)))
        assertEquals(LabelBackupMode.PROGRESS, labelBackupMode(true, true, saving))
    }

    @Test
    fun `a save still looking up its folder shows as a save`() {
        assertEquals(saving, labelBackupShownState(idle, preparingSave = true))
        assertEquals(idle, labelBackupShownState(idle, preparingSave = false))
        val restoring = LabelBackupState.Running(LabelBackupOperation.RESTORE)
        assertEquals(restoring, labelBackupShownState(restoring, preparingSave = true))
    }

    @Test
    fun `the folder question comes before any result`() {
        val done = finished(LabelBackupOperation.SAVE, LabelBackupResult.SAVED, 3)

        assertEquals(LabelBackupNotice.AskToCreateFolder, labelBackupSheet(folderPrompt = true, state = done))
        assertNull(labelBackupSheet(folderPrompt = false, state = idle))
        assertNull(labelBackupSheet(folderPrompt = false, state = saving))
    }

    @Test
    fun `a finished save says how many photos it kept`() {
        val notice = labelBackupNotice(LabelBackupOutcome(LabelBackupOperation.SAVE, LabelBackupResult.SAVED, 4))

        assertEquals(R.string.settings_ml_drive_saved, notice.title)
        assertEquals(R.plurals.label_backup_saved_count, notice.plural)
        assertEquals(4, notice.count)
    }

    @Test
    fun `a job that found no folder asks for it instead of reporting a failure`() {
        val missing = finished(LabelBackupOperation.SAVE, LabelBackupResult.FOLDER_MISSING)

        assertEquals(LabelBackupNotice.AskToCreateFolder, labelBackupSheet(folderPrompt = false, state = missing))
    }

    @Test
    fun `a restore that only brought labels without photos says it restored`() {
        val notice = labelBackupNotice(
            LabelBackupOutcome(LabelBackupOperation.RESTORE, LabelBackupResult.RESTORED, photos = 0, newLabels = 3),
        )

        assertEquals(R.string.settings_ml_drive_restored, notice.title)
        assertNull(notice.line)
        assertNull(notice.plural)
    }

    @Test
    fun `the running row says what the job is doing`() {
        assertEquals(
            R.string.label_backup_step_encrypting,
            labelBackupStepText(LabelBackupProgress(LabelBackupStep.ENCRYPTING)).res,
        )
        assertEquals(
            R.string.label_backup_step_uploading,
            labelBackupStepText(LabelBackupProgress(LabelBackupStep.UPLOADING)).res,
        )
        assertEquals(
            R.string.label_backup_step_downloading,
            labelBackupStepText(LabelBackupProgress(LabelBackupStep.DOWNLOADING)).res,
        )
        assertEquals(
            R.string.label_backup_step_merging,
            labelBackupStepText(LabelBackupProgress(LabelBackupStep.MERGING)).res,
        )
    }

    @Test
    fun `a picked copy lists its first five labels and counts the rest behind Show all`() {
        val labels = (1..100).map { LabelCount("label $it", it) }

        val preview = labelCopyPreview(labels)

        assertEquals(labels.take(5), preview.shown)
        assertEquals(100, preview.total)
        assertTrue(preview.hasMore)
    }

    @Test
    fun `a copy of five labels or fewer shows them all with no Show all`() {
        val five = (1..5).map { LabelCount("label $it", 1) }

        assertFalse(labelCopyPreview(five).hasMore)
        assertEquals(five, labelCopyPreview(five).shown)
        assertFalse(labelCopyPreview(emptyList()).hasMore)
        assertTrue(labelCopyPreview(five + LabelCount("six", 1)).hasMore)
    }

    @Test
    fun `every step has a line`() {
        for (step in LabelBackupStep.entries) labelBackupStepText(LabelBackupProgress(step))
    }

    @Test
    fun `a save of nothing but removals still says it saved`() {
        val notice = labelBackupNotice(LabelBackupOutcome(LabelBackupOperation.SAVE, LabelBackupResult.SAVED, 0))

        assertEquals(R.string.settings_ml_drive_saved, notice.title)
        assertNull(notice.plural)
    }

    @Test
    fun `a restore says how many photos changed, or that nothing needed to`() {
        val changed = labelBackupNotice(LabelBackupOutcome(LabelBackupOperation.RESTORE, LabelBackupResult.RESTORED, 7))
        val same = labelBackupNotice(LabelBackupOutcome(LabelBackupOperation.RESTORE, LabelBackupResult.RESTORED, 0))

        assertEquals(R.plurals.label_backup_restored_count, changed.plural)
        assertEquals(7, changed.count)
        assertEquals(R.string.label_backup_up_to_date, same.line)
    }

    @Test
    fun `nothing to save is told apart from a failure`() {
        val none = labelBackupNotice(LabelBackupOutcome(LabelBackupOperation.SAVE, LabelBackupResult.NOTHING_TO_SAVE))

        assertEquals(R.string.settings_ml_drive_nothing_title, none.title)
        assertEquals(R.string.label_backup_nothing_message, none.line)
    }

    @Test
    fun `failures name the operation and say what is wrong`() {
        fun notice(op: LabelBackupOperation, result: LabelBackupResult) =
            labelBackupNotice(LabelBackupOutcome(op, result))

        assertEquals(R.string.settings_ml_drive_save_failed, notice(LabelBackupOperation.SAVE, LabelBackupResult.FAILED).title)
        assertEquals(R.string.settings_ml_drive_restore_failed, notice(LabelBackupOperation.RESTORE, LabelBackupResult.FAILED).title)
        assertEquals(R.string.settings_ml_drive_try_later, notice(LabelBackupOperation.SAVE, LabelBackupResult.CONFLICT).line)
        assertEquals(R.string.settings_ml_drive_wrong_account, notice(LabelBackupOperation.RESTORE, LabelBackupResult.WRONG_ACCOUNT).line)
        assertEquals(R.string.settings_ml_drive_too_new, notice(LabelBackupOperation.RESTORE, LabelBackupResult.TOO_NEW).line)
        assertEquals(R.string.settings_ml_drive_damaged, notice(LabelBackupOperation.RESTORE, LabelBackupResult.DAMAGED).line)
        assertEquals(R.string.settings_ml_drive_not_capable, notice(LabelBackupOperation.SAVE, LabelBackupResult.NOT_CAPABLE).title)
    }

    @Test
    fun `every result has a message`() {
        for (op in LabelBackupOperation.entries) {
            for (result in LabelBackupResult.entries) {
                labelBackupNotice(LabelBackupOutcome(op, result))
            }
        }
    }

    @Test
    fun `each operation has its own headline`() {
        assertEquals(R.string.label_backup_working_save, labelBackupHeadline(LabelBackupOperation.SAVE))
        assertEquals(R.string.label_backup_working_restore, labelBackupHeadline(LabelBackupOperation.RESTORE))
    }
}
