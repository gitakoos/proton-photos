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

package eu.akoos.photos.presentation.gallery

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.hidden.HiddenSyncTimes
import eu.akoos.photos.data.label.PhotoLabelRepository
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.UploadProgress
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.util.WorkManagerTestSetup
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The timeline screen built the way a start builds it: favourites and a waiting remote hide already stored, and
 * the settings already read once, so their first value is handled inside the constructor. Nothing the screen
 * starts may fail, and the timeline shows the library and filters it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelStartTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null
    private var scope: CoroutineScope? = null

    private val favourite = local("content://media/external/images/media/1", 2_000)
    private val other = local("content://media/external/images/media/2", 1_000)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestSetup.initialize(context)
        val record = Thread.UncaughtExceptionHandler { _, e -> failures += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        previousOwn = Thread.currentThread().uncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler(record)
        Thread.currentThread().uncaughtExceptionHandler = record
        runBlocking {
            context.settingsDataStore.edit { prefs ->
                prefs.clear()
                prefs[SettingsKeys.FAVORITE_IDS] = setOf(favourite.local.uri)
                // A photo another device hid that this library does not hold, so it waits without hiding anything.
                HiddenSyncTimes.setRemoteHideQueue(prefs, mapOf("linkNotOnThisPhone" to 1_000L))
            }
            // The cache a real start has already filled before the screen is built.
            context.settingsDataStore.data.first()
        }
    }

    @After
    fun tearDown() {
        scope?.cancel()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Dispatchers.resetMain()
    }

    @Test
    fun `a start with stored favourites and a waiting remote hide builds the timeline`() {
        val vm = viewModel()
        scope = vm.viewModelScope

        assertNoFailures()
        // Taken in while the constructor ran, from the settings already read.
        assertEquals(setOf(favourite.local.uri), vm.uiState.value.favoriteIds)
        awaitState(vm) { it.items.isNotEmpty() }
        assertEquals(listOf(favourite, other), vm.uiState.value.filteredItems)

        vm.onFilterSelected(GalleryFilter.Favorites)
        awaitState(vm) { it.filteredItems == listOf(favourite) }

        assertNoFailures()
    }

    private fun assertNoFailures() =
        assertTrue("failed while starting: ${failures.map { it.stackTraceToString() }}", failures.isEmpty())

    private fun awaitState(vm: GalleryViewModel, done: (GalleryUiState) -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done(vm.uiState.value)) {
            assertNoFailures()
            assertTrue("timeline never reached the expected state: ${vm.uiState.value}", System.currentTimeMillis() < until)
            dispatcher.scheduler.advanceTimeBy(400)
            dispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
    }

    private fun viewModel(): GalleryViewModel {
        val accounts = mockk<AccountManager>(relaxed = true)
        every { accounts.getPrimaryUserId() } returns flowOf<UserId?>(null)
        // A live library, as the real one is: the timeline samples it, and a finished flow would drop its value.
        val library = MutableStateFlow(listOf<GalleryItem>(favourite, other))
        val gallery = mockk<GetGalleryItemsUseCase>(relaxed = true)
        every { gallery.invokeLocalOnly() } returns library
        every { gallery.withQuickStart(any(), any()) } returns library
        val labels = mockk<PhotoLabelRepository>(relaxed = true)
        every { labels.observeLabelUsage() } returns flowOf(emptyList())
        every { labels.observeLabelsByPhoto() } returns flowOf(emptyMap())
        val upload = mockk<UploadPendingUseCase>(relaxed = true)
        every { upload.progress } returns MutableSharedFlow<UploadProgress>()
        val transfers = mockk<TransferCenter>(relaxed = true)
        every { transfers.active } returns MutableStateFlow(emptyList())
        val undo = mockk<UndoController>(relaxed = true)
        every { undo.restored } returns MutableSharedFlow<UndoAction>()
        return GalleryViewModel(
            context = context,
            peopleVisibility = mockk(relaxed = true),
            sameNamedPeopleMerge = mockk(relaxed = true),
            getGalleryItems = gallery,
            cloudRepo = mockk(relaxed = true),
            photoStreamService = mockk(relaxed = true),
            thumbnailUrlStore = mockk(relaxed = true),
            localRepo = mockk(relaxed = true),
            accountManager = accounts,
            observeUser = mockk(relaxed = true),
            deletePhotoUseCase = mockk(relaxed = true),
            galleryDownloads = mockk(relaxed = true),
            reconcile = mockk(relaxed = true),
            upload = upload,
            forceUploadLocalUris = mockk(relaxed = true),
            invalidateStrippedLocations = mockk(relaxed = true),
            hiddenStorage = mockk(relaxed = true),
            hiddenVaultJournal = mockk(relaxed = true),
            offlineStore = mockk(relaxed = true),
            transferCenter = transfers,
            undoController = undo,
            syncStateRepo = mockk(relaxed = true),
            syncStateSnapshot = mockk(relaxed = true),
            networkObserver = mockk(relaxed = true),
            albumPhotoMembershipDao = mockk(relaxed = true),
            photoListingDao = mockk(relaxed = true),
            albumListEvents = mockk(relaxed = true),
            updateOrchestrator = mockk(relaxed = true),
            newsRepository = mockk(relaxed = true),
            publicLink = mockk(relaxed = true),
            favoriteWriter = mockk(relaxed = true),
            cryptoServiceClient = mockk(relaxed = true),
            faceDao = mockk(relaxed = true),
            observePeopleUseCase = mockk(relaxed = true),
            moveToFolder = mockk(relaxed = true),
            folderDelete = mockk(relaxed = true),
            sharedBackup = mockk(relaxed = true),
            labelRepo = labels,
        )
    }

    private fun local(uri: String, dateTaken: Long) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = dateTaken,
            displayName = uri.substringAfterLast('/') + ".jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_000,
            bucketName = "Camera",
        ),
    )
}
