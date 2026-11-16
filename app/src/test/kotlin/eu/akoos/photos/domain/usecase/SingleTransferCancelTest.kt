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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.data.transfer.ItemCancels
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.mimeToFileExtension
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Cancelling one photo of a gallery download: the photo stops, running or still queued, its partial
 * and resume files are cleaned, the rest of the batch keeps going, and the Activity row goes at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleTransferCancelTest {

    private lateinit var context: Context
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var useCase: DownloadPhotosUseCase
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cloud = FakeDrivePhotoRepository()
        val linkDetails = mockk<eu.akoos.photos.data.repository.drive.LinkDetailHelpers>()
        coEvery { linkDetails.batchFetchLinkDetails(any(), any(), any()) } returns emptyMap()
        useCase = DownloadPhotosUseCase(
            context = context,
            cloudRepo = cloud,
            syncStateRepo = mockk(relaxed = true),
            linkDetailHelpers = linkDetails,
            locationResolver = mockk { coEvery { resolve(any(), any()) } returns null },
        )
    }

    private fun cloudItem(linkId: String, mime: String = "image/png") = GalleryItem.CloudOnly(
        CloudPhoto(
            linkId = linkId, shareId = "share", volumeId = "vol", captureTime = 1_700_000_000L,
            displayName = "$linkId.${mimeToFileExtension(mime)}", mimeType = mime, sizeBytes = 0L,
            thumbnailUrl = null, revisionId = "rev",
        ),
    )

    @Test
    fun `cancelling one running download stops only it and cleans its partial files`() = runTest {
        val cancels = ItemCancels()
        val states = mutableListOf<Pair<String, Boolean>>()
        cloud.onDownloadStarted = { photo ->
            if (photo.linkId == "b") {
                cancels.cancel("b")
                awaitCancellation()
            }
        }

        val progress = useCase.downloadGalleryItems(
            userId, listOf(cloudItem("a"), cloudItem("b"), cloudItem("c")),
            cancels = cancels,
            onItemState = { key, running -> states += key to running },
        )

        assertEquals(listOf("a", "b", "c"), cloud.downloadedLinkIds.sorted())
        assertEquals(listOf("b"), cloud.discardedDownloads)
        assertEquals(1, progress.cancelled)
        assertEquals(2, progress.total)
        assertEquals("the two others finished", 2, progress.done)
        assertEquals("b" to false, states.last { it.first == "b" })
    }

    @Test
    fun `a queued download cancelled before its turn is never fetched and the rest still run`() = runTest {
        val cancels = ItemCancels()
        cancels.cancel("c")

        val progress = useCase.downloadGalleryItems(
            userId, listOf(cloudItem("a"), cloudItem("b"), cloudItem("c")),
            cancels = cancels,
        )

        assertEquals(listOf("a", "b"), cloud.downloadedLinkIds.sorted())
        assertEquals(1, progress.cancelled)
        assertEquals(2, progress.done)
    }

    @Test
    fun `a download cancelled after its bytes arrived leaves no decrypted cache copy behind`() = runTest {
        val dir = File(context.cacheDir, "fake-fullres").also { it.deleteRecursively(); it.mkdirs() }
        cloud.fullResCacheDir = dir
        val cancels = ItemCancels()
        // The bytes are in and the photo is being prepared for saving when the cancel lands.
        val resolver = mockk<eu.akoos.photos.data.repository.PhotoLocationResolver>()
        coEvery { resolver.resolve(any(), any()) } coAnswers {
            cancels.cancel("a")
            awaitCancellation()
        }
        val linkDetails = mockk<eu.akoos.photos.data.repository.drive.LinkDetailHelpers>()
        coEvery { linkDetails.batchFetchLinkDetails(any(), any(), any()) } returns emptyMap()
        val saving = DownloadPhotosUseCase(context, cloud, mockk(relaxed = true), linkDetails, resolver)

        val progress = saving.downloadGalleryItems(
            userId, listOf(cloudItem("a", "image/jpeg")), cancels = cancels,
        )

        assertEquals(1, progress.cancelled)
        assertTrue("the cache copy is removed on cancel", dir.listFiles().isNullOrEmpty())
        assertEquals(listOf("a"), cloud.discardedDownloads)
    }

    @Test
    fun `a cancel that lands after the photo was saved keeps it as downloaded`() = runTest {
        val cancels = ItemCancels()
        val saved = mutableListOf<String>()

        val progress = useCase.downloadGalleryItems(
            userId, listOf(cloudItem("a", "image/jpeg")),
            cancels = cancels,
            onSaved = { uri ->
                saved += uri
                cancels.cancel("a")
            },
        )

        assertEquals(1, saved.size)
        assertEquals(0, progress.cancelled)
        assertEquals(0, progress.failed)
        assertEquals(1, progress.done)
        assertTrue(cloud.discardedDownloads.isEmpty())
    }

    @Test
    fun `cancelling one item of a transfer drops its row and asks the batch to stop it`() {
        val center = TransferCenter(context)
        val asked = mutableListOf<String>()
        val id = center.start(
            TransferCenter.Kind.DOWNLOAD, total = 2, items = listOf("u1", "u2"), itemKeys = listOf("a", "b"),
            itemStatus = mapOf("a" to "Queued", "b" to "Queued"), onCancelItem = { asked += it },
        )
        val before = center.active.value.single()
        assertTrue(before.itemCancelable)

        center.cancelItem(id, "a")

        assertEquals(listOf("a"), asked)
        val after = center.active.value.single()
        assertFalse("a" in after.itemStatus)
        assertTrue("b" in after.itemStatus)
        center.progress(id, done = 0, total = 1)
        assertEquals(1, center.active.value.single().total)
        center.finish(id)
        assertTrue(center.active.value.isEmpty())
    }

    @Test
    fun `discarding a stopped download removes its cache copy, temp and resume files only`() {
        val service = PhotoDownloadService(
            apiProvider = mockk(relaxed = true),
            cryptoHelper = mockk(relaxed = true),
            cryptoServiceClient = mockk(relaxed = true),
            shareService = mockk(relaxed = true),
            linkDetailHelpers = mockk(relaxed = true),
            cdnBlockFetcher = mockk(relaxed = true),
            albumCryptoChain = mockk(relaxed = true),
            sharedAlbumKeyStore = mockk(relaxed = true),
            photoListingDao = mockk(relaxed = true),
            sdkRouting = mockk(relaxed = true),
            sdkPhotoDownloadOps = mockk(relaxed = true),
            photoLocationDao = mockk(relaxed = true),
            context = context,
        )
        val dir = File(context.cacheDir, "fullres").also { it.deleteRecursively(); it.mkdirs() }
        val ext = mimeToFileExtension("image/jpeg")
        val partial = listOf("L1.$ext", "L1.$ext.tmp", "dec_L1_0.bin", "dec_L1_1.bin", "enc_L1_0.bin", "L1.hashes")
        val kept = listOf("dec_L10_0.bin", "L2.$ext.tmp", "L2.$ext", "L10.$ext")
        (partial + kept).forEach { File(dir, it).writeText("x") }

        service.discardPartialDownload("L1", "image/jpeg")

        partial.forEach { assertFalse("$it should be gone", File(dir, it).exists()) }
        kept.forEach { assertTrue("$it should stay", File(dir, it).exists()) }
    }
}
