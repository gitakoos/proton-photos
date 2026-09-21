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

package eu.akoos.photos.presentation.duplicates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule standing between the user and a photo they cannot get back. A duplicate group only ever
 * gives up copies while one of them is still there afterwards.
 */
class DuplicateDeletionTest {

    private val pair = listOf("A", "B")

    @Test
    fun `keeping one copy deletes the other`() {
        val out = DuplicateDeletion.deletableExtras(pair, keepIds = setOf("A"), alreadyDeleted = emptySet())
        assertEquals(listOf("B"), out)
    }

    @Test
    fun `naming no keeper deletes nothing`() {
        val out = DuplicateDeletion.deletableExtras(pair, keepIds = emptySet(), alreadyDeleted = emptySet())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `the second card cannot take the last remaining copy`() {
        // A byte-identical pair is shown twice at once: once as an exact group, once as a similar
        // cluster. The user keeps A and deletes B on the first card, then keeps B and deletes A on
        // the second. B is gone by then, so it cannot be what survives, and A must stay.
        val out = DuplicateDeletion.deletableExtras(
            pair, keepIds = setOf("B"), alreadyDeleted = setOf("B"),
        )
        assertTrue("A must survive when the only keeper is already deleted", out.isEmpty())
    }

    @Test
    fun `a keeper that is still there still allows the rest to go`() {
        val out = DuplicateDeletion.deletableExtras(
            listOf("A", "B", "C"), keepIds = setOf("A", "B"), alreadyDeleted = setOf("B"),
        )
        assertEquals(listOf("C"), out)
    }

    @Test
    fun `an already deleted copy is never handed over twice`() {
        val out = DuplicateDeletion.deletableExtras(
            listOf("A", "B", "C"), keepIds = setOf("A"), alreadyDeleted = setOf("B"),
        )
        assertEquals(listOf("C"), out)
    }

    @Test
    fun `batch threads deletions so opposite keepers cannot wipe a pair`() {
        // The same pair shown on two cards with opposite keepers. Measuring both against one frozen
        // snapshot deletes B for the first and A for the second, taking the last copy. Threading each
        // card's deletion into the next makes the second card see its only keeper already gone and
        // delete nothing, so one copy always survives. This is the batch-path regression.
        val out = DuplicateDeletion.batchDeletableExtras(
            groups = listOf(pair to setOf("A"), pair to setOf("B")),
            alreadyDeleted = emptySet(),
        )
        assertEquals(listOf(listOf("B"), emptyList<String>()), out)
        assertEquals("the batch must never delete the whole pair", setOf("B"), out.flatten().toSet())
    }

    @Test
    fun `batch keeps at least one copy of a pair whichever keeper order`() {
        val ab = DuplicateDeletion.batchDeletableExtras(listOf(pair to setOf("A"), pair to setOf("B")), emptySet())
        val ba = DuplicateDeletion.batchDeletableExtras(listOf(pair to setOf("B"), pair to setOf("A")), emptySet())
        assertTrue("a copy of the pair must survive", pair.any { it !in ab.flatten() })
        assertTrue("a copy of the pair must survive", pair.any { it !in ba.flatten() })
    }

    @Test
    fun `batch deletes each independent group's extras`() {
        val out = DuplicateDeletion.batchDeletableExtras(
            groups = listOf(listOf("A", "B") to setOf("A"), listOf("C", "D") to setOf("C")),
            alreadyDeleted = emptySet(),
        )
        assertEquals(listOf(listOf("B"), listOf("D")), out)
    }

    @Test
    fun `batch honours the incoming already deleted set`() {
        val out = DuplicateDeletion.batchDeletableExtras(
            groups = listOf(listOf("A", "B", "C") to setOf("A")),
            alreadyDeleted = setOf("B"),
        )
        assertEquals(listOf(listOf("C")), out)
    }
}
