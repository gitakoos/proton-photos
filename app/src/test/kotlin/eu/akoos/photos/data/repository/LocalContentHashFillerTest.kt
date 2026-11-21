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
import eu.akoos.photos.domain.repository.LocalMediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun repo(unreadable: Set<String> = emptySet()): LocalMediaRepository = mockk {
        coEvery { sha1(any()) } answers { firstArg<String>().takeUnless { it in unreadable }?.let { "sha-$it" } }
    }

    @Test
    fun `a pass publishes the hash of every same-size file`() = runBlocking {
        val filler = LocalContentHashFiller(repo())
        val items = (1..200).map { local("u$it", 100) }

        filler.request(items)
        val hashes = withTimeout(10_000) { filler.hashes.first { it.size == 200 } }

        assertEquals("sha-u1", hashes["u1"])
        assertEquals("sha-u200", hashes["u200"])
    }

    @Test
    fun `a file that cannot be read is left out and the rest still land`() = runBlocking {
        val filler = LocalContentHashFiller(repo(unreadable = setOf("b")))

        filler.request(listOf(local("a", 100), local("b", 100), local("c", 100)))
        val hashes = withTimeout(10_000) { filler.hashes.first { it.size == 2 } }

        assertEquals(mapOf("a" to "sha-a", "c" to "sha-c"), hashes)
    }

    @Test
    fun `an unchanged file is not read again on the next pass`() = runBlocking {
        val media = repo()
        val filler = LocalContentHashFiller(media)
        val items = listOf(local("a", 100), local("b", 100))
        filler.request(items)
        withTimeout(10_000) { filler.hashes.first { it.size == 2 } }

        filler.request(items + local("c", 100))
        withTimeout(10_000) { filler.hashes.first { it.size == 3 } }

        coVerify(exactly = 1) { media.sha1("a") }
        coVerify(exactly = 1) { media.sha1("c") }
    }
}
