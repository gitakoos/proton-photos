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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the tag set one upload commits, WITHOUT the upload pipeline, a device or a network.
 *
 * Three properties carry the weight. A file nobody categorised must commit precisely what the
 * detector found, because that is what every upload did before a choice could exist and any drift
 * there is a change to every backup. A choice must reach the server without displacing what the
 * file's own bytes prove. And the commit must never carry tag 0, which the server treats as a
 * favourite rather than a category: an id it handles specially inside a commit payload puts the
 * commit at risk, and a rejected commit is a lost upload.
 */
class UploadPhotoTagsTest {

    // ── no choice made: the detection passes through untouched ───────────────────────────────────

    @Test
    fun `no detection and no choice commits nothing`() {
        assertTrue(UploadPhotoTags.mergeForCommit(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `a file nobody categorised commits exactly what the detector found`() {
        // Order included: this is the list every upload has always sent, and it must stay that list.
        val detected = listOf(2, 9, 1, 4, 8)
        assertEquals(detected, UploadPhotoTags.mergeForCommit(detected, emptySet()))
    }

    @Test
    fun `a detection of one tag survives an empty choice`() {
        assertEquals(listOf(2), UploadPhotoTags.mergeForCommit(listOf(2), emptySet()))
    }

    // ── a choice joins the detection ─────────────────────────────────────────────────────────────

    @Test
    fun `chosen categories are added to the detected ones`() {
        assertEquals(listOf(2, 5, 6), UploadPhotoTags.mergeForCommit(listOf(2), setOf(5, 6)))
    }

    @Test
    fun `a choice made on an undetectable file is committed on its own`() {
        // Selfies and Portraits have no local signal at all, so a person is the only source for them.
        assertEquals(listOf(5), UploadPhotoTags.mergeForCommit(emptyList(), setOf(5)))
    }

    @Test
    fun `a choice never displaces what the file itself proves`() {
        // The video marker comes off the mime type; picking categories in the viewer must not cost
        // the photo that marker on the way up.
        val committed = UploadPhotoTags.mergeForCommit(listOf(2), setOf(7))
        assertTrue("the detected video tag stays", 2 in committed)
        assertTrue("the chosen burst tag arrives", 7 in committed)
    }

    // ── tag 0 stays out of the commit ────────────────────────────────────────────────────────────

    @Test
    fun `a chosen favourite is not committed with the categories`() {
        val committed = UploadPhotoTags.mergeForCommit(listOf(2), setOf(UploadPhotoTags.FAVORITE_TAG_ID, 5))
        assertFalse("tag 0 travels on the favourite endpoint, not the commit", 0 in committed)
        assertEquals(listOf(2, 5), committed)
    }

    @Test
    fun `tag 0 is dropped whichever side it arrives from`() {
        assertEquals(listOf(1), UploadPhotoTags.mergeForCommit(listOf(0, 1), emptySet()))
        assertTrue(UploadPhotoTags.mergeForCommit(listOf(0), setOf(0)).isEmpty())
    }

    // ── only ids the server accepts ──────────────────────────────────────────────────────────────

    @Test
    fun `an out-of-range id cannot reach the commit`() {
        // The Drive PhotoTag enum runs 0 to 9 and the server rejects anything else, so one damaged
        // stored id must cost only itself and never the whole upload.
        assertEquals(listOf(2, 9), UploadPhotoTags.mergeForCommit(listOf(2), setOf(10, 9, 42, -1)))
        assertEquals(listOf(3), UploadPhotoTags.mergeForCommit(listOf(99, 3), setOf(-7)))
    }

    @Test
    fun `a choice of nothing but junk leaves the detection alone`() {
        assertEquals(listOf(2, 8), UploadPhotoTags.mergeForCommit(listOf(2, 8), setOf(15, 100, -3)))
    }

    // ── every id once ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an id both detected and chosen is committed once`() {
        assertEquals(listOf(4, 8), UploadPhotoTags.mergeForCommit(listOf(4, 8), setOf(8, 4)))
    }

    @Test
    fun `a repeated id within one source collapses`() {
        assertEquals(listOf(1), UploadPhotoTags.mergeForCommit(listOf(1, 1, 1), emptySet()))
        assertEquals(listOf(1, 5), UploadPhotoTags.mergeForCommit(listOf(1), listOf(5, 5, 1)))
    }

    @Test
    fun `the whole valid range commits as itself minus the favourite`() {
        assertEquals(
            (1..9).toList(),
            UploadPhotoTags.mergeForCommit(emptyList(), UserPhotoTags.VALID_IDS.toList()),
        )
    }
}
