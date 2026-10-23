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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.label.PhotoLabelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A listing with no row for any link, so the replaced photo the caller holds is what the carry-over reads. */
internal fun noListingRow(): PhotoListingDao = mockk(relaxed = true) {
    coEvery { getByLinkId(any()) } returns null
}

/** What a date or place edit hands from the replaced photo to its corrected copy. */
class CarryOverToReplacementTest {

    private val userId = UserId("u")
    private val cloud = FakeDrivePhotoRepository()
    private val listing: PhotoListingDao = mockk(relaxed = true)
    private val labels: PhotoLabelRepository = mockk(relaxed = true)
    private val faces: RekeyPhotoFacesUseCase = mockk(relaxed = true)
    private val embeddings: ImageEmbeddingDao = mockk(relaxed = true)
    private val carryOver = CarryOverToReplacement(cloud, listing, labels, faces, embeddings)

    private fun listed(linkId: String, tagsCsv: String) = PhotoListingEntity(
        linkId = linkId, shareId = "s", volumeId = "v", userId = "u", captureTime = 1L, displayName = "a.jpg",
        mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r", tagsCsv = tagsCsv,
    )

    @Test
    fun `a favourite read from the listing is set on the copy, with everything else moved`() = runTest {
        coEvery { listing.getByLinkId("old") } returns listed("old", "0,4")

        carryOver(userId, "old", "new")

        assertEquals(listOf("new" to true), cloud.favoriteCalls)
        coVerify { labels.rekeyFor(userId, "old", "new") }
        coVerify { faces.invoke("u", "old", "new", any()) }
        coVerify { embeddings.rekeyPhoto("u", "old", "new") }
    }

    @Test
    fun `a heart set after the edit was queued is read off the row, not the queued snapshot`() = runTest {
        // The queued edit holds the photo as it was before the heart; the row has it.
        coEvery { listing.getByLinkId("old") } returns listed("old", "0")

        carryOver(userId, "old", "new", original = listed("old", "").toDomain())

        assertEquals(listOf("new" to true), cloud.favoriteCalls)
    }

    @Test
    fun `the snapshot decides only when the row is already gone`() = runTest {
        coEvery { listing.getByLinkId("old") } returns null

        carryOver(userId, "old", "new", original = listed("old", "0").toDomain())

        assertEquals(listOf("new" to true), cloud.favoriteCalls)
    }

    @Test
    fun `one part failing does not stop the others`() = runTest {
        coEvery { listing.getByLinkId("old") } returns listed("old", "")
        coEvery { labels.rekeyFor(any(), any(), any()) } throws IllegalStateException("locked")

        carryOver(userId, "old", "new")

        assertTrue(cloud.favoriteCalls.isEmpty())
        coVerify { faces.invoke("u", "old", "new", any()) }
        coVerify { embeddings.rekeyPhoto("u", "old", "new") }
    }

    @Test
    fun `a cancellation is not swallowed`() = runTest {
        coEvery { listing.getByLinkId("old") } returns listed("old", "")
        coEvery { labels.rekeyFor(any(), any(), any()) } throws CancellationException("stop")

        try {
            carryOver(userId, "old", "new")
            fail("the cancellation must reach the caller")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
