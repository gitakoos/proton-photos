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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The version attribution of the crash record: a bundle copied after an update must carry only the
 * running build's crashes, so an old version's block is dropped on the startup prune and never reaches
 * the copied text. Pure file operations, so no Android, DI, or crypto is exercised.
 */
class CrashLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun block(code: Int, exception: String): String =
        CrashLogStore.blockHeader(code) + "$exception\n    at eu.akoos.photos.Foo.bar(Foo.kt:1)\n\n"

    private fun write(text: String): File {
        val f = CrashLogStore.file(tmp.root)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun `prune keeps only the current version blocks`() {
        write(block(264, "OldException") + block(265, "NewException"))
        CrashLogStore.pruneToVersion(tmp.root, 265)
        val kept = CrashLogStore.file(tmp.root).readText()
        assertTrue(kept.contains("NewException"))
        assertFalse(kept.contains("OldException"))
        assertFalse(kept.contains("v264"))
    }

    @Test
    fun `currentVersionText returns only the current version blocks`() {
        write(block(264, "OldException") + block(265, "NewException"))
        val text = CrashLogStore.currentVersionText(tmp.root, 265)
        assertTrue(text.contains("NewException"))
        assertFalse(text.contains("OldException"))
    }

    @Test
    fun `prune deletes the file when no block matches the current version`() {
        write(block(264, "OldException"))
        CrashLogStore.pruneToVersion(tmp.root, 265)
        assertFalse(CrashLogStore.file(tmp.root).exists())
    }

    @Test
    fun `currentVersionText is empty when the file is absent`() {
        assertEquals("", CrashLogStore.currentVersionText(tmp.root, 265))
    }

    @Test
    fun `prune leaves a file untouched when every block is the current version`() {
        val content = block(265, "A") + block(265, "B")
        write(content)
        CrashLogStore.pruneToVersion(tmp.root, 265)
        assertEquals(content, CrashLogStore.file(tmp.root).readText())
    }
}
