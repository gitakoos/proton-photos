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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Drives [MirrorOverwriteJournal] end to end against a real temporary directory. The journal is the
 * crash-consistency net for the mirror-overwrite paths: it stages the original bytes before the
 * device file is truncated so a process killed mid-write can be recovered on the next launch. Each
 * test pins one rule of the stage / commit / recover protocol so a failure names the rule it broke.
 * No Android, no Context, no Robolectric: plain [java.io.File] assertions.
 */
class MirrorOverwriteJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var journal: MirrorOverwriteJournal

    @Before
    fun setUp() {
        journal = MirrorOverwriteJournal(tmp.root)
    }

    private fun fileCount(): Int = tmp.root.listFiles()?.size ?: 0

    @Test
    fun `an interrupted overwrite is pending and is restored on the next launch`() {
        val target = "content://media/external/images/media/42"
        val original = byteArrayOf(1, 2, 3, 4)
        val entry = journal.stage(target) { backup -> backup.writeBytes(original); true }
        assertNotNull(entry)

        // The device write was interrupted (never committed), so the pair is still pending.
        assertEquals(listOf(target), journal.pending().map { it.targetUri })

        var restoredUri: String? = null
        var restoredBytes: ByteArray? = null
        val count = journal.recover { targetUri, backup ->
            restoredUri = targetUri
            restoredBytes = backup.readBytes()
            true
        }

        assertEquals(1, count)
        assertEquals(target, restoredUri)
        assertArrayEquals(original, restoredBytes)
        assertTrue(journal.pending().isEmpty())
        assertEquals(0, fileCount())
    }

    @Test
    fun `a committed overwrite leaves nothing pending and no files behind`() {
        val entry = requireNotNull(journal.stage("content://media/1") { it.writeBytes(byteArrayOf(9)); true })
        journal.commit(entry)
        assertTrue(journal.pending().isEmpty())
        assertEquals(0, fileCount())
    }

    @Test
    fun `a lone backup is swept and never restored so a good write is not undone`() {
        // A backup with no marker means the overwrite already succeeded (commit removes the marker
        // first) or never started. Replaying it would overwrite good bytes, so it is swept, not restored.
        File(tmp.root, "abandoned.bak").writeBytes(byteArrayOf(7, 7, 7))
        assertTrue(journal.pending().isEmpty())

        var restorerCalled = false
        val count = journal.recover { _, _ -> restorerCalled = true; true }

        assertEquals(0, count)
        assertFalse(restorerCalled)
        assertFalse(File(tmp.root, "abandoned.bak").exists())
    }

    @Test
    fun `a marker with no backup is swept because nothing can be restored`() {
        File(tmp.root, "orphan.uri").writeText("content://media/2")
        assertTrue(journal.pending().isEmpty())

        var restorerCalled = false
        journal.recover { _, _ -> restorerCalled = true; true }

        assertFalse(restorerCalled)
        assertFalse(File(tmp.root, "orphan.uri").exists())
    }

    @Test
    fun `staging writes nothing when the backup write returns false`() {
        val entry = journal.stage("content://media/3") { false }
        assertNull(entry)
        assertEquals(0, fileCount())
    }

    @Test
    fun `staging writes nothing when the backup write throws`() {
        val entry = journal.stage("content://media/4") { backup ->
            backup.writeBytes(byteArrayOf(1, 2))
            throw IOException("disk full mid-copy")
        }
        assertNull(entry)
        assertEquals(0, fileCount())
    }

    @Test
    fun `recovery is idempotent and a second launch restores nothing`() {
        requireNotNull(journal.stage("content://media/5") { it.writeBytes(byteArrayOf(5)); true })

        assertEquals(1, journal.recover { _, _ -> true })
        assertEquals(0, journal.recover { _, _ -> true })
        assertEquals(0, fileCount())
    }

    @Test
    fun `a failed restore keeps the entry for a later retry`() {
        val target = "content://media/6"
        requireNotNull(journal.stage(target) { it.writeBytes(byteArrayOf(6)); true })

        assertEquals(0, journal.recover { _, _ -> false })
        // The original is not back on the device yet, so the entry survives for the next launch.
        assertEquals(listOf(target), journal.pending().map { it.targetUri })

        assertEquals(1, journal.recover { _, _ -> true })
        assertTrue(journal.pending().isEmpty())
        assertEquals(0, fileCount())
    }

    @Test
    fun `the restorer receives exactly the staged backup bytes`() {
        val original = ByteArray(2048) { (it * 31 % 256).toByte() }
        requireNotNull(journal.stage("content://media/7") { it.writeBytes(original); true })

        var seen: ByteArray? = null
        journal.recover { _, backup -> seen = backup.readBytes(); true }

        assertArrayEquals(original, seen)
    }
}
