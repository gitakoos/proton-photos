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
import eu.akoos.photos.util.LabelNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsSettingsLogicTest {

    // ── manager order ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `most used first, then alphabetical by the folded name`() {
        val sorted = sortLabelsForManager(
            listOf(usage("zebra", 2), usage("Édes", 2), usage("apple", 2), usage("Beach", 7), usage("cat", 1)),
        )
        assertEquals(listOf("Beach", "apple", "Édes", "zebra", "cat"), sorted.map { it.label })
    }

    // ── rename plan ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a blank field or the current spelling saves nothing`() {
        val beach = usage("Beach", 3)
        assertEquals(LabelRenamePlan.None, labelRenamePlan(beach, "   ", listOf(beach)))
        assertEquals(LabelRenamePlan.None, labelRenamePlan(beach, " Beach ", listOf(beach)))
    }

    @Test
    fun `a free name renames`() {
        val beach = usage("Beach", 3)
        assertEquals(LabelRenamePlan.Rename("Seaside"), labelRenamePlan(beach, "Seaside", listOf(beach)))
    }

    @Test
    fun `a new spelling of the same label renames rather than merges`() {
        val beach = usage("Beach", 3)
        assertEquals(LabelRenamePlan.Rename("BEACH"), labelRenamePlan(beach, "BEACH", listOf(beach)))
    }

    @Test
    fun `the name of another label merges, matched case and accent insensitively`() {
        val cafe = usage("Café", 4)
        val coffee = usage("coffee", 2)
        val plan = labelRenamePlan(coffee, "cafe", listOf(cafe, coffee))
        assertEquals(LabelRenamePlan.Merge("cafe", cafe), plan)
    }

    @Test
    fun `the rename field drops commas and stops at the label length`() {
        assertEquals("ab", renameFieldText("a,b"))
        assertEquals(LabelNormalizer.MAX_LENGTH, renameFieldText("x".repeat(40)).length)
    }

    @Test
    fun `typed text offers to create a label only when the account has none of that name`() {
        val all = listOf(usage("Café au lait", 3), usage("Beach", 2))

        assertEquals("summer trip", labelToCreate("  summer   trip ", all))
        assertEquals(null, labelToCreate("cafe AU lait", all))
        assertEquals(null, labelToCreate("BEACH", all))
        assertEquals(null, labelToCreate("   ", all))
        assertEquals("Beac", labelToCreate("Beac", all))
    }

    @Test
    fun `the search keeps the labels that hold the typed text, ignoring case and accents`() {
        val all = listOf(usage("Café au lait", 3), usage("Beach", 2), usage("Summer trip", 1))

        assertEquals(listOf("Café au lait"), filterLabelsForManager(all, "CAFE").map { it.label })
        assertEquals(listOf("Beach"), filterLabelsForManager(all, " each ").map { it.label })
        assertEquals(listOf("Summer trip"), filterLabelsForManager(all, "er tr").map { it.label })
    }

    @Test
    fun `a blank search keeps every label and a miss keeps none`() {
        val all = listOf(usage("Beach", 2), usage("Sun", 1))

        assertEquals(all, filterLabelsForManager(all, ""))
        assertEquals(all, filterLabelsForManager(all, "   "))
        assertEquals(emptyList<LabelUsage>(), filterLabelsForManager(all, "zzz"))
    }

    private fun usage(label: String, count: Int) = LabelUsage(LabelNormalizer.fold(label), label, count)
}
