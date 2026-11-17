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

import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.db.entity.LinkIdName
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background repair that links a device file to its same-named Drive photo after a reinstall, on
 * proof of equal bytes only.
 */
class DisplayPairRepairTest {

    private val user = UserId("user1")

    private class FakeMisses : PairRepairMisses {
        val recorded = HashMap<String, String>()
        override fun fingerprintOf(uri: String): String? = recorded[uri]
        override fun record(uri: String, fingerprint: String) { recorded[uri] = fingerprint }
        override fun keepOnly(uris: Set<String>) { recorded.keys.retainAll(uris) }
    }

    private fun device(uri: String, name: String, size: Long = 4_096L, dateTaken: Long = 1_783_507_135_000L) =
        LocalMediaItem(uri = uri, dateTaken = dateTaken, displayName = name, mimeType = "image/jpeg", sizeBytes = size, bucketName = "Download")

    private fun drive(linkId: String, name: String, contentHash: String?) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_783_507_135L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 0L,
        thumbnailUrl = null,
        revisionId = "rev1",
        contentHash = contentHash,
    )

    /** A harness over [deviceFiles], [drivePhotos], the device bytes' SHA-1 per uri, and [rows]. */
    private inner class Harness(
        deviceFiles: List<LocalMediaItem>,
        val drivePhotos: List<CloudPhoto>,
        sha1ByUri: Map<String, String>,
        rows: List<SyncStateLite> = emptyList(),
    ) {
        val misses = FakeMisses()
        val written = ArrayList<SyncState>()
        val hashed = ArrayList<String>()
        /** (uri, linkId) of each name link marked proven. */
        val proven = ArrayList<Pair<String, String>>()
        val localRepo = mockk<LocalMediaRepository>()
        val cloudRepo = mockk<DrivePhotoRepository>()
        val syncRepo = mockk<SyncStateRepository>(relaxed = true)
        val snapshot = mockk<SyncStateSnapshot>()
        val listing = mockk<PhotoListingDao>()

        init {
            coEvery { localRepo.lastKnownMedia() } returns deviceFiles
            coEvery { localRepo.sha1(any()) } answers {
                val uri = firstArg<String>()
                hashed += uri
                sha1ByUri[uri]
            }
            every { cloudRepo.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }
            every { cloudRepo.observePhotosByLinkIds(any()) } answers {
                val ids = firstArg<List<String>>().toSet()
                flowOf(drivePhotos.filter { it.linkId in ids })
            }
            coEvery { snapshot.current(user) } returns SyncStateSnapshot.Rows(rows)
            coEvery { listing.ownStreamByLowerNames("user1", any()) } answers {
                val names = secondArg<List<String>>().toSet()
                drivePhotos.filter { it.displayName.lowercase() in names }.map { LinkIdName(it.linkId, it.displayName) }
            }
            coEvery { syncRepo.insertIfAbsent(any(), user) } answers {
                written += firstArg<SyncState>()
                true
            }
            coEvery { syncRepo.markLinkProven(user, any(), any(), any(), any(), any()) } answers {
                proven += secondArg<String>() to thirdArg<String>()
                true
            }
        }

        val repair = DisplayPairRepair(null, null, localRepo, cloudRepo, syncRepo, snapshot, listing, misses)

        fun run(): Int = runBlocking { repair.run(user) }
    }

    private fun merger(): GetGalleryItemsUseCase {
        val cloudRepo = mockk<DrivePhotoRepository>()
        every { cloudRepo.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }
        return GetGalleryItemsUseCase(
            mockk<LocalMediaRepository>(),
            cloudRepo,
            mockk<SyncStateSnapshot>(),
            CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
        )
    }

    @Test
    fun `after a reinstall a download holding the Drive bytes pairs once the repair ran`() {
        val file = device("uri://dl", "IMG_0001.jpg")
        val photo = drive("link-1", "IMG_0001.jpg", contentHash = "hmac-sha-same")
        val h = Harness(listOf(file), listOf(photo), mapOf("uri://dl" to "sha-same"))

        // Before the repair: no row, so the gallery shows the file and the Drive photo apart.
        assertEquals(2, merger().merge(listOf(file), listOf(photo), emptyList<SyncState>()).size)

        assertEquals(1, h.run())

        val row = h.written.single()
        assertEquals("uri://dl", row.localUri)
        assertEquals("link-1", row.cloudFileId)
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(LinkBasis.PROVEN, row.linkBasis)
        assertEquals("hmac-sha-same", row.cloudHash)
        coVerify { h.syncRepo.setOriginalHash("uri://dl", "sha-same") }
        val merged = merger().merge(listOf(file), listOf(photo), h.written)
        assertEquals("link-1", merged.filterIsInstance<GalleryItem.Synced>().single().cloud.linkId)
        assertEquals(1, merged.size)
    }

    @Test
    fun `a same-named file with other bytes stays two items and is not read again`() {
        val file = device("uri://other", "IMG_0001.jpg")
        val photo = drive("link-1", "IMG_0001.jpg", contentHash = "hmac-sha-drive")
        val h = Harness(listOf(file), listOf(photo), mapOf("uri://other" to "sha-different"))

        assertEquals(0, h.run())
        assertTrue(h.written.isEmpty())
        assertEquals(2, merger().merge(listOf(file), listOf(photo), h.written).size)
        assertEquals(listOf("uri://other"), h.hashed)

        assertEquals(0, h.run())
        assertEquals("the miss is remembered, so the file is read once", listOf("uri://other"), h.hashed)
    }

    @Test
    fun `a miss is weighed again once the file changes`() {
        val photo = drive("link-1", "IMG_0001.jpg", contentHash = "hmac-sha-drive")
        val h = Harness(listOf(device("uri://f", "IMG_0001.jpg")), listOf(photo), mapOf("uri://f" to "sha-x"))
        h.run()
        val changed = Harness(listOf(device("uri://f", "IMG_0001.jpg", size = 8_192L)), listOf(photo), mapOf("uri://f" to "sha-x"))
        changed.misses.recorded.putAll(h.misses.recorded)

        changed.run()

        assertEquals(listOf("uri://f"), changed.hashed)
    }

    @Test
    fun `a download whose bytes the app stamped on saving stays two items`() {
        // Saving a download writes the place and the date into the file, so its SHA-1 is no longer Drive's.
        val file = device("uri://stamped", "IMG_0002.jpg")
        val photo = drive("link-2", "IMG_0002.jpg", contentHash = "hmac-sha-as-uploaded")
        val h = Harness(listOf(file), listOf(photo), mapOf("uri://stamped" to "sha-with-gps-stamped"))

        assertEquals(0, h.run())

        assertTrue(h.written.isEmpty())
        val merged = merger().merge(listOf(file), listOf(photo), h.written)
        assertTrue(merged.none { it is GalleryItem.Synced })
    }

    @Test
    fun `a name two device files carry is never weighed`() {
        val photo = drive("link-1", "IMG_0001.jpg", contentHash = "hmac-sha-same")
        val h = Harness(
            listOf(device("uri://a", "IMG_0001.jpg"), device("uri://b", "img_0001.JPG")),
            listOf(photo),
            mapOf("uri://a" to "sha-same", "uri://b" to "sha-same"),
        )

        assertEquals(0, h.run())
        assertTrue(h.hashed.isEmpty())
    }

    @Test
    fun `a name two Drive photos carry is never weighed`() {
        val h = Harness(
            listOf(device("uri://a", "IMG_0001.jpg")),
            listOf(drive("link-1", "IMG_0001.jpg", "hmac-sha-same"), drive("link-2", "IMG_0001.jpg", "hmac-sha-other")),
            mapOf("uri://a" to "sha-same"),
        )

        assertEquals(0, h.run())
        assertTrue(h.hashed.isEmpty())
    }

    @Test
    fun `a file with a row, or a Drive photo a row claims, is left alone`() {
        val rowed = SyncStateLite(localUri = "uri://rowed", cloudFileId = null, localHash = "", status = SyncStatus.LOCAL_ONLY, queued = false)
        val claim = SyncStateLite(localUri = "uri://elsewhere", cloudFileId = "link-2", localHash = "", status = SyncStatus.SYNCED, queued = false)
        val h = Harness(
            listOf(device("uri://rowed", "IMG_0001.jpg"), device("uri://free", "IMG_0002.jpg")),
            listOf(drive("link-1", "IMG_0001.jpg", "hmac-sha-1"), drive("link-2", "IMG_0002.jpg", "hmac-sha-2")),
            mapOf("uri://rowed" to "sha-1", "uri://free" to "sha-2"),
            rows = listOf(rowed, claim),
        )

        assertEquals(0, h.run())
        assertTrue(h.hashed.isEmpty())
    }

    @Test
    fun `a run reads at most its file budget, newest first`() {
        val files = (0 until PAIR_REPAIR_MAX_FILES + 5).map { i ->
            device("uri://$i", "IMG_$i.jpg", size = 1_000L, dateTaken = 1_000_000L + i)
        }
        val photos = files.map { drive("link-${it.uri}", it.displayName, "hmac-sha-${it.uri}") }
        val h = Harness(files, photos, files.associate { it.uri to "sha-${it.uri}" })

        assertEquals(PAIR_REPAIR_MAX_FILES, h.run())

        assertEquals(PAIR_REPAIR_MAX_FILES, h.hashed.size)
        assertEquals("uri://${PAIR_REPAIR_MAX_FILES + 4}", h.hashed.first())
    }

    @Test
    fun `a run stops at its byte budget after the first file`() {
        val big = device("uri://big", "IMG_big.jpg", size = PAIR_REPAIR_MAX_BYTES, dateTaken = 2_000L)
        val next = device("uri://next", "IMG_next.jpg", size = 10L, dateTaken = 1_000L)
        val h = Harness(
            listOf(big, next),
            listOf(drive("l-big", "IMG_big.jpg", "hmac-sha-big"), drive("l-next", "IMG_next.jpg", "hmac-sha-next")),
            mapOf("uri://big" to "sha-big", "uri://next" to "sha-next"),
        )

        assertEquals(1, h.run())
        assertEquals(listOf("uri://big"), h.hashed)
    }

    @Test
    fun `files whose Drive photo cannot be compared take no place in the budget`() {
        // The newest files are larger than a whole run may read, but their Drive photos carry no content
        // hash or are not listed: they are passed over unread, and the older match is still reached.
        val noHash = device("uri://no-hash", "VID_big.mp4", size = PAIR_REPAIR_MAX_BYTES, dateTaken = 3_000L)
        val unlisted = device("uri://unlisted", "VID_gone.mp4", size = PAIR_REPAIR_MAX_BYTES, dateTaken = 2_000L)
        val match = device("uri://match", "IMG_old.jpg", size = 10L, dateTaken = 1_000L)
        val h = Harness(
            listOf(noHash, unlisted, match),
            listOf(drive("l-no-hash", "VID_big.mp4", contentHash = null), drive("l-match", "IMG_old.jpg", "hmac-sha-old")),
            mapOf("uri://no-hash" to "sha-big", "uri://unlisted" to "sha-gone", "uri://match" to "sha-old"),
        )
        every { h.cloudRepo.observePhotosByLinkIds(any()) } answers {
            val ids = firstArg<List<String>>().toSet()
            flowOf(h.drivePhotos.filter { it.linkId in ids && it.linkId != "l-gone" })
        }
        coEvery { h.listing.ownStreamByLowerNames("user1", any()) } answers {
            val names = secondArg<List<String>>().toSet()
            (h.drivePhotos.map { LinkIdName(it.linkId, it.displayName) } + LinkIdName("l-gone", "VID_gone.mp4"))
                .filter { it.displayName.lowercase() in names }
        }

        assertEquals(1, h.run())

        assertEquals(listOf("uri://match"), h.hashed)
        assertEquals("uri://match", h.written.single().localUri)
    }

    @Test
    fun `with no key to compare hashes the run stops after the first read`() {
        val files = listOf(device("uri://a", "IMG_A.jpg", dateTaken = 2L), device("uri://b", "IMG_B.jpg", dateTaken = 1L))
        val h = Harness(
            files,
            listOf(drive("l-a", "IMG_A.jpg", "hmac-sha-a"), drive("l-b", "IMG_B.jpg", "hmac-sha-b")),
            mapOf("uri://a" to "sha-a", "uri://b" to "sha-b"),
        )
        every { h.cloudRepo.cloudContentHash(any()) } returns null

        assertEquals(0, h.run())

        assertEquals(listOf("uri://a"), h.hashed)
        assertNull("no key is not a miss", h.misses.fingerprintOf("uri://a"))
    }

    @Test
    fun `a row written meanwhile by another path is kept`() {
        val file = device("uri://dl", "IMG_0001.jpg")
        val h = Harness(listOf(file), listOf(drive("link-1", "IMG_0001.jpg", "hmac-sha-same")), mapOf("uri://dl" to "sha-same"))
        coEvery { h.syncRepo.insertIfAbsent(any(), user) } returns false

        assertEquals(0, h.run())
        coVerify(exactly = 0) { h.syncRepo.setOriginalHash(any(), any()) }
    }

    // ── a link reconcile made by name, in a backed-up folder ──────────────────────────

    private fun nameRow(uri: String, linkId: String, basis: Int = LinkBasis.NAME) = SyncStateLite(
        localUri = uri, cloudFileId = linkId, localHash = "", status = SyncStatus.SYNCED, queued = false, linkBasis = basis,
    )

    private fun SyncStateLite.full() = SyncState(
        localUri = localUri, cloudFileId = cloudFileId, localHash = localHash, cloudHash = null, status = status,
        lastSyncAttemptMs = 0L, lastSyncSuccessMs = null, backedUpAtMs = null, sizeBytes = 0L, linkBasis = linkBasis,
    )

    @Test
    fun `after a reinstall a backed-up photo linked by name pairs once its bytes are found to be Drive's`() {
        // Reconcile links the file by name and capture second; Drive's listing reports no size, so the
        // gallery shows the name link as two items until the bytes prove it.
        val file = device("uri://cam", "IMG_0003.jpg")
        val photo = drive("link-3", "IMG_0003.jpg", contentHash = "hmac-sha-same")
        val row = nameRow("uri://cam", "link-3")
        val h = Harness(listOf(file), listOf(photo), mapOf("uri://cam" to "sha-same"), rows = listOf(row))
        assertEquals(2, merger().merge(listOf(file), listOf(photo), listOf(row.full())).size)

        assertEquals(1, h.run())

        assertEquals(listOf("uri://cam" to "link-3"), h.proven)
        coVerify { h.syncRepo.markLinkProven(user, "uri://cam", "link-3", "hmac-sha-same", "sha-same", any()) }
        assertTrue("the row is marked, never written anew", h.written.isEmpty())
        val merged = merger().merge(listOf(file), listOf(photo), listOf(row.full().copy(linkBasis = LinkBasis.PROVEN)))
        assertEquals("link-3", merged.filterIsInstance<GalleryItem.Synced>().single().cloud.linkId)
        assertEquals(1, merged.size)
    }

    @Test
    fun `a name link whose bytes differ stays marked and is not read again`() {
        val h = Harness(
            listOf(device("uri://cam", "IMG_0003.jpg")),
            listOf(drive("link-3", "IMG_0003.jpg", contentHash = "hmac-sha-drive")),
            mapOf("uri://cam" to "sha-other"),
            rows = listOf(nameRow("uri://cam", "link-3")),
        )

        assertEquals(0, h.run())
        assertTrue(h.proven.isEmpty())
        h.run()
        assertEquals(listOf("uri://cam"), h.hashed)
    }

    @Test
    fun `a name link of rewritten bytes or a proven link is never read`() {
        val h = Harness(
            listOf(device("uri://a", "IMG_A.jpg"), device("uri://b", "IMG_B.jpg")),
            listOf(drive("link-a", "IMG_A.jpg", "hmac-sha-a"), drive("link-b", "IMG_B.jpg", "hmac-sha-b")),
            mapOf("uri://a" to "sha-a", "uri://b" to "sha-b"),
            rows = listOf(nameRow("uri://a", "link-a", LinkBasis.NAME_REWRITTEN), nameRow("uri://b", "link-b", LinkBasis.PROVEN)),
        )

        assertEquals(0, h.run())
        assertTrue(h.hashed.isEmpty())
    }

    @Test
    fun `no signed-in account or no complete listing runs nothing`() {
        val h = Harness(listOf(device("uri://dl", "IMG_0001.jpg")), emptyList(), emptyMap())

        assertEquals(0, runBlocking { h.repair.runForPrimaryUser() })
        assertTrue(h.hashed.isEmpty())
        assertNull(h.misses.fingerprintOf("uri://dl"))
    }
}
