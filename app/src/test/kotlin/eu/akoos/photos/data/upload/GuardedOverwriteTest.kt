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

package eu.akoos.photos.data.upload

import eu.akoos.photos.data.upload.MirrorOverwriteJournal.Outcome
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [MirrorOverwriteJournal.guardedOverwrite], the rule that decides when the journal entry
 * behind an in-place overwrite of the user's own photo may be dropped. Dropping it too early destroys
 * the only surviving copy of the original, so every branch is pinned here rather than argued in a
 * comment. Plain JVM: the Android I/O lives in the caller's lambdas.
 */
class GuardedOverwriteTest {

    private object FakeEntry

    @Test
    fun `a successful write drops the journal entry`() {
        var committed = 0
        val outcome = MirrorOverwriteJournal.guardedOverwrite(
            stage = { FakeEntry },
            openAndWrite = { true },
            restore = { throw AssertionError("restore must not run on success") },
            commit = { committed++ },
        )
        assertEquals(Outcome.WRITTEN, outcome)
        assertEquals(1, committed)
    }

    @Test
    fun `a refused write leaves the file untouched and drops the entry`() {
        var committed = 0
        var restored = false
        val outcome = MirrorOverwriteJournal.guardedOverwrite(
            stage = { FakeEntry },
            openAndWrite = { false },
            restore = { restored = true; true },
            commit = { committed++ },
        )
        assertEquals(Outcome.SKIPPED, outcome)
        assertEquals("nothing was truncated, so the entry is not needed", 1, committed)
        assertFalse("a refused write must not trigger a restore", restored)
    }

    @Test
    fun `a failed stage never touches the file and never commits`() {
        var wrote = false
        var committed = 0
        val outcome = MirrorOverwriteJournal.guardedOverwrite<FakeEntry>(
            stage = { null },
            openAndWrite = { wrote = true; true },
            restore = { true },
            commit = { committed++ },
        )
        assertEquals(Outcome.SKIPPED, outcome)
        assertFalse("the file must not be opened when staging failed", wrote)
        assertEquals(0, committed)
    }

    @Test
    fun `a write that throws is rolled back and then drops the entry`() {
        var committed = 0
        var restored = 0
        val outcome = MirrorOverwriteJournal.guardedOverwrite(
            stage = { FakeEntry },
            openAndWrite = { throw java.io.IOException("disk ejected") },
            restore = { restored++; true },
            commit = { committed++ },
        )
        assertEquals(Outcome.ROLLED_BACK, outcome)
        assertEquals(1, restored)
        assertEquals("the original is back, so the entry is no longer needed", 1, committed)
    }

    /** The rule that protects the original: a truncated file whose restore failed keeps its backup. */
    @Test
    fun `a write that throws and whose restore fails keeps the entry for the next launch`() {
        var committed = 0
        val outcome = MirrorOverwriteJournal.guardedOverwrite(
            stage = { FakeEntry },
            openAndWrite = { throw java.io.IOException("disk ejected") },
            restore = { false },
            commit = { committed++ },
        )
        assertEquals(Outcome.STRANDED, outcome)
        assertEquals("the staged backup is the only copy left, so it must survive", 0, committed)
    }

    @Test
    fun `a restore that throws is treated as a failed restore and keeps the entry`() {
        var committed = 0
        val outcome = MirrorOverwriteJournal.guardedOverwrite(
            stage = { FakeEntry },
            openAndWrite = { throw java.io.IOException("disk ejected") },
            restore = { throw java.io.IOException("restore failed too") },
            commit = { committed++ },
        )
        assertEquals(Outcome.STRANDED, outcome)
        assertEquals(0, committed)
    }

    @Test
    fun `a cancelled write is restored before the cancellation propagates`() {
        var restored = 0
        var committed = 0
        var threw = false
        try {
            MirrorOverwriteJournal.guardedOverwrite(
                stage = { FakeEntry },
                openAndWrite = { throw CancellationException("cancelled") },
                restore = { restored++; true },
                commit = { committed++ },
            )
        } catch (e: CancellationException) {
            threw = true
        }
        assertTrue("cancellation must propagate", threw)
        assertEquals("the original must be back before unwinding", 1, restored)
        assertEquals(1, committed)
    }

    @Test
    fun `a cancelled write whose restore fails still propagates and keeps the entry`() {
        var committed = 0
        var threw = false
        try {
            MirrorOverwriteJournal.guardedOverwrite(
                stage = { FakeEntry },
                openAndWrite = { throw CancellationException("cancelled") },
                restore = { false },
                commit = { committed++ },
            )
        } catch (e: CancellationException) {
            threw = true
        }
        assertTrue("cancellation must propagate", threw)
        assertEquals("the staged backup must survive a cancelled, unrestorable write", 0, committed)
    }
}
