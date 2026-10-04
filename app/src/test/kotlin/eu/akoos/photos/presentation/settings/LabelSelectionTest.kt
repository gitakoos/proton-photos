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

import eu.akoos.photos.data.db.dao.LabelUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelSelectionTest {

    private fun usage(key: String, count: Int = 1) = LabelUsage(key, key.replaceFirstChar { it.uppercase() }, count)

    @Test
    fun `a label is picked at the end and taken out when picked again`() {
        assertEquals(listOf("a"), toggledLabelSelection(emptyList(), "a"))
        assertEquals(listOf("a", "b"), toggledLabelSelection(listOf("a"), "b"))
        assertEquals(listOf("b"), toggledLabelSelection(listOf("a", "b"), "a"))
        assertEquals(emptyList<String>(), toggledLabelSelection(listOf("a"), "a"))
    }

    @Test
    fun `a picked label that is gone drops out of the selection`() {
        val available = listOf(usage("a"), usage("c"))

        assertEquals(listOf("a"), prunedLabelSelection(listOf("a", "b"), available))
        assertEquals(emptyList<String>(), prunedLabelSelection(listOf("b"), available))
        assertEquals(listOf("c", "a"), prunedLabelSelection(listOf("c", "a"), available))
    }

    @Test
    fun `the picked labels come back in the order they were picked`() {
        val available = listOf(usage("a"), usage("b"), usage("c"))

        assertEquals(listOf("C", "A"), selectedLabels(listOf("c", "a"), available).map { it.label })
        assertEquals(listOf("A"), selectedLabels(listOf("zzz", "a"), available).map { it.label })
        assertEquals(emptyList<LabelUsage>(), selectedLabels(emptyList(), available))
    }

    @Test
    fun `the picker leaves out only the photos that already carry every picked label`() {
        val keys = listOf(setOf("p1", "p2", "p3"), setOf("p2", "p3", "p4"), setOf("p3", "p2"))

        assertEquals(setOf("p2", "p3"), photosCarryingAll(keys))
    }

    @Test
    fun `one label leaves out its own photos, and a label nobody carries leaves out nothing`() {
        assertEquals(setOf("p1", "p2"), photosCarryingAll(listOf(setOf("p1", "p2"))))
        assertEquals(emptySet<String>(), photosCarryingAll(listOf(setOf("p1"), emptySet())))
        assertEquals(emptySet<String>(), photosCarryingAll(emptyList()))
    }
}
