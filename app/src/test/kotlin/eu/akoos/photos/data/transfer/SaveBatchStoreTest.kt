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

package eu.akoos.photos.data.transfer

import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase.ItemResult
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveBatchStoreTest {

    private val root: File = Files.createTempDirectory("save-batch-store").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a batch reads back with what each photo did`() {
        val store = SaveBatchStore(root, "b1")
        store.create(SaveBatchStore.Batch("user", listOf("a", "b", "c"), onDeviceCount = 2))
        store.markDone("a", ItemResult.SAVED)
        store.markDone("b", ItemResult.CANCELLED)
        store.markSaved("content://media/1")

        val again = SaveBatchStore(root, "b1")
        assertEquals(SaveBatchStore.Batch("user", listOf("a", "b", "c"), 2), again.read())
        assertEquals(mapOf("a" to ItemResult.SAVED, "b" to ItemResult.CANCELLED), again.done())
        assertEquals(listOf("content://media/1"), again.savedUris())
    }

    @Test
    fun `only rows opened and never settled read as open`() {
        val store = SaveBatchStore(root, "b2")
        store.create(SaveBatchStore.Batch("user", listOf("a"), 0))
        store.rowOpened("content://media/1")
        store.rowSettled("content://media/1")
        store.rowOpened("content://media/2")

        assertEquals(listOf("content://media/2"), store.openRows())
        store.clearRows()
        assertEquals(emptyList<String>(), store.openRows())
    }

    @Test
    fun `a dropped batch is not brought back by a save that finishes after`() {
        val store = SaveBatchStore(root, "b3")
        store.create(SaveBatchStore.Batch("user", listOf("a"), 0))
        store.delete()

        store.markDone("a", ItemResult.SAVED)
        store.markSaved("content://media/1")
        store.rowOpened("content://media/1")

        assertFalse(store.exists())
        assertFalse(File(File(root, SaveBatchStore.DIR), "b3").exists())
        assertNull(store.read())
    }

    @Test
    fun `a torn last line is skipped`() {
        val store = SaveBatchStore(root, "b4")
        store.create(SaveBatchStore.Batch("user", listOf("a", "b"), 0))
        store.markDone("a", ItemResult.SKIPPED)
        File(File(File(root, SaveBatchStore.DIR), "b4"), "done.txt").appendText("b SAV")

        assertEquals(mapOf("a" to ItemResult.SKIPPED), store.done())
        assertTrue(store.exists())
    }
}
