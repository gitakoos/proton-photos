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

/**
 * The safety partition at the core of an import undo. Only a row whose bytes on Drive still match what
 * the run uploaded may be trashed; every other case keeps the photo. This pins that an edit, a missing
 * link, or an underivable expected hash can never clear the wrong file for the trash. Pure data in, plan
 * out, so no DI, network, or crypto is exercised.
 */
class UndoImportPartitionTest {

    private fun row(linkId: String) =
        ImportUploadedEntity(runId = "run", linkId = linkId, sha1 = "sha-$linkId")

    @Test
    fun `an unchanged photo (expected equals current) is cleared for trashing`() {
        val plan = planImportUndo(
            rows = listOf(row("a")),
            expectedByLink = mapOf("a" to "hash-a"),
            currentByLink = mapOf("a" to "hash-a"),
        )
        assertEquals(listOf("a"), plan.toTrash)
        assertEquals(emptyList<String>(), plan.changed)
        assertEquals(emptyList<String>(), plan.unverifiable)
    }

    @Test
    fun `a photo whose current hash differs is kept as changed`() {
        val plan = planImportUndo(
            rows = listOf(row("a")),
            expectedByLink = mapOf("a" to "hash-a"),
            currentByLink = mapOf("a" to "hash-a-replaced"),
        )
        assertEquals(emptyList<String>(), plan.toTrash)
        assertEquals(listOf("a"), plan.changed)
        assertEquals(emptyList<String>(), plan.unverifiable)
    }

    @Test
    fun `a link with no current detail is treated as gone and left out of every bucket`() {
        val plan = planImportUndo(
            rows = listOf(row("a")),
            expectedByLink = mapOf("a" to "hash-a"),
            currentByLink = emptyMap(),
        )
        assertEquals(emptyList<String>(), plan.toTrash)
        assertEquals(emptyList<String>(), plan.changed)
        assertEquals(emptyList<String>(), plan.unverifiable)
    }

    @Test
    fun `a null expected hash is never equal to a current hash so the photo is kept`() {
        val plan = planImportUndo(
            rows = listOf(row("a")),
            expectedByLink = mapOf("a" to null),
            currentByLink = mapOf("a" to "hash-a"),
        )
        assertEquals(emptyList<String>(), plan.toTrash)
        assertEquals(emptyList<String>(), plan.changed)
        assertEquals(listOf("a"), plan.unverifiable)
    }

    @Test
    fun `a resolved link carrying a null current hash is kept as unverifiable`() {
        val plan = planImportUndo(
            rows = listOf(row("a")),
            expectedByLink = mapOf("a" to "hash-a"),
            currentByLink = mapOf("a" to null),
        )
        assertEquals(emptyList<String>(), plan.toTrash)
        assertEquals(emptyList<String>(), plan.changed)
        assertEquals(listOf("a"), plan.unverifiable)
    }

    @Test
    fun `a mixed run trashes only the exact matches and keeps the rest`() {
        val rows = listOf(
            row("match"),
            row("edited"),
            row("gone"),
            row("nullExpected"),
            row("nullCurrent"),
        )
        val plan = planImportUndo(
            rows = rows,
            expectedByLink = mapOf(
                "match" to "h1",
                "edited" to "h2",
                "gone" to "h3",
                "nullExpected" to null,
                "nullCurrent" to "h5",
            ),
            currentByLink = mapOf(
                "match" to "h1",
                "edited" to "h2-replaced",
                // "gone" intentionally absent: no detail returned.
                "nullExpected" to "h4",
                "nullCurrent" to null,
            ),
        )
        assertEquals(listOf("match"), plan.toTrash)
        assertEquals(listOf("edited"), plan.changed)
        assertEquals(listOf("nullExpected", "nullCurrent"), plan.unverifiable)
    }
}
