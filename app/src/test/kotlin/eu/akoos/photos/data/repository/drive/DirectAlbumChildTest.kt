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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule every path that writes a photo row answers through [isDirectAlbumChild]: does this row
 * exist only inside an album, or is it a photo of the user's own stream? The answer decides whether
 * the timeline shows it and whether the refresh sweep may delete it, so both directions are wrong in
 * a visible way: a false negative puts someone else's contribution in the user's timeline, a false
 * positive hides one of the user's own photos.
 *
 * Pure sets and strings → no DI, no DB, no crypto.
 */
class DirectAlbumChildTest {

    @Test
    fun `a photo parented to a known album is an album child`() {
        // The contributed copy: it lands on the album owner's volume parented to the album, and the
        // volume's photo listing never returns it.
        assertTrue(isDirectAlbumChild(ALBUM, setOf(ALBUM), PHOTOS_ROOT, wireSaysChild = false))
    }

    @Test
    fun `a photo the user added to their own album stays a stream photo`() {
        // The rule that must not break. Adding to an album rewraps the photo's passphrase but does
        // not move it, so it is still parented to the photos root and still belongs on the timeline
        // even though the album holds it.
        assertFalse(isDirectAlbumChild(PHOTOS_ROOT, setOf(ALBUM), PHOTOS_ROOT, wireSaysChild = false))
    }

    @Test
    fun `the user's own root outranks the listing's answer`() {
        // The veto, and the reason it exists: a wrong true costs the user their own photos off their
        // own timeline, so a row sitting in their own root is never an album child however the
        // listing answers. Nothing else in the rule can override the parent this way.
        assertFalse(isDirectAlbumChild(PHOTOS_ROOT, emptySet(), PHOTOS_ROOT, wireSaysChild = true))
        assertFalse(isDirectAlbumChild(PHOTOS_ROOT, setOf(PHOTOS_ROOT), PHOTOS_ROOT, wireSaysChild = true))
    }

    @Test
    fun `the listing's answer settles a parent the root does not claim`() {
        // Away from the root the endpoint knows better than any parent-side guess, which is what
        // covers a contribution to an album this device has not cached yet.
        assertTrue(isDirectAlbumChild("uncached-album", emptySet(), PHOTOS_ROOT, wireSaysChild = true))
    }

    @Test
    fun `an unknown root leaves the rest of the rule intact`() {
        // The root is read from a cache that can still be cold. With nothing to veto, the parent and
        // the listing decide exactly as they otherwise would.
        assertTrue(isDirectAlbumChild(ALBUM, setOf(ALBUM), null, wireSaysChild = false))
        assertFalse(isDirectAlbumChild("some-link", emptySet(), null, wireSaysChild = false))
    }

    @Test
    fun `an unknown parent is not treated as an album`() {
        // The album list can be empty on a first sync. Guessing "album" from an unrecognised parent
        // would hide the user's own photos, so the unknown case resolves to a stream photo.
        assertFalse(isDirectAlbumChild("some-link", emptySet(), PHOTOS_ROOT, wireSaysChild = false))
    }

    @Test
    fun `a row with no parent is not an album child`() {
        // Rows written before the parent column existed carry no parent and are the user's own.
        assertFalse(isDirectAlbumChild(null, setOf(ALBUM), PHOTOS_ROOT, wireSaysChild = false))
        assertFalse(isDirectAlbumChild(null, emptySet(), PHOTOS_ROOT, wireSaysChild = true))
    }

    @Test
    fun `any cached album counts, shared-with-me included`() {
        // A photo inside an album someone else shared is outside this user's stream just as much as
        // one inside an album of their own, so both kinds of cached album answer the same way.
        assertTrue(isDirectAlbumChild("their-album", setOf(ALBUM, "their-album"), PHOTOS_ROOT, wireSaysChild = false))
    }

    private companion object {
        const val ALBUM = "album-1"
        const val PHOTOS_ROOT = "photos-root"
    }
}
