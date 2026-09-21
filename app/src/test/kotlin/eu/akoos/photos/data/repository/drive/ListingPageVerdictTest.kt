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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the listing walk reads one page.
 *
 * The distinction under test is which answers may end the walk AND count it as having seen the whole
 * library. Both the natural end and the defensive stall leave the loop by setting the cursor to null,
 * so the loop condition alone cannot tell them apart — and crediting the stall is what let a backend
 * repeating a cursor look identical to "the server has no more photos", handing the sweep every
 * unlisted photo past that point as its delete set. Pure data in, verdict out.
 */
class ListingPageVerdictTest {

    @Test
    fun `an empty page is the only answer that means the library ended`() {
        assertEquals(
            ListingPageVerdict.Exhausted,
            listingPageVerdict(pageLinkIds = emptyList(), queriedCursor = "p9"),
        )
    }

    @Test
    fun `a page ending on the cursor it was queried with is a stall, not an end`() {
        // The walk stops either way, but it stopped where the server never said the library ended,
        // so this must not be credited: the photos after this page were never listed at all.
        assertEquals(
            ListingPageVerdict.CursorStalled,
            listingPageVerdict(pageLinkIds = listOf("p8", "p9"), queriedCursor = "p9"),
        )
    }

    @Test
    fun `a short page keeps the walk going`() {
        // The timeline endpoint carries no More/AnchorID and can answer with fewer links than asked
        // while older photos still follow, so page length says nothing about the end.
        assertEquals(
            ListingPageVerdict.Advance("p3"),
            listingPageVerdict(pageLinkIds = listOf("p3"), queriedCursor = "p2"),
        )
    }

    @Test
    fun `the first page of a fresh walk is never mistaken for a stall`() {
        // A fresh walk queries with no cursor at all, which must not compare equal to a real linkId.
        assertEquals(
            ListingPageVerdict.Advance("p2"),
            listingPageVerdict(pageLinkIds = listOf("p1", "p2"), queriedCursor = null),
        )
    }

    @Test
    fun `an empty first page ends a walk over an empty library`() {
        // A brand-new account lists nothing and is complete on the first answer, which is what lets
        // the sweep run at all on an account whose photos were all deleted elsewhere.
        assertEquals(
            ListingPageVerdict.Exhausted,
            listingPageVerdict(pageLinkIds = emptyList(), queriedCursor = null),
        )
    }

    @Test
    fun `a cursor reappearing anywhere but last still advances the walk`() {
        // Only the LAST link becomes the next cursor, so an earlier repeat is a duplicate row in the
        // page, not a stall — treating it as one would stop a walk that is making progress.
        assertEquals(
            ListingPageVerdict.Advance("p4"),
            listingPageVerdict(pageLinkIds = listOf("p2", "p3", "p4"), queriedCursor = "p2"),
        )
    }
}
