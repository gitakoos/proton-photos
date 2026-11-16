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
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.localcrypto.CachedOriginal
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An offline pin runs on the app's scope too: the screen may close mid-batch, and the pins it wrote
 * up front must end matching the blobs that actually landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryDownloadsPinTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val userA = UserId("user-a")
    private val primaryUser = MutableStateFlow<UserId?>(userA)
    private val accountManager = mockk<AccountManager> { every { getPrimaryUserId() } returns primaryUser }
    private val driveRepo = mockk<DrivePhotoRepository>()
    private val offlineStore = mockk<OfflineStorageManager>(relaxed = true)
    private val transferCenter = mockk<TransferCenter>(relaxed = true)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screen = CoroutineScope(Job() + Dispatchers.Default)

    private val downloads = GalleryDownloads(
        appScope, mockk(), driveRepo, offlineStore, transferCenter, accountManager, context,
    )

    @Before
    fun clearPins() = runBlocking {
        context.settingsDataStore.edit { it.remove(SettingsKeys.OFFLINE_PIN_IDS) }
        Unit
    }

    @After
    fun cancelScopesAndClearPins() {
        screen.cancel()
        appScope.cancel()
        clearPins()
    }

    private fun photo(linkId: String) = CloudPhoto(
        linkId = linkId, shareId = "share", volumeId = "volume", captureTime = 0L,
        displayName = "$linkId.jpg", mimeType = "image/jpeg", sizeBytes = 1L,
        thumbnailUrl = null, revisionId = "rev",
    )

    private suspend fun pins(): Set<String> =
        context.settingsDataStore.data.first()[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()

    @Test
    fun `closing the screen does not strand the pins`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = CachedOriginal(File("plain"))
        coEvery { driveRepo.downloadFullResPhoto(userA, any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            original
        }
        coEvery { offlineStore.store(any(), any()) } answers { File("blob-${firstArg<String>()}") }
        var screenSawResult = false

        val pinning = downloads.pinOffline(listOf(photo("a")))
        screen.launch { pinning.awaitUnlessStopped(); screenSawResult = true }
        withTimeout(5_000) { started.await() }
        assertEquals(setOf("a"), pins())

        screen.cancel()
        release.complete(Unit)
        assertEquals(GalleryDownloads.PinResult(pinned = 1, failed = 0), withTimeout(5_000) { pinning.await() })
        assertEquals("the pin stays with its blob in place", setOf("a"), pins())
        assertFalse(screenSawResult)
        verify(exactly = 0) { offlineStore.delete("a") }
    }

    @Test
    fun `a failed download takes its pin back`() = runBlocking {
        coEvery { driveRepo.downloadFullResPhoto(userA, any(), any(), any()) } throws IllegalStateException("offline")

        assertEquals(
            GalleryDownloads.PinResult(pinned = 0, failed = 1),
            withTimeout(5_000) { downloads.pinOffline(listOf(photo("a"))).await() },
        )
        assertTrue(pins().isEmpty())
        verify { offlineStore.delete("a") }
    }

    @Test
    fun `an un-pin while the download runs is no failure and drops the blob`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { driveRepo.downloadFullResPhoto(userA, any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            CachedOriginal(File("plain"))
        }
        coEvery { offlineStore.store(any(), any()) } answers { File("blob-${firstArg<String>()}") }

        val pinning = downloads.pinOffline(listOf(photo("a")))
        withTimeout(5_000) { started.await() }
        // The user taps the pin again: the un-pin drops the id at once.
        context.settingsDataStore.edit { it[SettingsKeys.OFFLINE_PIN_IDS] = emptySet() }
        release.complete(Unit)

        assertEquals(GalleryDownloads.PinResult(pinned = 0, failed = 0), withTimeout(5_000) { pinning.await() })
        assertTrue(pins().isEmpty())
        verify { offlineStore.delete("a") }
    }

    @Test
    fun `a sign-out mid-batch takes back every pin that has no blob`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        coEvery { driveRepo.downloadFullResPhoto(userA, any(), any(), any()) } coAnswers {
            started.complete(Unit)
            CompletableDeferred<Nothing>().await()
        }

        val pinning = downloads.pinOffline(listOf(photo("a"), photo("b")))
        withTimeout(5_000) { started.await() }
        assertEquals(setOf("a", "b"), pins())

        primaryUser.value = null
        withTimeout(5_000) { pinning.join() }
        assertTrue(pinning.isCancelled)
        assertTrue(pins().isEmpty())
    }
}
