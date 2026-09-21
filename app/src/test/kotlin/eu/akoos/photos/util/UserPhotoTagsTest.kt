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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure encoding of the categories a user picks for a device photo, WITHOUT the app, a
 * device or the database in the loop.
 *
 * Two properties carry the weight. One is that the Drive PhotoTag enum runs 0 to 9 and the server
 * rejects anything else, so an id outside that range must never survive a round trip in either
 * direction. The other is that a damaged stored value costs the user only the ids that are actually
 * damaged, never the whole file's choice.
 */
class UserPhotoTagsTest {

    // ── encode: in-range, deduplicated, ordered ──────────────────────────────────────────────────

    @Test
    fun `no chosen category encodes to the empty column value`() {
        // The empty string is the column's default, so "chose nothing" and "never asked" agree.
        assertEquals("", UserPhotoTags.encode(emptyList()))
    }

    @Test
    fun `a single category encodes to its bare id`() {
        assertEquals("1", UserPhotoTags.encode(listOf(1)))
    }

    @Test
    fun `several categories encode comma-separated in ascending order`() {
        // Ascending regardless of the order they were picked in, so the same choice always produces
        // the same string and a write can be compared against what is stored.
        assertEquals("1,4,9", UserPhotoTags.encode(listOf(9, 1, 4)))
        assertEquals("1,4,9", UserPhotoTags.encode(listOf(4, 9, 1)))
    }

    @Test
    fun `the whole valid range encodes intact`() {
        assertEquals("0,1,2,3,4,5,6,7,8,9", UserPhotoTags.encode(UserPhotoTags.VALID_IDS.toList()))
    }

    @Test
    fun `a repeated id is stored once`() {
        assertEquals("2", UserPhotoTags.encode(listOf(2, 2, 2)))
        assertEquals("1,2", UserPhotoTags.encode(listOf(2, 1, 2)))
    }

    @Test
    fun `an out-of-range id is dropped and the rest is kept`() {
        // 10 and up, and anything negative, are not PhotoTag ids: the server refuses them, so storing
        // one would only produce a value that can never be acted on. Dropping the single bad id keeps
        // the categories beside it usable.
        assertEquals("1,9", UserPhotoTags.encode(listOf(1, 10, 9, 42, -1)))
        assertEquals("", UserPhotoTags.encode(listOf(15, -3, 100)))
    }

    // ── decode: the same rules, read back ────────────────────────────────────────────────────────

    @Test
    fun `an empty or absent column decodes to no categories`() {
        assertTrue(UserPhotoTags.decode("").isEmpty())
        assertTrue("a row that does not exist reads as no choice", UserPhotoTags.decode(null).isEmpty())
    }

    @Test
    fun `a single id decodes to that one category`() {
        assertEquals(setOf(4), UserPhotoTags.decode("4"))
    }

    @Test
    fun `several ids decode to the whole chosen set`() {
        assertEquals(setOf(1, 4, 9), UserPhotoTags.decode("1,4,9"))
    }

    @Test
    fun `a repeated id decodes once`() {
        assertEquals(setOf(2), UserPhotoTags.decode("2,2,2"))
    }

    @Test
    fun `an out-of-range id in a stored value is dropped on read`() {
        // A row written by something that did not enforce the range still yields its usable ids only,
        // so a bad value can never reach the API as a filter or an upload tag.
        assertEquals(setOf(1, 9), UserPhotoTags.decode("1,10,9,-2"))
        assertTrue(UserPhotoTags.decode("10,11,12").isEmpty())
    }

    @Test
    fun `malformed entries are skipped rather than losing the row`() {
        // Empty fields, stray separators, whitespace and outright junk: each bad field costs only
        // itself. Anything that throws here would take a user's whole choice with it.
        assertEquals(setOf(1, 3), UserPhotoTags.decode("1,,3"))
        assertEquals(setOf(1, 3), UserPhotoTags.decode(" 1 , 3 "))
        assertEquals(setOf(2), UserPhotoTags.decode("abc,2,x9"))
        assertEquals(setOf(5), UserPhotoTags.decode(",5,"))
        assertTrue(UserPhotoTags.decode(",,,").isEmpty())
        assertTrue("a number too large for Int is junk, not an id", UserPhotoTags.decode("99999999999").isEmpty())
    }

    // ── round trip ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `encode then decode returns exactly the chosen categories`() {
        val chosen = setOf(0, 2, 8)
        assertEquals(chosen, UserPhotoTags.decode(UserPhotoTags.encode(chosen)))
    }

    @Test
    fun `encode then decode strips what was never storable`() {
        assertEquals(setOf(3), UserPhotoTags.decode(UserPhotoTags.encode(listOf(3, 3, 77, -5))))
    }
}
