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

import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.db.entity.GpsCheck
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.ContentEdgeReader
import eu.akoos.photos.data.repository.drive.ContentEdges
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.util.NetworkObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The file leg of the cloud GPS walk: a video's place comes from its own movie box, an image whose uploader
 * read its EXIF is not downloaded, a passing failure leaves the photo for a later pass, and hidden, synced
 * or mobile-data cases download nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudContentGpsBackfillTest {

    private val userId = UserId("u")
    private lateinit var listing: PhotoListingDao
    private lateinit var locations: PhotoLocationDao
    private lateinit var resolver: PhotoLocationResolver
    private lateinit var downloads: PhotoDownloadService
    private lateinit var edges: ContentEdgeReader
    private lateinit var sync: SyncStateSnapshot
    private val unmetered = MutableStateFlow(true)
    private val stored = mutableListOf<PhotoLocationEntity>()
    private val settled = mutableListOf<String>()
    private val skipped = mutableListOf<String>()

    private fun row(linkId: String, mime: String) = PhotoListingEntity(
        linkId = linkId, shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = linkId, mimeType = mime, sizeBytes = 1L, revisionId = "r-$linkId", encXAttr = "armored",
        gpsChecked = GpsCheck.XATTR_READ,
    )

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(8 + payload.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload) }
        return out.toByteArray()
    }

    private fun videoWithPlace(): ByteArray {
        val iso = "+38.9067+001.4206/".toByteArray(Charsets.US_ASCII)
        val xyzPayload = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeShort(iso.size); writeShort(0x15C7); write(iso) } }
        return box("ftyp", "isom".toByteArray()) + box("mdat", ByteArray(64)) +
            box("moov", box("mvhd", ByteArray(100)), box("udta", box("©xyz", xyzPayload.toByteArray())))
    }

    @Before
    fun setUp() {
        listing = mockk(relaxed = true)
        locations = mockk(relaxed = true)
        resolver = mockk()
        downloads = mockk()
        edges = mockk()
        sync = mockk()
        every { downloads.isDownloading(any()) } returns false
        coEvery { sync.current(userId) } returns SyncStateSnapshot.Rows(emptyList())
        val fixes = slot<List<PhotoLocationEntity>>()
        coEvery { locations.upsert(capture(fixes)) } answers { stored += fixes.captured }
        val ids = slot<List<String>>()
        coEvery { listing.setGpsCheck(capture(ids), GpsCheck.CONTENT_READ) } answers { settled += ids.captured }
        val skippedIds = slot<List<String>>()
        coEvery { listing.setGpsCheck(capture(skippedIds), GpsCheck.CONTENT_SKIPPED) } answers { skipped += skippedIds.captured }
    }

    private fun backfill(): CloudContentGpsBackfill {
        val network = mockk<NetworkObserver> { every { isUnmetered } returns unmetered }
        return CloudContentGpsBackfill(
            listing, locations, mockk<LinkDetailHelpers>(), resolver, downloads, edges, sync, network,
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun page(vararg rows: PhotoListingEntity) {
        coEvery { listing.getXAttrReadWithoutLocation("u", "v", "", any()) } returns rows.toList()
    }

    private val cameraXAttr = """{"Common":{},"Camera":{"Orientation":1}}"""

    private fun rows(prefix: String, mime: String, count: Int = 50) = (0 until count).map { row("$prefix%02d".format(it), mime) }

    private fun pages(vararg pages: List<PhotoListingEntity>) {
        var after = ""
        pages.forEach { rows ->
            coEvery { listing.getXAttrReadWithoutLocation("u", "v", after, any()) } returns rows
            after = rows.last().linkId
        }
        coEvery { listing.getXAttrReadWithoutLocation("u", "v", after, any()) } returns emptyList()
    }

    private suspend fun run(hidden: Set<String> = emptySet()) =
        backfill().backfill(userId, "v", hidden, userAsked = false, awaitGate = {})

    private fun failingVideo(error: Exception) {
        page(row("v1", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } throws error
    }

    @Test
    fun `a failed read is counted against the photo`() = runTest {
        failingVideo(IllegalStateException("bad key"))

        run()

        coVerify { listing.setGpsCheck(listOf("v1"), GpsCheck.CONTENT_FAILED_ONCE) }
    }

    @Test
    fun `a network failure on a page where nothing else was read is not counted`() = runTest {
        failingVideo(java.io.IOException("reset"))

        run()

        coVerify(exactly = 0) { listing.setGpsCheck(any(), GpsCheck.CONTENT_FAILED_ONCE) }
    }

    @Test
    fun `a network failure beside a file that was read is counted`() = runTest {
        page(row("v1", "video/mp4"), row("v2", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(ByteArray(16), null, 1)
        coEvery { edges.readContentEdges(userId, match { it.linkId == "v1" }, true, 1) } throws java.io.IOException("reset")

        run()

        coVerify { listing.setGpsCheck(listOf("v1"), GpsCheck.CONTENT_FAILED_ONCE) }
    }

    @Test
    fun `the spacing holds for a pass started in a new process`() = runTest {
        failingVideo(java.io.IOException("reset"))
        val first = backfill()
        val second = backfill()
        first.now = { 0L }
        second.now = { 10 * 60 * 1000L }

        first.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})
        second.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})

        coVerify(exactly = 1) { edges.readContentEdges(any(), any(), any(), any()) }
    }

    @Test
    fun `a photo is given up after its third failed read`() {
        assertEquals(GpsCheck.CONTENT_FAILED_ONCE, stateAfterFailure(GpsCheck.XATTR_READ))
        assertEquals(GpsCheck.CONTENT_FAILED_TWICE, stateAfterFailure(GpsCheck.CONTENT_FAILED_ONCE))
        assertEquals(GpsCheck.CONTENT_GAVE_UP, stateAfterFailure(GpsCheck.CONTENT_FAILED_TWICE))
    }

    @Test
    fun `network failures during an outage are not held against the photos`() = runTest {
        pages(rows("a", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } throws java.io.IOException("reset")

        run()

        coVerify(exactly = 0) { listing.setGpsCheck(any(), GpsCheck.CONTENT_FAILED_ONCE) }
    }

    @Test
    fun `a background pass within half an hour of the last one reads nothing`() = runTest {
        failingVideo(java.io.IOException("reset"))
        val walk = backfill()
        var clock = 0L
        walk.now = { clock }

        walk.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})
        clock = 29 * 60 * 1000L
        walk.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})

        coVerify(exactly = 1) { edges.readContentEdges(any(), any(), any(), any()) }
    }

    @Test
    fun `the map's own run is not held back by a recent pass`() = runTest {
        failingVideo(java.io.IOException("reset"))
        val walk = backfill()
        var clock = 0L
        walk.now = { clock }

        walk.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})
        clock = 60_000L
        walk.backfill(userId, "v", emptySet(), userAsked = true, awaitGate = {})

        coVerify(exactly = 2) { edges.readContentEdges(any(), any(), any(), any()) }
    }

    @Test
    fun `a background pass half an hour after the last one reads again`() = runTest {
        failingVideo(java.io.IOException("reset"))
        val walk = backfill()
        var clock = 0L
        walk.now = { clock }

        walk.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})
        clock = 30 * 60 * 1000L
        walk.backfill(userId, "v", emptySet(), userAsked = false, awaitGate = {})

        coVerify(exactly = 2) { edges.readContentEdges(any(), any(), any(), any()) }
    }

    @Test
    fun `a video's place is read from its movie box and the photo is not read again`() = runTest {
        page(row("v1", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(videoWithPlace(), null, 1)

        run()

        assertEquals(38.9067, stored.single().latitude, 1e-4)
        assertEquals(listOf("v1"), settled)
    }

    @Test
    fun `an image whose uploader read its EXIF is set aside as skipped without a download`() = runTest {
        page(row("i1", "image/jpeg"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns cameraXAttr

        run()

        coVerify(exactly = 0) { edges.readContentEdges(any(), any(), any(), any()) }
        assertEquals(listOf("i1"), skipped)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `a place the XAttr does hold is stored without a download`() = runTest {
        page(row("p1", "image/jpeg"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Location":{"Latitude":38.9,"Longitude":1.4}}"""

        run()

        coVerify(exactly = 0) { edges.readContentEdges(any(), any(), any(), any()) }
        assertEquals(listOf("p1"), stored.map { it.id })
    }

    @Test
    fun `a passing failure leaves the photo for a later pass`() = runTest {
        page(row("v1", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } throws java.io.IOException("reset")

        run()

        assertTrue(settled.isEmpty())
    }

    @Test
    fun `failures piling up end the pass before the next page`() = runTest {
        val first = rows("a", "video/mp4")
        pages(first, rows("b", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } throws java.io.IOException("reset")

        run()

        coVerify(exactly = 0) { listing.getXAttrReadWithoutLocation("u", "v", first.last().linkId, any()) }
    }

    @Test
    fun `the next pass resumes after rows that keep failing`() = runTest {
        val first = rows("a", "video/mp4")
        pages(first, listOf(row("b00", "video/mp4")))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(ByteArray(16), null, 1)
        coEvery { edges.readContentEdges(userId, match { it.linkId.startsWith("a") }, true, 1) } throws
            java.io.IOException("reset")
        val walk = backfill()

        walk.backfill(userId, "v", emptySet(), userAsked = true, awaitGate = {})
        walk.backfill(userId, "v", emptySet(), userAsked = true, awaitGate = {})

        assertEquals(listOf("b00"), settled)
    }

    @Test
    fun `a few failures among good reads do not end the pass`() = runTest {
        val first = rows("a", "video/mp4")
        pages(first, listOf(row("b00", "video/mp4")))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(ByteArray(16), null, 1)
        coEvery { edges.readContentEdges(userId, match { it.linkId in setOf("a00", "a01", "a02") }, true, 1) } throws
            java.io.IOException("reset")

        run()

        assertTrue("b00" in settled)
        assertEquals(48, settled.size)
    }

    @Test
    fun `a row not read from its file does not use up the pass budget`() = runTest {
        pages(rows("a", "image/jpeg"), rows("b", "image/jpeg"), rows("c", "image/jpeg"), listOf(row("d00", "video/mp4")))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns cameraXAttr
        coEvery { resolver.decryptXAttr(userId, match { it.linkId == "d00" }, "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(videoWithPlace(), null, 1)

        run()

        assertEquals(listOf("d00"), stored.map { it.id })
    }

    @Test
    fun `a pass reads at most one hundred files and leaves the rest unsettled`() = runTest {
        val second = rows("b", "video/mp4", count = 50)
        pages(rows("a", "video/mp4", count = 50), second, rows("c", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns ContentEdges(ByteArray(16), null, 1)

        run()

        coVerify(exactly = 100) { edges.readContentEdges(userId, any(), true, 1) }
        coVerify(exactly = 0) { listing.getXAttrReadWithoutLocation("u", "v", second.last().linkId, any()) }
        assertEquals(100, settled.size)
    }

    @Test
    fun `a movie box split across the last two blocks is read from both`() = runTest {
        page(row("v1", "video/mp4"))
        coEvery { resolver.decryptXAttr(userId, any(), "armored") } returns """{"Common":{}}"""
        val file = videoWithPlace()
        coEvery { edges.readContentEdges(userId, any(), true, 1) } returns
            ContentEdges(file.copyOfRange(0, 40), file.copyOfRange(file.size - 20, file.size), 3)
        coEvery { edges.readContentEdges(userId, any(), false, 2) } returns
            ContentEdges(null, file.copyOfRange(40, file.size), 3)

        run()

        assertEquals(38.9067, stored.single().latitude, 1e-4)
    }

    @Test
    fun `hidden and synced photos are passed over`() = runTest {
        page(row("hidden", "video/mp4"), row("synced", "video/mp4"))
        coEvery { sync.current(userId) } returns SyncStateSnapshot.Rows(
            listOf(SyncStateLite("content://media/1", "synced", "h", SyncStatus.SYNCED, queued = false)),
        )

        run(hidden = setOf("hidden"))

        coVerify(exactly = 0) { edges.readContentEdges(any(), any(), any(), any()) }
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `nothing is downloaded on mobile data`() = runTest {
        unmetered.value = false
        page(row("v1", "video/mp4"))

        run()

        coVerify(exactly = 0) { listing.getXAttrReadWithoutLocation(any(), any(), any(), any()) }
    }
}
