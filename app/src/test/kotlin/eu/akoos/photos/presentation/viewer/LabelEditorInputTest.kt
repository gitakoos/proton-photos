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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.util.LabelNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelEditorInputTest {

    // ── field edits ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `typing without a comma commits nothing`() {
        assertEquals(LabelFieldEdit(null, "beach"), LabelEditorInput.onEdit("beach"))
    }

    @Test
    fun `a trailing comma commits what came before and empties the field`() {
        assertEquals(LabelFieldEdit("beach", ""), LabelEditorInput.onEdit("beach,"))
    }

    @Test
    fun `a pasted list commits all but the last piece, which stays`() {
        assertEquals(LabelFieldEdit("a, b", "c"), LabelEditorInput.onEdit("a, b, c"))
    }

    @Test
    fun `a lone comma commits nothing`() {
        assertEquals(LabelFieldEdit(null, ""), LabelEditorInput.onEdit(" ,"))
    }

    @Test
    fun `the kept text is clipped to the label length`() {
        val edit = LabelEditorInput.onEdit("x".repeat(40))
        assertNull(edit.commit)
        assertEquals(LabelNormalizer.MAX_LENGTH, edit.remainder.length)
    }

    @Test
    fun `clipping never splits a surrogate pair`() {
        val emoji = "🌴"
        val edit = LabelEditorInput.onEdit(emoji.repeat(30))
        assertEquals(LabelNormalizer.MAX_LENGTH, edit.remainder.codePointCount(0, edit.remainder.length))
    }

    // ── suggestions ─────────────────────────────────────────────────────────────────────────────

    private fun usage(vararg pairs: Pair<String, Int>) =
        pairs.map { (label, count) -> LabelUsage(LabelNormalizer.fold(label), label, count) }

    @Test
    fun `with nothing typed the most used labels not on the photo are offered`() {
        val all = usage("Family" to 9, "Beach" to 5, "Work" to 2)
        assertEquals(listOf("Family", "Work"), LabelEditorInput.suggestions(all, listOf("beach"), ""))
    }

    @Test
    fun `typed text narrows by folded contains, prefix matches first`() {
        val all = usage("Kids beach" to 9, "Beach" to 3, "Balaton" to 8, "Work" to 1)
        assertEquals(listOf("Beach", "Kids beach"), LabelEditorInput.suggestions(all, emptyList(), "BEA"))
    }

    @Test
    fun `accents are ignored when narrowing`() {
        val all = usage("Nyaralás" to 4)
        assertEquals(listOf("Nyaralás"), LabelEditorInput.suggestions(all, emptyList(), "nyaralas"))
    }

    @Test
    fun `at most eight are offered`() {
        val all = (1..12).map { LabelUsage("l$it", "L$it", 20 - it) }
        val out = LabelEditorInput.suggestions(all, emptyList(), "")
        assertEquals(LabelEditorInput.SUGGESTION_LIMIT, out.size)
        assertEquals("L1", out.first())
    }
}
