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

package eu.akoos.photos.data.repository

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact-duplicate finder's size pre-filter: only local files that share a byte size with
 * another are ever content-hashed, so a large library is not read end to end.
 */
class LocalContentHashFillerTest {

    private fun local(uri: String, size: Long, modified: Long = 100L) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = 1_000L,
            displayName = uri,
            mimeType = "image/jpeg",
            sizeBytes = size,
            bucketName = null,
            dateModified = modified,
        ),
    )

    @Test
    fun `files with unique sizes are not candidates`() {
        val out = sameSizeLocalCandidates(listOf(local("a", 10), local("b", 20), local("c", 30)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `two files sharing a size are both candidates`() {
        val out = sameSizeLocalCandidates(listOf(local("a", 100), local("b", 100)))
        assertEquals(setOf("a", "b"), out.map { it.uri }.toSet())
    }

    @Test
    fun `only the size-colliding files are candidates`() {
        val out = sameSizeLocalCandidates(
            listOf(local("a", 100), local("b", 100), local("c", 999), local("d", 100)),
        )
        assertEquals(setOf("a", "b", "d"), out.map { it.uri }.toSet())
    }

    @Test
    fun `freshness key is dateModified underscore size`() {
        val out = sameSizeLocalCandidates(
            listOf(local("a", 100, modified = 42), local("b", 100, modified = 7)),
        )
        assertEquals("42_100", out.first { it.uri == "a" }.freshness)
        assertEquals("7_100", out.first { it.uri == "b" }.freshness)
    }

    @Test
    fun `files with non-positive size are skipped`() {
        val out = sameSizeLocalCandidates(
            listOf(local("a", 0), local("b", 0), local("c", 100), local("d", 100)),
        )
        assertEquals(setOf("c", "d"), out.map { it.uri }.toSet())
    }
}
