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

package eu.akoos.photos.data.hidden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins what a screen holding a vault photo under an old uri can still find it by.
 *
 * A rename and a capture-date edit both move a vault file, and the screens showing it work from lists
 * they took before the move. What matters here is that a lookup stays ONE step however many moves a
 * photo has been through, since nothing chases a chain at read time.
 */
class HiddenVaultMovesTest {

    private val first = "file:///data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"
    private val second = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1783507135000.jpg"
    private val third = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1700000000000.jpg"

    @Test
    fun `a rename records where the file went and what it is now called`() {
        val after = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        assertEquals(VaultMove(second, "Beach trip.jpg"), after[first])
    }

    @Test
    fun `a date edit records the move and leaves the name alone`() {
        val after = HiddenVaultMoves.folded(emptyMap(), first, second, displayName = null)

        assertEquals(VaultMove(second, null), after[first])
    }

    @Test
    fun `a second move re-points the first, so the oldest snapshot still resolves in one step`() {
        val renamed = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        val restamped = HiddenVaultMoves.folded(renamed, second, third, displayName = null)

        // A screen opened before either edit, and one opened between them, both land on the file.
        assertEquals(third, restamped[first]?.uri)
        assertEquals(third, restamped[second]?.uri)
    }

    @Test
    fun `a move that leaves the name alone carries the name the earlier rename gave`() {
        val renamed = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        val restamped = HiddenVaultMoves.folded(renamed, second, third, displayName = null)

        assertEquals("Beach trip.jpg", restamped[first]?.displayName)
    }

    @Test
    fun `a rename after a date edit gives the newer name to every entry that follows the file`() {
        val restamped = HiddenVaultMoves.folded(emptyMap(), first, second, displayName = null)

        val renamed = HiddenVaultMoves.folded(restamped, second, third, "Sunset.jpg")

        assertEquals(VaultMove(third, "Sunset.jpg"), renamed[first])
        assertEquals(VaultMove(third, "Sunset.jpg"), renamed[second])
    }

    @Test
    fun `a move of no distance writes nothing`() {
        val moves = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        assertSame(moves, HiddenVaultMoves.folded(moves, third, third, "Anything.jpg"))
    }

    @Test
    fun `another photo's move leaves this one's entry alone`() {
        val mine = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")
        val other = "file:///data/user/0/eu.akoos.photos/files/hidden/c1d2__1783507135000.png"

        val after = HiddenVaultMoves.folded(mine, other, "$other.moved", "Other.png")

        assertEquals(VaultMove(second, "Beach trip.jpg"), after[first])
    }

    @Test
    fun `a photo that never moved has no entry at all`() {
        val after = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        assertNull(after[third])
    }

    @Test
    fun `a rename back onto the name the file started under resolves to the file`() {
        // The first move frees that name, so a later photo can legitimately be given it.
        val there = HiddenVaultMoves.folded(emptyMap(), first, second, "Beach trip.jpg")

        val back = HiddenVaultMoves.folded(there, second, first, "Original.jpg")

        assertEquals(first, back[second]?.uri)
        assertEquals(first, back[first]?.uri)
    }
}
