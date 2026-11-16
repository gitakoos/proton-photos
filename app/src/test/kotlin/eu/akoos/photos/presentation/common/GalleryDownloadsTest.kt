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

import android.content.Context
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A save to the device runs on the app's scope: the screen that started it may close, and with it
 * every coroutine it owned, while the photos still have to reach the device and the History tab.
 */
class GalleryDownloadsTest {

    private val userA = UserId("user-a")
    private val primaryUser = MutableStateFlow<UserId?>(userA)
    private val accountManager = mockk<AccountManager> { every { getPrimaryUserId() } returns primaryUser }
    private val downloadPhotos = mockk<DownloadPhotosUseCase>()
    private val driveRepo = mockk<DrivePhotoRepository> {
        coEvery { getAlbumMemberships(any()) } returns emptyMap()
    }
    private val transferCenter = mockk<TransferCenter>(relaxed = true)
    private val onCancelBatch = mutableListOf<(() -> Unit)?>()
    private val context = mockk<Context>(relaxed = true) {
        every { cacheDir } returns File(System.getProperty("java.io.tmpdir"))
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** Stands in for the injected app scope: the test's clock, its own SupervisorJob, no caller. */
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    /** Stands in for a screen's ViewModel scope, which is cancelled when the screen closes. */
    private fun TestScope.screenScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }

    private fun TestScope.downloads() = GalleryDownloads(
        appScope(), downloadPhotos, driveRepo, mockk<OfflineStorageManager>(relaxed = true),
        transferCenter, accountManager, context,
    )

    private fun cloudItem(linkId: String) = GalleryItem.CloudOnly(
        CloudPhoto(
            linkId = linkId, shareId = "share", volumeId = "volume", captureTime = 0L,
            displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1L,
            thumbnailUrl = null, revisionId = "rev",
        ),
    )

    /** The download parks until [release] completes, then reports one photo saved. */
    private fun parkDownload(started: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        every {
            transferCenter.start(any(), any(), any(), any(), any(), any(), captureNullable(onCancelBatch), any(), any())
        } returns 7L
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            started.complete(Unit)
            release.await()
            val onSaved = arg<(String) -> Unit>(4)
            onSaved("content://media/1")
            DownloadPhotosUseCase.Progress(done = 1, total = 1, failed = 0)
        }
    }

    @Test
    fun `closing the screen does not cancel the save`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        parkDownload(started, release)
        val downloads = downloads()
        val screen = screenScope()
        var screenSawResult = false

        // What a screen does: start the batch, then wait for its result in its own scope.
        val batch = downloads.saveToDevice(listOf(cloudItem("a")))
        screen.launch { batch.await(); screenSawResult = true }
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        screen.cancel()
        advanceUntilIdle()
        assertFalse("the save must still be running with the screen gone", batch.outcome.isCompleted)

        release.complete(Unit)
        advanceUntilIdle()
        val outcome = batch.outcome.await()
        assertTrue(outcome is GalleryDownloads.Outcome.Finished)
        assertEquals(1, (outcome as GalleryDownloads.Outcome.Finished).progress.done)
        assertFalse("the closed screen's wait ended with it", screenSawResult)
        coVerify(exactly = 1) {
            transferCenter.log(TransferCenter.Kind.DOWNLOAD, 1, any(), listOf("content://media/1"), any())
        }
        coVerify(exactly = 1) { transferCenter.finish(7L) }
    }

    @Test
    fun `the Activity screen cancel stops the batch and the wait reads it as stopped`() = runTest {
        val started = CompletableDeferred<Unit>()
        parkDownload(started, CompletableDeferred())
        val downloads = downloads()
        var awaited: GalleryDownloads.Outcome? = GalleryDownloads.Outcome.NotSignedIn
        var waitEnded = false

        val batch = downloads.saveToDevice(listOf(cloudItem("a")))
        screenScope().launch { awaited = batch.await(); waitEnded = true }
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        onCancelBatch.single()!!.invoke()
        advanceUntilIdle()
        assertTrue(batch.outcome.isCancelled)
        assertTrue(waitEnded)
        assertNull(awaited)
        coVerify(exactly = 0) { transferCenter.log(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { transferCenter.finish(7L) }
    }

    @Test
    fun `a sign-out stops a running save`() = runTest {
        val started = CompletableDeferred<Unit>()
        parkDownload(started, CompletableDeferred())
        val downloads = downloads()

        val batch = downloads.saveToDevice(listOf(cloudItem("a")))
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        primaryUser.value = null
        advanceUntilIdle()
        assertTrue("the account the batch started under is gone", batch.outcome.isCancelled)
        coVerify(exactly = 1) { transferCenter.finish(7L) }
    }

    @Test
    fun `a save with nobody signed in fetches nothing`() = runTest {
        primaryUser.value = null
        val downloads = downloads()

        val batch = downloads.saveToDevice(listOf(cloudItem("a")))
        advanceUntilIdle()
        assertEquals(GalleryDownloads.Outcome.NotSignedIn, batch.outcome.await())
        coVerify(exactly = 0) {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a failure the batch could not count reaches the screen as a failure`() = runTest {
        every {
            transferCenter.start(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 7L
        coEvery {
            downloadPhotos.downloadGalleryItems(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("volume gone")
        val downloads = downloads()

        val batch = downloads.saveToDevice(listOf(cloudItem("a")))
        advanceUntilIdle()
        assertEquals(GalleryDownloads.Outcome.Failed("volume gone"), batch.outcome.await())
        coVerify(exactly = 1) { transferCenter.finish(7L) }
    }
}
