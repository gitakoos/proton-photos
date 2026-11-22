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
import eu.akoos.photos.data.db.entity.GpsCheck
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.AlbumService
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.util.DeviceHealthPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Before
import org.junit.Test

/**
 * The cloud GPS walk around its XAttr leg: hidden photos are passed over in a state of their own and read
 * once shown again, the user opening the map frees a walk parked on the health gate, and the file leg
 * follows the XAttr leg.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudGpsBackfillWalkTest {

    private val userId = UserId("u")
    private lateinit var listing: PhotoListingDao
    private lateinit var helpers: LinkDetailHelpers
    private lateinit var health: DeviceHealthPolicy
    private lateinit var content: CloudContentGpsBackfill
    private lateinit var resolver: PhotoLocationResolver
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    private fun row(linkId: String) = PhotoListingEntity(
        linkId = linkId, shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r-$linkId",
    )

    @Before
    fun setUp() {
        listing = mockk(relaxed = true)
        helpers = mockk()
        health = mockk(relaxed = true)
        content = mockk(relaxed = true)
        resolver = mockk()
        coEvery { resolver.placeOf(any(), any(), any()) } returns PhotoLocationResolver.XAttrPlace.NoLocation
        coEvery { listing.gpsSkippedHiddenLinkIds("u") } returns emptyList()
        coEvery { helpers.fetchRevisionXAttrOrThrow(any(), any(), any(), any(), any()) } returns null
    }

    private fun scheduler(): CloudGpsBackfillScheduler {
        val shares = mockk<PhotosShareService> { coEvery { getVolumeId(userId) } returns "v" }
        val albums = mockk<AlbumService> { every { observeHiddenAlbumMemberLinkIds() } returns hidden }
        return CloudGpsBackfillScheduler(
            listing, mockk<PhotoLocationDao>(relaxed = true), helpers, resolver, albums, shares, health, content,
        )
    }

    @Test
    fun `a hidden photo is passed over in its own state, not marked as read`() = runTest {
        hidden.value = setOf("h1")
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(listOf(row("h1"), row("p1")), emptyList())

        scheduler().backfillAll(userId)

        coVerify { listing.setGpsCheck(listOf("h1"), GpsCheck.SKIPPED_HIDDEN) }
        coVerify { listing.markGpsChecked(listOf("p1")) }
    }

    @Test
    fun `a photo shown again is set back to unchecked while one still hidden is not`() = runTest {
        hidden.value = setOf("still")
        coEvery { listing.gpsSkippedHiddenLinkIds("u") } returns listOf("shown", "still")
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()

        scheduler().backfillAll(userId)

        coVerify { listing.setGpsCheck(listOf("shown"), GpsCheck.UNCHECKED) }
    }

    @Test
    fun `a photo whose key does not decrypt is kept for the next walk, not marked as read`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(listOf(row("k1"), row("p1")), emptyList())
        coEvery { resolver.placeOf(userId, match { it.linkId == "k1" }, any()) } returns PhotoLocationResolver.XAttrPlace.KeyMissing

        scheduler().backfillAll(userId)

        coVerify { listing.setGpsCheck(listOf("k1"), GpsCheck.KEY_PENDING) }
        coVerify { listing.markGpsChecked(listOf("p1")) }
    }

    @Test
    fun `a photo whose decrypt throws is kept for the next walk`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returnsMany listOf(listOf(row("k1")), emptyList())
        coEvery { resolver.placeOf(any(), any(), any()) } throws IllegalStateException("bad key")

        scheduler().backfillAll(userId)

        coVerify { listing.setGpsCheck(listOf("k1"), GpsCheck.KEY_PENDING) }
        coVerify(exactly = 0) { listing.markGpsChecked(any()) }
    }

    @Test
    fun `the first walk after the app starts gives the photos with a pending key one more try`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()

        scheduler().backfillAll(userId)

        coVerify { listing.releaseGpsKeyPending("u") }
    }

    @Test
    fun `later walks of the same app start leave the photos with a pending key alone`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()
        val walk = scheduler()

        walk.backfillAll(userId)
        walk.backfillAll(userId)

        coVerify(exactly = 1) { listing.releaseGpsKeyPending("u") }
    }

    @Test
    fun `opening the map frees a walk waiting on the health gate`() = runTest {
        coEvery { health.awaitBackgroundWorkAllowed() } coAnswers { awaitCancellation() }
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()
        val walk = scheduler()

        launch { walk.backfillAll(userId) }
        runCurrent()
        coVerify(exactly = 0) { listing.getUngeocoded(any(), any(), any()) }

        walk.backfillAll(userId, respectHealthGate = false)
        runCurrent()

        coVerify(exactly = 1) { listing.getUngeocoded("u", "v", any()) }
    }

    @Test
    fun `the file leg runs once the XAttr leg is done`() = runTest {
        hidden.value = setOf("h1")
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()

        scheduler().backfillAll(userId)

        coVerify { content.backfill(userId, "v", setOf("h1"), any(), any()) }
    }

    @Test
    fun `a background walk asks the file leg to keep its spacing`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()

        scheduler().backfillAll(userId)

        coVerify { content.backfill(userId, "v", any(), userAsked = false, awaitGate = any()) }
    }

    @Test
    fun `the map's walk asks the file leg to run now`() = runTest {
        coEvery { listing.getUngeocoded("u", "v", any()) } returns emptyList()

        scheduler().backfillAll(userId, respectHealthGate = false)

        coVerify { content.backfill(userId, "v", any(), userAsked = true, awaitGate = any()) }
    }
}
