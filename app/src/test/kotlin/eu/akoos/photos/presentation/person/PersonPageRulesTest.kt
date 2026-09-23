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

package eu.akoos.photos.presentation.person

import eu.akoos.photos.data.db.entity.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the person page moves once its group is named or merged: where the name-or-merge sheet sends
 * it, whether that person's page opens or is already the one below, when an emptied grid hands back to
 * the caller (exactly once, and never under the open sheet), when a freshly named group looks for its
 * merge suggestion, and when a held suggestion for a group merged away is dropped.
 */
class PersonPageRulesTest {

    private fun leaves(
        isEmpty: Boolean = true,
        hadPhotos: Boolean = true,
        isLoading: Boolean = false,
        leaving: Boolean = false,
        sheetOpen: Boolean = false,
    ) = leavesEmptiedPage(isEmpty, hadPhotos, isLoading, leaving, sheetOpen)

    @Test
    fun `a group that kept its id was only named, so the page stays`() {
        assertNull(pageAfterGroupNamed(pagePersonId = 7L, resultPersonId = 7L))
    }

    @Test
    fun `a group merged into someone opens the page of that person`() {
        assertEquals(3L, pageAfterGroupNamed(pagePersonId = 7L, resultPersonId = 3L))
    }

    @Test
    fun `an invalid result opens no page`() {
        assertNull(pageAfterGroupNamed(pagePersonId = 7L, resultPersonId = -1L))
    }

    @Test
    fun `a group merged into the person on the page below goes back to that page`() {
        assertFalse(opensMergedPerson(revealedPersonId = 3L, targetPersonId = 3L))
    }

    @Test
    fun `a group merged into someone other than the person below opens their page`() {
        assertTrue(opensMergedPerson(revealedPersonId = 3L, targetPersonId = 5L))
    }

    @Test
    fun `a group whose page sits over a screen that is not a person opens the person it joined`() {
        assertTrue(opensMergedPerson(revealedPersonId = null, targetPersonId = 3L))
    }

    @Test
    fun `a grid emptied after its photos showed hands back`() {
        assertTrue(leaves())
    }

    @Test
    fun `a page with photos, still loading, or opened empty stays`() {
        assertFalse(leaves(isEmpty = false))
        assertFalse(leaves(isLoading = true))
        assertFalse(leaves(hadPhotos = false))
    }

    @Test
    fun `a page already leaving is not left a second time`() {
        assertFalse(leaves(leaving = true))
    }

    @Test
    fun `the open name-or-merge sheet holds the page while its merge empties the grid`() {
        assertFalse(leaves(sheetOpen = true))
        // Once the sheet is closed without a result, an emptied grid hands back as usual.
        assertTrue(leaves(sheetOpen = false))
    }

    @Test
    fun `a group open unnamed that gets a name looks for its merge suggestion`() {
        assertTrue(looksForSuggestionOnNaming(wasNamed = false, isNamed = true))
    }

    @Test
    fun `the first read, a rename and a cleared name look for none`() {
        assertFalse(looksForSuggestionOnNaming(wasNamed = null, isNamed = true))
        assertFalse(looksForSuggestionOnNaming(wasNamed = null, isNamed = false))
        assertFalse(looksForSuggestionOnNaming(wasNamed = true, isNamed = true))
        assertFalse(looksForSuggestionOnNaming(wasNamed = true, isNamed = false))
        assertFalse(looksForSuggestionOnNaming(wasNamed = false, isNamed = false))
    }

    @Test
    fun `a held suggestion whose group was merged away is dropped`() {
        assertTrue(dropsHeldSuggestion(candidateRow = null))
    }

    @Test
    fun `a held suggestion whose group still exists stays until the next lookup`() {
        assertFalse(dropsHeldSuggestion(candidateRow = PersonEntity(id = 5L, userId = "account")))
    }
}
