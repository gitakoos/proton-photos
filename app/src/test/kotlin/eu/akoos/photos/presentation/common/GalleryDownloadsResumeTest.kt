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

package eu.akoos.photos.presentation.common

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.transfer.SaveBatchStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase.ItemResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A save to the device kept on disk survives the app process ending: the next run (a new process,
 * so a new runner with nothing in memory) removes the gallery row a save was still writing, saves
 * only the photos that were not done, and never saves one twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryDownloadsResumeTest {

    /** The device's media index: a row whose id is in [published] reads back published, one in [pending]
     *  reads back pending, any other reads as no row. Deletes are recorded. */
    class FakeMedia : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleted += uri.toString()
            return 1
        }
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.IS_PENDING))
            when (uri.lastPathSegment) {
                in published -> cursor.addRow(arrayOf<Any>(0))
                in pending -> cursor.addRow(arrayOf<Any>(1))
            }
            return cursor
        }

        companion object {
            val published = mutableSetOf<String>()
            val pending = mutableSetOf<String>()
            val deleted = CopyOnWriteArrayList<String>()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val userA = UserId("user-a")
    private val accountManager = mockk<AccountManager> { every { getPrimaryUserId() } returns MutableStateFlow(userA) }
    private val downloadPhotos = mockk<DownloadPhotosUseCase>()
    /** The library as a new process reads it back: every photo but one deleted since. */
    private val library = listOf("a", "b").map(::photo)
    private val driveRepo = mockk<DrivePhotoRepository> {
        coEvery { getAlbumMemberships(any()) } returns emptyMap()
        every { observePhotosByLinkIds(any()) } answers {
            val ids = firstArg<List<String>>()
            flowOf(library.filter { it.linkId in ids })
        }
    }
    private val transferCenter = mockk<TransferCenter>(relaxed = true)
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        FakeMedia.published.clear()
        FakeMedia.pending.clear()
        FakeMedia.deleted.clear()
        File(context.filesDir, SaveBatchStore.DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        File(context.filesDir, SaveBatchStore.DIR).deleteRecursively()
    }

    /** A runner as a fresh process has it: nothing in memory, only what is on disk. */
    private fun newProcess(): GalleryDownloads {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return GalleryDownloads(
            app, downloadPhotos, driveRepo, mockk<OfflineStorageManager>(relaxed = true),
            transferCenter, accountManager, context,
        )
    }

    private fun photo(linkId: String) = CloudPhoto(
        linkId = linkId, shareId = "share", volumeId = "volume", captureTime = 0L,
        displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1L, thumbnailUrl = null,
        revisionId = "rev",
    )

    private fun rowUri(id: Int) = "content://media/external/images/media/$id"

    /** The linkIds each run asked the use case to save, in order. */
    private val asked = CopyOnWriteArrayList<List<String>>()

    @Suppress("UNCHECKED_CAST")
    private fun recordAsked(call: io.mockk.Call) {
        asked += (call.invocation.args[1] as List<GalleryItem>).map { (it as GalleryItem.CloudOnly).cloud.linkId }
    }

    @Test
    fun `a batch cut off by the process ending goes on with only the photos not done`() = runBlocking {
        val batchId = "batch-1"
        SaveBatchStore(context.filesDir, batchId).create(
            SaveBatchStore.Batch(userA.id, listOf("a", "b"), onDeviceCount = 1),
        )
        val bStarted = CompletableDeferred<Unit>()
        // First process: "a" is saved whole, "b" opens its gallery row and the process ends mid-copy.
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordAsked(this.call)
            val journal = arg<DownloadPhotosUseCase.RowJournal>(8)
            journal.opened(Uri.parse(rowUri(10)))
            arg<(String) -> Unit>(4).invoke(rowUri(10))
            journal.settled(Uri.parse(rowUri(10)))
            arg<(String, ItemResult) -> Unit>(7).invoke("a", ItemResult.SAVED)
            journal.opened(Uri.parse(rowUri(11)))
            bStarted.complete(Unit)
            CompletableDeferred<Nothing>().await()
        }
        FakeMedia.published += "10"
        FakeMedia.pending += "11"
        val first = newProcess()
        val run = scopes.last().launch { first.runSaveBatch(batchId, cancelBatch = {}) }
        withTimeout(5_000) { bStarted.await() }
        // The process ends: nothing after this point runs, and nothing tidies up.
        run.cancelAndJoin()
        assertTrue("the batch stays on disk for the next run", SaveBatchStore(context.filesDir, batchId).exists())

        // Second process: the background work runs the batch again.
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordAsked(this.call)
            val journal = arg<DownloadPhotosUseCase.RowJournal>(8)
            journal.opened(Uri.parse(rowUri(12)))
            arg<(String) -> Unit>(4).invoke(rowUri(12))
            journal.settled(Uri.parse(rowUri(12)))
            arg<(String, ItemResult) -> Unit>(7).invoke("b", ItemResult.SAVED)
            DownloadPhotosUseCase.Progress(done = 1, total = 1, failed = 0)
        }
        val ran = withTimeout(5_000) { newProcess().runSaveBatch(batchId, cancelBatch = {}) }

        assertEquals(GalleryDownloads.SaveRun.FINISHED, ran)
        assertEquals("the second run saves only the photo that was not done", listOf(listOf("a", "b"), listOf("b")), asked)
        assertEquals("the row cut off mid-copy is removed, the whole photo stays", listOf(rowUri(11)), FakeMedia.deleted)
        // Both runs' photos and the one already on the device count once each in History.
        coVerify(exactly = 1) {
            transferCenter.log(TransferCenter.Kind.DOWNLOAD, 3, any(), listOf(rowUri(10), rowUri(12)), any())
        }
        assertFalse(SaveBatchStore(context.filesDir, batchId).exists())
    }

    @Test
    fun `a batch that finished is not saved again when its work runs once more`() = runBlocking {
        val batchId = "batch-2"
        SaveBatchStore(context.filesDir, batchId).create(
            SaveBatchStore.Batch(userA.id, listOf("a"), onDeviceCount = 0),
        )
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordAsked(this.call)
            arg<(String, ItemResult) -> Unit>(7).invoke("a", ItemResult.SAVED)
            DownloadPhotosUseCase.Progress(done = 1, total = 1, failed = 0)
        }

        assertEquals(GalleryDownloads.SaveRun.FINISHED, withTimeout(5_000) { newProcess().runSaveBatch(batchId, {}) })
        assertEquals(GalleryDownloads.SaveRun.MISSING, withTimeout(5_000) { newProcess().runSaveBatch(batchId, {}) })
        assertEquals(listOf(listOf("a")), asked)
    }

    @Test
    fun `a run that finds every photo done saves nothing and closes the batch`() = runBlocking {
        val batchId = "batch-3"
        val store = SaveBatchStore(context.filesDir, batchId)
        store.create(SaveBatchStore.Batch(userA.id, listOf("a"), onDeviceCount = 0))
        // The process ended right after the last photo was marked done.
        store.markSaved(rowUri(20))
        store.markDone("a", ItemResult.SAVED)

        assertEquals(GalleryDownloads.SaveRun.FINISHED, withTimeout(5_000) { newProcess().runSaveBatch(batchId, {}) })
        coVerify(exactly = 0) {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { transferCenter.log(TransferCenter.Kind.DOWNLOAD, 1, any(), listOf(rowUri(20)), any()) }
        assertFalse(store.exists())
    }

    @Test
    fun `a photo the library no longer lists counts as failed and is not fetched`() = runBlocking {
        val batchId = "batch-5"
        SaveBatchStore(context.filesDir, batchId).create(
            SaveBatchStore.Batch(userA.id, listOf("a", "gone"), onDeviceCount = 0),
        )
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordAsked(this.call)
            arg<(String) -> Unit>(4).invoke(rowUri(30))
            arg<(String, ItemResult) -> Unit>(7).invoke("a", ItemResult.SAVED)
            DownloadPhotosUseCase.Progress(done = 1, total = 1, failed = 0)
        }

        assertEquals(GalleryDownloads.SaveRun.FINISHED, withTimeout(5_000) { newProcess().runSaveBatch(batchId, {}) })
        assertEquals(listOf(listOf("a")), asked)
        coVerify(exactly = 1) { transferCenter.log(TransferCenter.Kind.DOWNLOAD, 1, any(), listOf(rowUri(30)), any()) }
    }

    @Test
    fun `a batch kept for another account is dropped, not saved`() = runBlocking {
        val batchId = "batch-4"
        SaveBatchStore(context.filesDir, batchId).create(
            SaveBatchStore.Batch("user-b", listOf("a"), onDeviceCount = 0),
        )

        assertEquals(GalleryDownloads.SaveRun.STOPPED, withTimeout(5_000) { newProcess().runSaveBatch(batchId, {}) })
        coVerify(exactly = 0) {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse(SaveBatchStore(context.filesDir, batchId).exists())
    }
}
