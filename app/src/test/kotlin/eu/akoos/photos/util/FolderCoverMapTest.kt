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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pinned device-folder covers survive the round trip through their flattened
 * preference, WITHOUT a device in the loop. A folder is identified by its bucket display name, which
 * a person types, so the encoding has to hold names the separator character appears in.
 */
class FolderCoverMapTest {

    private val camera = "content://media/external/images/media/11"
    private val screenshot = "content://media/external/images/media/22"
    private val clip = "content://media/external/video/media/33"

    // ── the round trip ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an encoded cover parses back to the folder it was pinned to`() {
        val entries = setOf(FolderCoverMap.encode("Camera", camera))
        assertEquals(mapOf("Camera" to camera), FolderCoverMap.parse(entries))
    }

    @Test
    fun `folders keep their own covers`() {
        val entries = setOf(
            FolderCoverMap.encode("Camera", camera),
            FolderCoverMap.encode("Screenshots", screenshot),
        )
        assertEquals(mapOf("Camera" to camera, "Screenshots" to screenshot), FolderCoverMap.parse(entries))
    }

    @Test
    fun `nothing stored is no covers`() {
        assertTrue(FolderCoverMap.parse(null).isEmpty())
        assertTrue(FolderCoverMap.parse(emptySet()).isEmpty())
    }

    @Test
    fun `an entry naming no folder or no uri is dropped`() {
        // A half-written entry pins nothing, and reading it as either half would attach a cover to a
        // folder nobody chose.
        val entries = setOf("Camera", "|$camera", "Camera|", "")
        assertTrue(FolderCoverMap.parse(entries).isEmpty())
    }

    // ── a folder name holding the separator ──────────────────────────────────────────────────────

    @Test
    fun `a folder name containing the separator resolves whole`() {
        // Reading the uri from the right is what makes this work: split from the left and the cover
        // lands on a folder named "Trip", which does not exist.
        val entries = setOf(FolderCoverMap.encode("Trip|2026", camera))
        assertEquals(mapOf("Trip|2026" to camera), FolderCoverMap.parse(entries))
    }

    @Test
    fun `a separator-bearing folder replaces only its own cover`() {
        val entries = setOf(
            FolderCoverMap.encode("Trip|2026", camera),
            FolderCoverMap.encode("Trip", screenshot),
        )
        val updated = FolderCoverMap.withCover(entries, "Trip|2026", clip)
        assertEquals(mapOf("Trip|2026" to clip, "Trip" to screenshot), FolderCoverMap.parse(updated))
    }

    @Test
    fun `a separator-bearing folder is pruned by its own uri`() {
        val entries = setOf(FolderCoverMap.encode("Trip|2026", camera))
        assertEquals(emptySet<String>(), FolderCoverMap.dropUris(entries, setOf(camera)))
    }

    // ── replacing a cover ────────────────────────────────────────────────────────────────────────

    @Test
    fun `pinning a second photo replaces the first rather than adding to it`() {
        val entries = FolderCoverMap.withCover(emptySet(), "Camera", camera)
        val updated = FolderCoverMap.withCover(entries, "Camera", screenshot)
        assertEquals(1, updated.size)
        assertEquals(mapOf("Camera" to screenshot), FolderCoverMap.parse(updated))
    }

    @Test
    fun `pinning leaves other folders alone`() {
        val entries = setOf(FolderCoverMap.encode("Screenshots", screenshot))
        val updated = FolderCoverMap.withCover(entries, "Camera", camera)
        assertEquals(mapOf("Screenshots" to screenshot, "Camera" to camera), FolderCoverMap.parse(updated))
    }

    @Test
    fun `an empty folder name or uri pins nothing`() {
        val entries = setOf(FolderCoverMap.encode("Camera", camera))
        assertEquals(entries, FolderCoverMap.withCover(entries, "", camera))
        assertEquals(entries, FolderCoverMap.withCover(entries, "Camera", ""))
    }

    // ── pruning ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only the entries naming a dropped uri go`() {
        val entries = setOf(
            FolderCoverMap.encode("Camera", camera),
            FolderCoverMap.encode("Screenshots", screenshot),
            FolderCoverMap.encode("Clips", clip),
        )
        val kept = FolderCoverMap.dropUris(entries, setOf(screenshot))
        assertEquals(mapOf("Camera" to camera, "Clips" to clip), FolderCoverMap.parse(kept))
    }

    @Test
    fun `a prune that changes nothing answers null`() {
        // The media scan runs constantly, so the caller has to be able to skip the write entirely
        // rather than persist an identical set every pass.
        val entries = setOf(FolderCoverMap.encode("Camera", camera))
        assertNull(FolderCoverMap.dropUris(entries, setOf(screenshot)))
        assertNull(FolderCoverMap.dropUris(entries, emptySet()))
        assertNull(FolderCoverMap.dropUris(emptySet(), setOf(camera)))
    }
}
