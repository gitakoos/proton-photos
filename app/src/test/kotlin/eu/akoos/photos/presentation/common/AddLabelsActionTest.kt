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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.data.db.dao.LabelBulkResult
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.util.LabelNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddLabelsActionTest {

    @Test
    fun `editing a photo's labels counts a removal or an addition as a change`() {
        val initial = listOf("Camping", "Zoé")
        assertFalse(BulkLabelInput.changed(initial, listOf("camping", "zoe")))
        assertTrue(BulkLabelInput.changed(initial, listOf("Camping")))
        assertTrue(BulkLabelInput.changed(initial, initial + "Lake"))
        assertTrue(BulkLabelInput.changed(emptyList(), listOf("Lake")))
    }

    @Test
    fun `seeded pills fill the cap on their own`() {
        val seeded = listOf("a", "b", "c", "d", "e")
        assertTrue(BulkLabelInput.isFull(seeded))
        assertEquals(seeded, BulkLabelInput.commit(seeded, "f"))
        assertEquals(listOf("a", "b", "c", "d"), BulkLabelInput.remove(seeded, "E"))
    }

    // ── message after a bulk add ────────────────────────────────────────────────────────────────

    @Test
    fun `a failed write reads as a failure`() {
        assertEquals(LabelBulkMessage.Failed, labelBulkMessage(null))
    }

    @Test
    fun `labelled photos are counted with no limit note when none hit the cap`() {
        assertEquals(LabelBulkMessage.Added(4, 0), labelBulkMessage(LabelBulkResult(4, 0)))
    }

    @Test
    fun `photos that hit the cap are counted beside the labelled ones`() {
        assertEquals(LabelBulkMessage.Added(3, 2), labelBulkMessage(LabelBulkResult(3, 2)))
    }

    @Test
    fun `a selection already at the cap names only the limit`() {
        assertEquals(LabelBulkMessage.Limited(5), labelBulkMessage(LabelBulkResult(0, 5)))
    }

    @Test
    fun `nothing changed and nothing capped means the labels were all there`() {
        assertEquals(LabelBulkMessage.NothingNew, labelBulkMessage(LabelBulkResult(0, 0)))
    }

    // ── pills ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `typing without a comma keeps the text in the field`() {
        assertEquals(LabelPillsInput(emptyList(), "beach"), BulkLabelInput.onEdit(emptyList(), "beach"))
    }

    @Test
    fun `a comma turns the typed word into a pill and empties the field`() {
        assertEquals(LabelPillsInput(listOf("beach"), ""), BulkLabelInput.onEdit(emptyList(), "beach,"))
        assertEquals(LabelPillsInput(listOf("beach", "sun"), ""), BulkLabelInput.onEdit(listOf("beach"), " sun ,"))
    }

    @Test
    fun `a pasted list becomes pills and its last piece stays typed`() {
        assertEquals(
            LabelPillsInput(listOf("a", "Lake Balaton"), "c"),
            BulkLabelInput.onEdit(emptyList(), "a,  Lake  Balaton , c"),
        )
    }

    @Test
    fun `a typed duplicate of a pill is dropped quietly`() {
        assertEquals(LabelPillsInput(listOf("Nyaralás"), ""), BulkLabelInput.onEdit(listOf("Nyaralás"), "NYARALAS,"))
        assertEquals(listOf("beach"), BulkLabelInput.commit(listOf("beach"), "Beach, beach"))
    }

    @Test
    fun `blank parts never become pills`() {
        assertEquals(LabelPillsInput(emptyList(), ""), BulkLabelInput.onEdit(emptyList(), " , ,"))
    }

    @Test
    fun `pills stop at five and the field empties once full`() {
        val four = listOf("a", "b", "c", "d")
        assertEquals(LabelPillsInput(four + "e", ""), BulkLabelInput.onEdit(four, "e, f, g"))
        assertTrue(BulkLabelInput.isFull(four + "e"))
        assertFalse(BulkLabelInput.isFull(four))
    }

    @Test
    fun `the cap counts the labels a single photo already has`() {
        val has = listOf("Family", "Work", "Beach")
        assertEquals(2, BulkLabelInput.room(has))
        assertEquals(listOf("x", "y"), BulkLabelInput.commit(emptyList(), "x, y, z", has))
        assertTrue(BulkLabelInput.isFull(listOf("x", "y"), has))
        assertEquals(LabelPillsInput(listOf("x", "y"), ""), BulkLabelInput.onEdit(listOf("x"), "y, z", has))
    }

    @Test
    fun `a photo at the cap leaves no room`() {
        val has = listOf("a", "b", "c", "d", "e")
        assertEquals(0, BulkLabelInput.room(has))
        assertTrue(BulkLabelInput.isFull(emptyList(), has))
        assertEquals(emptyList<String>(), BulkLabelInput.commit(emptyList(), "f", has))
    }

    @Test
    fun `a label the photo already has is not added as a pill`() {
        assertEquals(listOf("sun"), BulkLabelInput.commit(emptyList(), "family, sun", listOf("Family")))
    }

    @Test
    fun `backspace on an empty field takes the last pill`() {
        assertEquals(listOf("a"), BulkLabelInput.onBackspace(listOf("a", "b"), ""))
        assertNull(BulkLabelInput.onBackspace(listOf("a", "b"), "x"))
        assertNull(BulkLabelInput.onBackspace(emptyList(), ""))
    }

    @Test
    fun `a pill comes off by its folded name`() {
        assertEquals(listOf("a", "c"), BulkLabelInput.remove(listOf("a", "Nyaralás", "c"), "nyaralas"))
    }

    @Test
    fun `add applies the pills and the text still in the field`() {
        assertEquals(listOf("a", "b"), BulkLabelInput.labelsToAdd(LabelPillsInput(listOf("a"), " b ")))
        assertEquals(listOf("a"), BulkLabelInput.labelsToAdd(LabelPillsInput(listOf("a"), "A")))
        assertEquals(emptyList<String>(), BulkLabelInput.labelsToAdd(LabelPillsInput(emptyList(), "  ")))
        assertEquals(
            listOf("x"),
            BulkLabelInput.labelsToAdd(LabelPillsInput(listOf("x"), "y"), listOf("a", "b", "c", "d")),
        )
    }

    @Test
    fun `suggestions leave out the pills and the photo's labels and narrow by the typed text`() {
        val usage = listOf(
            usage("Beach", 9), usage("Summer", 5), usage("Sunset", 3), usage("Family", 2),
        )
        assertEquals(listOf("Summer", "Sunset", "Family"), BulkLabelInput.suggestions(usage, listOf("beach"), ""))
        assertEquals(listOf("Summer", "Sunset"), BulkLabelInput.suggestions(usage, listOf("Beach"), "su"))
        assertEquals(listOf("Sunset"), BulkLabelInput.suggestions(usage, listOf("Beach"), "su", listOf("summer")))
    }

    private fun usage(label: String, count: Int) = LabelUsage(LabelNormalizer.fold(label), label, count)
}
