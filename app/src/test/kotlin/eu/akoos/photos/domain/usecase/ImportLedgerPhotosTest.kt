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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The history detail counts a Live Photo once, as the history row and the Done card do. */
class ImportLedgerPhotosTest {

    private fun row(name: String, link: String, dateMs: Long? = 1_000L, already: Boolean = false) =
        ImportUploadedEntity(runId = "r", linkId = link, sha1 = link, name = name, dateMs = dateMs, alreadyInDrive = already)

    @Test
    fun `a Live Photo and a photo count as two`() {
        val rows = listOf(row("IMG_1.HEIC", "s"), row("IMG_1.MOV", "v"), row("IMG_2.jpg", "p"))
        assertEquals(listOf("s", "p"), ledgerPhotos(rows).map { it.linkId })
    }

    @Test
    fun `a Live Photo whose halves carry different dates still counts once`() {
        val rows = listOf(row("IMG_1.jpg", "s", dateMs = 1_000L), row("IMG_1.mp4", "v", dateMs = 780_000L))
        assertEquals(listOf("s"), ledgerPhotos(rows).map { it.linkId })
    }

    @Test
    fun `a video joins a still of the same outcome only`() {
        val rows = listOf(row("IMG_1.HEIC", "s", already = true), row("IMG_1.MOV", "v"))
        assertEquals(2, ledgerPhotos(rows).size)
    }

    @Test
    fun `one still takes one video, and a lone video stays`() {
        val rows = listOf(row("IMG_1.jpg", "s"), row("img_1.mov", "v1"), row("IMG_1.mp4", "v2"), row("clip.mov", "c"))
        assertEquals(listOf("s", "v2", "c"), ledgerPhotos(rows).map { it.linkId })
    }

    @Test
    fun `undoing a Live Photo reports one photo removed`() {
        val rows = listOf(row("IMG_1.HEIC", "s"), row("IMG_1.MOV", "v"), row("IMG_2.jpg", "p"))
        val plan = ImportUndoPlan(toTrash = listOf("s", "v", "p"), changed = emptyList(), unverifiable = emptyList())

        val result = undoResultOf(rows, plan, trashed = setOf("s", "v", "p"), failedTrash = emptySet(), trashCallFailed = false)

        assertEquals(UndoResult(undone = 2, keptChanged = 0, failed = 0), result)
    }

    @Test
    fun `a failed trash call counts the Live Photo once`() {
        val rows = listOf(row("IMG_1.HEIC", "s"), row("IMG_1.MOV", "v"))
        val plan = ImportUndoPlan(toTrash = listOf("s", "v"), changed = emptyList(), unverifiable = emptyList())

        val result = undoResultOf(rows, plan, trashed = emptySet(), failedTrash = emptySet(), trashCallFailed = true)

        assertEquals(UndoResult(undone = 0, keptChanged = 0, failed = 1), result)
    }
}
