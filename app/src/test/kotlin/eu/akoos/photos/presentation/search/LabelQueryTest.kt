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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.util.LabelNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The label keyword typed into Search: where it is found, what it leaves of the text, and what it names. */
class LabelQueryTest {

    /** English plus Hungarian, as the app builds them for a Hungarian app language. */
    private val keywords = LabelQuery.keywords(listOf("labels|label", "címkék|címke"))

    private fun usage(vararg names: Pair<String, Int>) =
        names.map { (name, count) -> LabelUsage(LabelNormalizer.fold(name), name, count) }

    @Test
    fun `the keyword variants are folded and the longest comes first`() {
        assertEquals(listOf("labels", "cimkek", "label", "cimke"), keywords)
    }

    @Test
    fun `the bare keyword is found in English and in the app language`() {
        assertEquals(LabelTyping("", null), LabelQuery.parse("labels", keywords))
        assertEquals(LabelTyping("", null), LabelQuery.parse("label", keywords))
        assertEquals(LabelTyping("", null), LabelQuery.parse("címkék", keywords))
        assertEquals(LabelTyping("", null), LabelQuery.parse("CIMKE", keywords))
        assertEquals(LabelTyping("", null), LabelQuery.parse("  Labels  ", keywords))
    }

    @Test
    fun `the keyword after other words leaves them as the text`() {
        assertEquals(LabelTyping("beach june", null), LabelQuery.parse("beach june labels", keywords))
        assertEquals(LabelTyping("beach", "trip"), LabelQuery.parse("beach, labels:trip", keywords))
        assertEquals("beach june", LabelQuery.textOf("beach june labels", keywords))
        assertEquals("beach june", LabelQuery.textOf("beach june", keywords))
    }

    @Test
    fun `a word that only holds the keyword is no keyword`() {
        assertNull(LabelQuery.parse("mylabels", keywords))
        assertNull(LabelQuery.parse("labelsx", keywords))
        assertNull(LabelQuery.parse("lab", keywords))
        assertNull(LabelQuery.parse("labels beach", keywords))
        assertNull(LabelQuery.parse("", keywords))
        assertNull(LabelQuery.parse("labels", emptyList()))
    }

    @Test
    fun `a quoted keyword stays exact text`() {
        assertNull(LabelQuery.parse("\"labels:trip\"", keywords))
        assertNull(LabelQuery.parse("\"labels", keywords))
        assertNull(LabelQuery.parse("“beach labels", keywords))
        assertNull(LabelQuery.parse("\"beach\"labels", keywords))
        assertEquals(LabelTyping("\"my trip\"", "x"), LabelQuery.parse("\"my trip\" labels:x", keywords))
        assertEquals(LabelTyping("«a, b»", null), LabelQuery.parse("«a, b» labels", keywords))
    }

    @Test
    fun `an unclosed keyword names nothing yet`() {
        assertEquals(LabelTyping("", ""), LabelQuery.parse("labels:", keywords))
        assertEquals(LabelTyping("", ""), LabelQuery.parse("címkék: ", keywords))
    }

    @Test
    fun `a label name keeps its spaces and colons`() {
        assertEquals(LabelTyping("", "summer trip"), LabelQuery.parse("labels:summer trip", keywords))
        assertEquals(LabelTyping("", "a:b c"), LabelQuery.parse("labels: a:b c", keywords))
        assertEquals(LabelTyping("sea", "10:30 ferry"), LabelQuery.parse("sea label:10:30 ferry", keywords))
    }

    @Test
    fun `a typed name picks only the label of exactly that name`() {
        val labels = usage("beach" to 4, "be" to 1, "Nyaralás" to 2)
        assertEquals("be", LabelQuery.named(LabelTyping("", "BE"), labels)?.label)
        assertEquals("Nyaralás", LabelQuery.named(LabelTyping("", "nyaralas"), labels)?.label)
        assertNull(LabelQuery.named(LabelTyping("", "bea"), labels))
        assertNull(LabelQuery.named(LabelTyping("", ""), labels))
        assertNull(LabelQuery.named(LabelTyping("", null), labels))
    }

    @Test
    fun `the chip row offers the labels the typed part holds, picked ones left out`() {
        val labels = usage("beach" to 4, "trip" to 3, "be" to 1)
        assertEquals(listOf("beach", "trip", "be"), LabelQuery.offered(LabelTyping("", null), labels, emptySet()).map { it.label })
        assertEquals(listOf("beach", "be"), LabelQuery.offered(LabelTyping("", "BE"), labels, emptySet()).map { it.label })
        assertEquals(listOf("trip", "be"), LabelQuery.offered(LabelTyping("", ""), labels, setOf("beach")).map { it.label })
    }

    @Test
    fun `a picked label or the typed keyword makes a label search, so content search and name rows stay off`() {
        assertTrue(LabelQuery.isLabelSearch("", ContentFilter().withLabels(setOf("beach")), keywords))
        assertTrue(LabelQuery.isLabelSearch("sunset", ContentFilter().withLabels(setOf("beach")), keywords))
        assertTrue(LabelQuery.isLabelSearch("labels:jun", ContentFilter(), keywords))
        assertFalse(LabelQuery.isLabelSearch("sunset", ContentFilter(), keywords))
        assertFalse(LabelQuery.isLabelSearch("", ContentFilter(), keywords))
    }

    @Test
    fun `a label named like a month is held back from the date pill while the keyword is typed`() {
        val q = "labels:my june"
        val months = SearchFilter.foldedMonthNames(Locale.ENGLISH)
        // On the raw text the date pill would take "june" out of the field; the keyword check stops it.
        assertTrue(DateSuggestions.term(q, months, SearchFilter.foldedMonthAbbreviations(Locale.ENGLISH)) != null)
        assertEquals(LabelTyping("", "my june"), LabelQuery.parse(q, keywords))
    }
}
