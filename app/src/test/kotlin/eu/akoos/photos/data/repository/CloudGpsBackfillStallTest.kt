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

import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.AlbumService
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.util.DeviceHealthPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A photo whose revision can never be read must not hold up the map: it is set aside as checked, so the walk
 * reaches the photos behind it, while a passing outage and a session problem still leave rows for a later run.
 */
class CloudGpsBackfillStallTest {

    private val userId = UserId("u")
    private lateinit var listing: PhotoListingDao
    private lateinit var helpers: LinkDetailHelpers
    private lateinit var scheduler: CloudGpsBackfillScheduler
    private val marked = mutableListOf<List<String>>()

    private fun row(linkId: String) = PhotoListingEntity(
        linkId = linkId, shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r-$linkId",
    )

    private fun http(code: Int, protonCode: Int? = null) = ApiException(
        ApiResult.Error.Http(code, "refused", protonCode?.let { ApiResult.Error.ProtonData(code = it, error = "refused") }),
    )

    @Before
    fun setUp() {
        listing = mockk(relaxed = true)
        helpers = mockk()
        val shares = mockk<PhotosShareService> { coEvery { getVolumeId(userId) } returns "v" }
        val albums = mockk<AlbumService> { every { observeHiddenAlbumMemberLinkIds() } returns flowOf(emptySet()) }
        val health = mockk<DeviceHealthPolicy>(relaxed = true)
        val captured = slot<List<String>>()
        coEvery { listing.markGpsChecked(capture(captured)) } answers { marked += captured.captured.toList() }
        scheduler = CloudGpsBackfillScheduler(listing, mockk<PhotoLocationDao>(relaxed = true), helpers, mockk(relaxed = true), albums, shares, health)
    }

    @Test
    fun `a full page of photos whose revision is gone is set aside and the walk goes on to the next page`() = runTest {
        val gone = (1..200).map { row("gone$it") }
        val next = listOf(row("next1"))
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(gone, next, emptyList())
        coEvery { helpers.fetchRevisionXAttrOrThrow(userId, "v", "s", match { it.startsWith("gone") }, any()) } throws http(422, 2501)
        coEvery { helpers.fetchRevisionXAttrOrThrow(userId, "v", "s", "next1", any()) } returns null

        scheduler.backfillAll(userId)

        assertEquals(gone.map { it.linkId }.toSet(), marked.first().toSet())
        assertTrue(marked.flatten().contains("next1"))
    }

    @Test
    fun `a passing outage leaves the page for a later run`() = runTest {
        val rows = (1..3).map { row("p$it") }
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(rows, emptyList())
        coEvery { helpers.fetchRevisionXAttrOrThrow(any(), any(), any(), any(), any()) } throws http(503)

        scheduler.backfillAll(userId)

        assertTrue(marked.flatten().isEmpty())
    }

    @Test
    fun `a session refusal is not mistaken for photos that are gone`() = runTest {
        val rows = (1..3).map { row("p$it") }
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(rows, emptyList())
        coEvery { helpers.fetchRevisionXAttrOrThrow(any(), any(), any(), any(), any()) } throws http(401)

        scheduler.backfillAll(userId)

        assertTrue(marked.flatten().isEmpty())
        coVerify(exactly = 1) { listing.getUngeocoded("u", "v", any()) }
    }

    @Test
    fun `only a missing or invalid revision counts as unreadable for good`() {
        assertTrue(isRevisionGoneForGood(http(404)))
        assertTrue(isRevisionGoneForGood(http(422, 2501)))
        assertTrue(isRevisionGoneForGood(http(400, 2061)))
        assertFalse(isRevisionGoneForGood(http(401)))
        assertFalse(isRevisionGoneForGood(http(403)))
        assertFalse(isRevisionGoneForGood(http(429)))
        assertFalse(isRevisionGoneForGood(http(503)))
        assertFalse(isRevisionGoneForGood(java.io.IOException("reset")))
    }
}
