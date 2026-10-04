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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelNormalizerTest {

    // clean

    @Test
    fun `clean trims and collapses inner whitespace`() {
        assertEquals("summer trip", LabelNormalizer.clean("  summer \t\n  trip  "))
    }

    @Test
    fun `clean treats a no-break space and other separator spaces as blanks`() {
        assertEquals("a b c", LabelNormalizer.clean(" a b c "))
    }

    @Test
    fun `clean drops control characters as blanks`() {
        assertEquals("a b", LabelNormalizer.clean("a\u0000\u0007b"))
    }

    @Test
    fun `clean returns null for empty or blank input`() {
        assertNull(LabelNormalizer.clean(""))
        assertNull(LabelNormalizer.clean("   "))
        assertNull(LabelNormalizer.clean(" \t\n"))
    }

    @Test
    fun `clean rejects a label holding a comma`() {
        assertNull(LabelNormalizer.clean("beach, sea"))
        assertNull(LabelNormalizer.clean(","))
    }

    @Test
    fun `clean rejects a label of only combining marks`() {
        assertNull(LabelNormalizer.clean("́̈"))
    }

    @Test
    fun `clean keeps a label of exactly the maximum length`() {
        val label = "a".repeat(LabelNormalizer.MAX_LENGTH)
        assertEquals(label, LabelNormalizer.clean(label))
    }

    @Test
    fun `clean cuts a long label to the maximum length and trims the cut`() {
        assertEquals("a".repeat(24), LabelNormalizer.clean("a".repeat(30)))
        // The 24th character is a space, so the cut ends in one and is trimmed.
        assertEquals("a".repeat(23), LabelNormalizer.clean("a".repeat(23) + " bcdef"))
    }

    @Test
    fun `clean counts code points and never splits a surrogate pair`() {
        val emoji = "🌴" // one code point, two UTF-16 units
        val label = emoji.repeat(30)
        val cut = LabelNormalizer.clean(label)!!
        assertEquals(24, cut.codePointCount(0, cut.length))
        assertEquals(emoji.repeat(24), cut)
    }

    @Test
    fun `clean keeps accents, case and non-latin scripts for display`() {
        assertEquals("Árvíztűrő", LabelNormalizer.clean("Árvíztűrő"))
        assertEquals("東京 旅行", LabelNormalizer.clean(" 東京  旅行 "))
    }

    // fold

    @Test
    fun `fold ignores case and accents`() {
        assertEquals(LabelNormalizer.fold("café"), LabelNormalizer.fold("CAFE"))
        assertEquals("arvizturo tukorfurogep", LabelNormalizer.fold("ÁRVÍZTŰRŐ tükörfúrógép"))
    }

    @Test
    fun `fold ignores outer and repeated whitespace`() {
        assertEquals(LabelNormalizer.fold("summer trip"), LabelNormalizer.fold("  Summer   trip "))
    }

    @Test
    fun `fold matches a precomposed and a decomposed accent`() {
        assertEquals(LabelNormalizer.fold("été"), LabelNormalizer.fold("été"))
    }

    @Test
    fun `fold is locale independent for the dotted capital I`() {
        assertEquals("istanbul", LabelNormalizer.fold("ISTANBUL"))
    }

    // parse

    @Test
    fun `parse splits on commas and cleans each label`() {
        val parsed = LabelNormalizer.parse(" beach ,  summer   trip,family ")
        assertEquals(listOf("beach", "summer trip", "family"), parsed.labels)
        assertTrue(parsed.overflow.isEmpty())
        assertFalse(parsed.hitCap)
    }

    @Test
    fun `parse of empty or separator-only input yields nothing`() {
        assertEquals(emptyList<String>(), LabelNormalizer.parse("").labels)
        assertEquals(emptyList<String>(), LabelNormalizer.parse(" , ,, ").labels)
    }

    @Test
    fun `parse drops duplicates by folded form and keeps the first spelling`() {
        val parsed = LabelNormalizer.parse("Café, cafe, CAFÉ, café ")
        assertEquals(listOf("Café"), parsed.labels)
    }

    @Test
    fun `parse keeps the first five labels and reports the sixth deterministically`() {
        val parsed = LabelNormalizer.parse("a, b, c, d, e, f, g")
        assertEquals(listOf("a", "b", "c", "d", "e"), parsed.labels)
        assertEquals(listOf("f", "g"), parsed.overflow)
        assertTrue(parsed.hitCap)
    }

    @Test
    fun `duplicates do not count towards the cap`() {
        val parsed = LabelNormalizer.parse("a, A, b, B, c, d, e")
        assertEquals(listOf("a", "b", "c", "d", "e"), parsed.labels)
        assertFalse(parsed.hitCap)
    }

    @Test
    fun `a label cut to the same text as an earlier one is a duplicate`() {
        val base = "x".repeat(24)
        val parsed = LabelNormalizer.parse("$base, ${base}yz")
        assertEquals(listOf(base), parsed.labels)
    }

    @Test
    fun `normalize applies the same rules to an already split list`() {
        val parsed = LabelNormalizer.normalize(listOf(" Hello ", "hello", "", "a,b", "World"))
        assertEquals(listOf("Hello", "World"), parsed.labels)
    }

    // merge

    @Test
    fun `merge appends new labels after the existing ones`() {
        val merge = LabelNormalizer.merge(listOf("beach"), listOf("sea", "Beach"))
        assertEquals(listOf("beach", "sea"), merge.labels)
        assertEquals(listOf("sea"), merge.added)
        assertTrue(merge.rejected.isEmpty())
    }

    @Test
    fun `merge rejects new labels past the cap and keeps the existing ones`() {
        val merge = LabelNormalizer.merge(listOf("a", "b", "c", "d"), listOf("e", "f", "a"))
        assertEquals(listOf("a", "b", "c", "d", "e"), merge.labels)
        assertEquals(listOf("e"), merge.added)
        assertEquals(listOf("f"), merge.rejected)
        assertTrue(merge.hitCap)
    }

    @Test
    fun `merge into a full photo adds nothing and a duplicate is not a rejection`() {
        val full = listOf("a", "b", "c", "d", "e")
        val merge = LabelNormalizer.merge(full, listOf("B", "z"))
        assertEquals(full, merge.labels)
        assertTrue(merge.added.isEmpty())
        assertEquals(listOf("z"), merge.rejected)
    }

    // canAdd

    @Test
    fun `canAdd reports each outcome`() {
        val four = listOf("a", "b", "c", "d")
        assertEquals(LabelAddCheck.OK, LabelNormalizer.canAdd(four, "e"))
        assertEquals(LabelAddCheck.DUPLICATE, LabelNormalizer.canAdd(four, " A "))
        assertEquals(LabelAddCheck.FULL, LabelNormalizer.canAdd(four + "e", "f"))
        assertEquals(LabelAddCheck.INVALID, LabelNormalizer.canAdd(four, "  "))
        assertEquals(LabelAddCheck.INVALID, LabelNormalizer.canAdd(four, "x,y"))
    }

    @Test
    fun `canAdd on a full photo still reports a duplicate as a duplicate`() {
        assertEquals(LabelAddCheck.DUPLICATE, LabelNormalizer.canAdd(listOf("a", "b", "c", "d", "e"), "É"))
    }
}
