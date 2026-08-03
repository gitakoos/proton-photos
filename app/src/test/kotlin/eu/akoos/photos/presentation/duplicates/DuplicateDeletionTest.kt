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
}
