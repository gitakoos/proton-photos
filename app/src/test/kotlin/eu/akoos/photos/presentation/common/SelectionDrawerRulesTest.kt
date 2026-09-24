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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules the selection drawer lays itself out by: which actions become the fixed buttons and
 * in what order, where they stand in the open list, when More shows, when Select all asks first, when
 * its label gives way to a glyph, how far the drawer travels as its button row folds away, how tall
 * the resting bar is, which a grid reserves before the drawer lays out, and when its controls take a
 * tap. No Android, no Compose runtime: plain JVM assertions.
 */
class SelectionDrawerRulesTest {

    private fun action(label: String, slot: PrimarySlot? = null) =
        SelectionAction(icon = Icons.Default.Face, label = label, onClick = {}, slot = slot)

    private fun labels(actions: List<SelectionAction>) = actions.map { it.label }

    // A timeline-like set: Select all first, the three slotted actions spread through the list.
    private val timeline = listOf(
        action("Select all"),
        action("Share", PrimarySlot.Share),
        action("Add to album", PrimarySlot.Organize),
        action("Favourite"),
        action("Hide"),
        action("Delete", PrimarySlot.Delete),
    )

    // Line heights at the default text size: the count's and Select all's.
    private val countLine = 24.dp
    private val selectAllLine = 16.dp

    @Test
    fun `primary actions come out in slot order whatever order the surface lists them in`() {
        val shuffled = listOf(
            action("Delete", PrimarySlot.Delete),
            action("Hide"),
            action("Share", PrimarySlot.Share),
            action("Move to album", PrimarySlot.Organize),
        )
        assertEquals(listOf("Share", "Move to album", "Delete"), labels(primaryActions(shuffled)))
    }

    @Test
    fun `two actions sharing a slot keep the order they were given in`() {
        val actions = listOf(
            action("Delete", PrimarySlot.Delete),
            action("Move to folder", PrimarySlot.Organize),
            action("Add to album", PrimarySlot.Organize),
        )
        assertEquals(listOf("Move to folder", "Add to album", "Delete"), labels(primaryActions(actions)))
    }

    @Test
    fun `the open list leads with the primaries in slot order and keeps the rest in their order`() {
        assertEquals(
            listOf("Share", "Add to album", "Delete", "Select all", "Favourite", "Hide"),
            labels(expandedActionOrder(timeline)),
        )
    }

    @Test
    fun `the open list holds every action exactly once`() {
        val listed = expandedActionOrder(timeline)
        assertEquals(timeline.size, listed.size)
        assertEquals(labels(timeline).sorted(), labels(listed).sorted())
    }

    @Test
    fun `a surface that slots nothing keeps its list exactly as given`() {
        val plain = listOf(action("Remove"), action("Restore"))
        assertTrue(primaryActions(plain).isEmpty())
        assertEquals(labels(plain), labels(expandedActionOrder(plain)))
    }

    @Test
    fun `More shows only beside slotted buttons with something left for the open list`() {
        assertTrue(showsMoreButton(timeline, compact = false))
        // Every action already has a button, so the open list has nothing more to offer.
        val allSlotted = listOf(action("Share", PrimarySlot.Share), action("Delete", PrimarySlot.Delete))
        assertFalse(showsMoreButton(allSlotted, compact = false))
        // Nothing slotted means no button row at all, so no More either.
        assertFalse(showsMoreButton(listOf(action("Remove"), action("Restore")), compact = false))
        assertFalse(showsMoreButton(emptyList(), compact = false))
    }

    @Test
    fun `the compact drawer has no More because its chevron opens the same list`() {
        assertFalse(showsMoreButton(timeline, compact = true))
    }

    @Test
    fun `Select all asks first only past the threshold`() {
        assertEquals(500, SELECT_ALL_CONFIRM_THRESHOLD)
        assertFalse(selectAllNeedsConfirm(null))
        assertFalse(selectAllNeedsConfirm(0))
        assertFalse(selectAllNeedsConfirm(499))
        assertFalse(selectAllNeedsConfirm(500))
        assertTrue(selectAllNeedsConfirm(501))
        assertTrue(selectAllNeedsConfirm(12_000))
    }

    @Test
    fun `a short window takes the compact drawer and an upright phone or a tablet does not`() {
        assertTrue(isCompactDrawer(336))
        assertTrue(isCompactDrawer(479))
        assertFalse(isCompactDrawer(480))
        assertFalse(isCompactDrawer(780))
        assertFalse(isCompactDrawer(1200))
    }

    @Test
    fun `the Select all label shows only while the count keeps its full length beside it`() {
        assertTrue(selectAllFitsAsText(available = 744, countWidth = 210, labelWidth = 330, gap = 24))
        assertTrue(selectAllFitsAsText(available = 564, countWidth = 210, labelWidth = 330, gap = 24))
        assertFalse(selectAllFitsAsText(available = 563, countWidth = 210, labelWidth = 330, gap = 24))
    }

    @Test
    fun `the resting bar without a button row sums the handle and count row and strip and rule`() {
        // 24 handle band, 44 count row, 10 air, 56 strip, 11 rule, 12 bottom air.
        assertEquals(
            157.dp,
            restingDrawerHeight(countLine, selectAllLine, primaryRow = false, compact = false),
        )
    }

    @Test
    fun `the icon-only button row adds a low band at rest`() {
        val withoutRow = restingDrawerHeight(countLine, selectAllLine, primaryRow = false, compact = false)
        val withRow = restingDrawerHeight(countLine, selectAllLine, primaryRow = true, compact = false)
        // 9dp padding above and below a 22dp glyph; the labels live in the open list.
        assertEquals(40.dp, primaryButtonHeight())
        // Plus 8dp of air under the rule.
        assertEquals(48.dp, primaryRowHeight())
        assertEquals(205.dp, withRow)
        assertEquals(48.dp, withRow - withoutRow)
    }

    @Test
    fun `the compact drawer does not grow for its buttons`() {
        assertEquals(
            157.dp,
            restingDrawerHeight(countLine, selectAllLine, primaryRow = true, compact = true),
        )
    }

    @Test
    fun `larger text grows only the count row, never the icon-only buttons`() {
        // A count line resolving to 40dp lifts the count row from 44dp to 48dp; the buttons carry no
        // text, so the row keeps its 48dp band.
        assertEquals(
            209.dp,
            restingDrawerHeight(countLine = 40.dp, selectAllLine = selectAllLine, primaryRow = true, compact = false),
        )
    }

    @Test
    fun `the count row grows once its text outgrows the round controls`() {
        assertEquals(44.dp, headerRowHeight(countLine = 24.dp, selectAllLine = 16.dp))
        assertEquals(48.dp, headerRowHeight(countLine = 40.dp, selectAllLine = 16.dp))
        // The Select all pill, its line plus 6dp above and below, can be the tallest item too.
        assertEquals(46.dp, headerRowHeight(countLine = 30.dp, selectAllLine = 26.dp))
    }

    @Test
    fun `the top edge follows the drag one to one while the row folds into the list`() {
        val list = 440f
        val row = 69f
        val travel = detentTravel(list, row, minTravel = 24f)
        assertEquals(371f, travel, 0f)
        for (reveal in listOf(0f, 50f, 185.5f, 300f, travel)) {
            val fraction = openFraction(reveal, travel)
            val shown = row * (1f - fraction) + list * fraction
            assertEquals("reveal=$reveal", row + reveal, shown, 0.01f)
        }
    }

    @Test
    fun `without a button row the drawer travels the whole list`() {
        assertEquals(440f, detentTravel(440f, 0f, minTravel = 24f), 0f)
        assertEquals(1f, openFraction(440f, 440f), 0f)
    }

    @Test
    fun `a list barely taller than the row keeps a usable travel`() {
        assertEquals(24f, detentTravel(80f, 69f, minTravel = 24f), 0f)
    }

    @Test
    fun `the open fraction is clamped and safe on a zero travel`() {
        assertEquals(0f, openFraction(-5f, 100f), 0f)
        assertEquals(0.5f, openFraction(50f, 100f), 0f)
        assertEquals(1f, openFraction(150f, 100f), 0f)
        assertEquals(0f, openFraction(10f, 0f), 0f)
    }

    @Test
    fun `a closing drawer takes no tap on its buttons or rows`() {
        // Its rows and fully shown buttons still draw the selection that just ended.
        assertFalse(drawerTakesTaps(opened = false))
        assertFalse(drawerTakesTaps(opened = false, shownAlpha = 1f))
        assertFalse(drawerTakesTaps(opened = false, shownAlpha = 0.4f))
    }

    @Test
    fun `an open drawer's buttons take taps until they have faded away under the list`() {
        assertTrue(drawerTakesTaps(opened = true))
        assertTrue(drawerTakesTaps(opened = true, shownAlpha = 1f))
        assertTrue(drawerTakesTaps(opened = true, shownAlpha = 0.01f))
        assertFalse(drawerTakesTaps(opened = true, shownAlpha = 0f))
    }
}
