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
 * Verifies what a finished media scan PROVES about a file it did not return, WITHOUT a device or
 * MediaStore in the loop. Every uri-keyed side store prunes itself on this one answer, so a wrong
 * one deletes what a person cannot put back: a chosen category, or the recorded capture date that is
 * the only date a downloaded PNG or WebP has left once its cloud copy is gone (#34).
 *
 * The whole question is whether absence from the scan's result is evidence of deletion or merely
 * evidence of a gap in what the scan covered.
 */
class MediaScanCoverageTest {

    private val images = "content://media/external/images/media"
    private val video = "content://media/external/video/media"
    private val bothRoots = listOf(images, video)

    private fun deleted(
        uris: Collection<String>,
        live: Set<String>,
        roots: Collection<String> = bothRoots,
    ): List<String> = MediaScanCoverage.provenDeleted(uris, live, roots)

    // ── the plain decision ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a uri the scan no longer sees is proven deleted`() {
        val gone = "$images/2"
        assertEquals(listOf(gone), deleted(uris = listOf("$images/1", gone), live = setOf("$images/1")))
    }

    @Test
    fun `a uri the scan still sees is not`() {
        assertTrue(deleted(uris = listOf("$images/1"), live = setOf("$images/1")).isEmpty())
    }

    @Test
    fun `nothing qualifying answers with nothing`() {
        // Every uri is live, so the caller must be able to skip the write entirely rather than issue
        // one that changes nothing on every single media scan.
        val live = setOf("$images/1", "$images/2", "$video/7")
        assertTrue(deleted(uris = live.toList(), live = live).isEmpty())
        assertTrue(deleted(uris = emptyList(), live = live).isEmpty())
    }

    @Test
    fun `both collections are decided in the same pass`() {
        val result = deleted(
            uris = listOf("$images/1", "$images/2", "$video/7", "$video/8"),
            live = setOf("$images/1", "$video/8"),
        )
        assertEquals(listOf("$images/2", "$video/7"), result)
    }

    // ── condition 1: an empty live set is a failed scan, not an empty device ─────────────────────

    @Test
    fun `an empty live set proves nothing`() {
        // A scan that failed, that was cut short, or that ran before its data arrived produces
        // exactly this shape: no live uris and a full store. Read literally it says every file on
        // the device is gone, so acting on it would empty the store in one pass. A device with
        // genuinely no photos left costs only what the next successful scan would have cleared.
        val uris = listOf("$images/1", "$images/2", "$video/7")
        assertTrue(deleted(uris = uris, live = emptySet()).isEmpty())
    }

    @Test
    fun `no scanned root proves nothing`() {
        // Same reasoning one step earlier: if not one collection query got through, the live set
        // describes nothing and cannot contradict any uri.
        assertTrue(deleted(uris = listOf("$images/1"), live = setOf("$video/7"), roots = emptyList()).isEmpty())
    }

    // ── condition 2: only what the scan actually covered ─────────────────────────────────────────

    @Test
    fun `a collection whose query failed proves nothing about its own uris`() {
        // Images and videos are separate permissions from Android 13, so a user who grants photos
        // but not videos fails the video query on every scan while the images query succeeds. The
        // videos are all still on the device; only the scan cannot see them. The media permission
        // check passes on either grant, so this scan runs and returns a perfectly ordinary-looking
        // images-only result.
        val result = deleted(
            uris = listOf("$images/1", "$images/2", "$video/7", "$video/8"),
            live = setOf("$images/1"),
            roots = listOf(images),
        )
        assertEquals(listOf("$images/2"), result)
    }

    @Test
    fun `a vaulted file uri is never proven deleted`() {
        // A hidden device-only photo lives in app-private storage and no MediaStore query returns
        // it, so it is absent from every live set for as long as it exists.
        val vaulted = "file:///data/user/0/eu.akoos.photos/files/hidden/a1b2__1783507135000.jpg"
        assertTrue(deleted(uris = listOf(vaulted), live = setOf("$images/1")).isEmpty())
    }

    @Test
    fun `a uri that merely starts like a scanned root is not treated as part of it`() {
        // The root is matched with its trailing separator, so a sibling path sharing the prefix
        // stays outside the scan's proven area instead of being deleted by a string coincidence.
        assertTrue(deleted(uris = listOf("${images}_backup/1"), live = setOf("$images/1")).isEmpty())
    }
}
