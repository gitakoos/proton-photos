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

package eu.akoos.photos.presentation.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.ImportHistoryDao
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.util.WorkManagerTestSetup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every new import review starts on "Photos in albums", whatever the previous run chose. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportAlbumModeDefaultTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var vm: ImportViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WorkManagerTestSetup.initialize(context)
    }

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new review starts on photos in albums`() {
        val vm = viewModel(pendingZips = emptyList())

        assertEquals(ImportAlbumMode.WITH_PHOTOS, vm.albumMode.value)
    }

    @Test
    fun `leaving a finished run returns to photos in albums`() {
        val vm = viewModel(pendingZips = emptyList())
        vm.setAlbumMode(ImportAlbumMode.NONE)

        vm.reset()

        assertEquals(ImportAlbumMode.WITH_PHOTOS, vm.albumMode.value)
    }

    @Test
    fun `a fresh pick returns to photos in albums`() {
        val vm = viewModel(pendingZips = emptyList())
        vm.setAlbumMode(ImportAlbumMode.SHELLS_ONLY)

        vm.onSourcePicked(Uri.parse("content://eu.akoos.photos.test/export.zip"))

        assertEquals(ImportAlbumMode.WITH_PHOTOS, vm.albumMode.value)
    }

    @Test
    fun `discarding a review returns to photos in albums`() {
        val vm = viewModel(pendingZips = listOf("content://eu.akoos.photos.test/left.zip"))
        vm.setAlbumMode(ImportAlbumMode.NONE)

        vm.discardImport()

        assertEquals(ImportAlbumMode.WITH_PHOTOS, vm.albumMode.value)
    }

    private fun viewModel(pendingZips: List<String>): ImportViewModel {
        val accounts = mockk<AccountManager>(relaxed = true)
        every { accounts.getPrimaryUserId() } returns flowOf<UserId?>(null)
        val history = mockk<ImportHistoryDao>(relaxed = true)
        every { history.observeAll() } returns flowOf(emptyList())
        val staged = mockk<ImportStagedDao>(relaxed = true)
        coEvery { staged.zipsWithPending() } returns pendingZips
        every { staged.observeForZip(any()) } returns flowOf(emptyList())
        every { staged.observeIncludedCount(any()) } returns flowOf(0)
        every { staged.observeNewCount(any()) } returns flowOf(0)
        coEvery { staged.thumbPaths(any()) } returns emptyList()
        return ImportViewModel(
            context = context,
            accountManager = accounts,
            importStagedDao = staged,
            importHistoryDao = history,
            importUploadedDao = mockk(relaxed = true),
            importAlbumMemberDao = mockk(relaxed = true),
            drivePhotoRepository = mockk(relaxed = true),
            undoImportUseCase = mockk(relaxed = true),
        ).also { vm = it }
    }
}
