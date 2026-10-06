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

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rule that decides when the backup behind an in-place edit of the user's own photo may be dropped
 * ([MirrorOverwriteJournal.guardedMutation]), driven over real files and the real journal. Dropping the
 * entry before the original is certain to be in place destroys the only surviving copy, so each branch
 * is pinned here. Plain JVM: the Android I/O lives in the caller's lambdas.
 */
class GuardedMutationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val original = "the original photo bytes".toByteArray()

    private class Run(val file: File, val journal: MirrorOverwriteJournal) {
        var restores = 0
    }

    private fun run(): Run {
        val file = tmp.newFile("photo.jpg").also { it.writeBytes(original) }
        return Run(file, MirrorOverwriteJournal(tmp.newFolder("journal")))
    }

    private fun <T> Run.guarded(
        restoreWorks: Boolean = true,
        verify: () -> Boolean = { true },
        mutate: () -> T,
    ): T = MirrorOverwriteJournal.guardedMutation(
        stage = { journal.stage(file.path) { backup -> file.copyTo(backup, overwrite = true); true } },
        mutate = mutate,
        untouched = { entry -> file.readBytes().contentEquals(entry.backup.readBytes()) },
        restore = { entry ->
            restores++
            if (restoreWorks) entry.backup.copyTo(file, overwrite = true)
            restoreWorks
        },
        commit = { entry -> journal.commit(entry) },
        verify = verify,
    )

    private fun Run.pending() = journal.pending().size

    @Test
    fun `a successful edit keeps the new bytes and drops the backup`() {
        val r = run()
        val result = r.guarded { r.file.writeBytes("edited".toByteArray()); 42 }
        assertEquals(42, result)
        assertEquals("edited", r.file.readText())
        assertEquals(0, r.pending())
        assertEquals(0, r.restores)
    }

    @Test
    fun `an edit that truncates the file and throws is rolled back and the failure reaches the caller`() {
        val r = run()
        val boom = IOException("disk ejected")
        val thrown = runCatching {
            r.guarded<Unit> { r.file.writeBytes(ByteArray(0)); throw boom }
        }.exceptionOrNull()
        assertSame(boom, thrown)
        assertTrue(r.file.readBytes().contentEquals(original))
        assertEquals(1, r.restores)
        assertEquals("the original is back, so the backup is not needed", 0, r.pending())
    }

    @Test
    fun `a write the system refused before opening anything needs no restore`() {
        val r = run()
        val denied = SecurityException("needs consent")
        val thrown = runCatching { r.guarded<Unit> { throw denied } }.exceptionOrNull()
        assertSame(denied, thrown)
        assertTrue(r.file.readBytes().contentEquals(original))
        assertEquals("the file was never touched", 0, r.restores)
        assertEquals(0, r.pending())
    }

    @Test
    fun `a truncated file whose restore also fails keeps the backup for the next launch`() {
        val r = run()
        runCatching {
            r.guarded<Unit>(restoreWorks = false) { r.file.writeBytes(ByteArray(0)); throw IOException("eject") }
        }
        assertEquals("the backup is the only copy of the original left", 1, r.pending())
        val replayed = r.journal.recover { target, backup ->
            File(target).writeBytes(backup.readBytes()); true
        }
        assertEquals(1, replayed)
        assertTrue(r.file.readBytes().contentEquals(original))
    }

    @Test
    fun `an edit that finishes but leaves an unreadable file is rolled back`() {
        val r = run()
        val thrown = runCatching {
            r.guarded(verify = { false }) { r.file.writeBytes("garbage".toByteArray()) }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertTrue(r.file.readBytes().contentEquals(original))
        assertEquals(0, r.pending())
    }

    @Test
    fun `an edit that cannot be backed up is never started`() {
        val r = run()
        var ran = false
        val thrown = runCatching {
            MirrorOverwriteJournal.guardedMutation(
                stage = { r.journal.stage(r.file.path) { false } },
                mutate = { ran = true },
                untouched = { true },
                restore = { true },
                commit = { r.journal.commit(it) },
            )
        }.exceptionOrNull()
        assertTrue(thrown is MirrorOverwriteJournal.StagingFailedException)
        assertFalse(ran)
        assertTrue(r.file.readBytes().contentEquals(original))
        assertEquals(0, r.pending())
    }
}
