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

package eu.akoos.photos.presentation.person

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.R
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceSweepEvent
import eu.akoos.photos.data.face.SweepStop
import eu.akoos.photos.data.face.sweepStopReason
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Find more photos" ends its search with a message rather than ending the app when the sweep throws, says so
 * when the sweep stopped short of the end because the face model stalled, and ends quietly when it finished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindMorePhotosStopDbTest {

    private val acct = "acct"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        context.settingsDataStore.edit {
            it[SettingsKeys.AI_FEATURES_ENABLED] = true
            it[SettingsKeys.FACE_ENABLED] = true
        }
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, displayName = "Anna", faceCount = 1))
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until FACE_EMBEDDING_DIM) buffer.putFloat(if (i == 0) 1f else 0f)
        db.faceDao().upsert(
            FaceEntity(
                id = "a#0", userId = acct, photoKey = "a",
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = buffer.array(), personId = 1L, score = 0.9f,
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        context.settingsDataStore.edit { it.clear() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(sweep: Flow<FaceSweepEvent>): FindMorePhotosViewModel {
        val accounts = mockk<AccountManager>(relaxed = true)
        coEvery { accounts.getPrimaryUserId() } returns flowOf(UserId(acct))
        val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
        every { scheduler.sweepFacelessForPerson(any(), any(), any()) } returns sweep
        val gallery = mockk<GetGalleryItemsUseCase>(relaxed = true)
        every { gallery.invoke(any()) } returns flowOf(emptyList())
        return FindMorePhotosViewModel(
            accountManager = accounts,
            faceDao = db.faceDao(),
            personDao = db.personDao(),
            personManualPhotoDao = db.personManualPhotoDao(),
            faceIndexingScheduler = scheduler,
            getGalleryItems = gallery,
            context = context,
        )
    }

    private fun FindMorePhotosViewModel.ended(): FindMoreUiState = runBlocking {
        withTimeout(10_000) { uiState.first { !it.running } }
    }

    @Test
    fun `a sweep that throws ends the search with a message`() {
        val vm = viewModel(flow {
            emit(FaceSweepEvent.Progress(0, 29_000))
            throw IllegalStateException("torn read")
        })

        vm.start(1L)

        assertEquals(R.string.find_more_failed, vm.ended().stopNotice)
    }

    @Test
    fun `a sweep the stalled model stopped short ends with that message`() {
        val vm = viewModel(flowOf(
            FaceSweepEvent.Progress(0, 29_000),
            FaceSweepEvent.Progress(120, 29_000),
            FaceSweepEvent.Stopped(SweepStop.MODEL_STALLED, 120, 29_000),
        ))

        vm.start(1L)

        val state = vm.ended()
        assertEquals(R.string.find_more_stopped, state.stopNotice)
        assertEquals(120, state.done)
    }

    @Test
    fun `a sweep that finished ends with no message`() {
        val vm = viewModel(flowOf(FaceSweepEvent.Progress(0, 2), FaceSweepEvent.Progress(2, 2)))

        vm.start(1L)

        assertNull(vm.ended().stopNotice)
    }

    @Test
    fun `a sweep stops for the model only when the stalled pairs left photos unread`() {
        assertEquals(SweepStop.MODEL_STALLED, sweepStopReason(done = 120, total = 29_000, abandonCapReached = true))
        assertEquals(SweepStop.COMPLETED, sweepStopReason(done = 29_000, total = 29_000, abandonCapReached = true))
        assertEquals(SweepStop.COMPLETED, sweepStopReason(done = 29_000, total = 29_000, abandonCapReached = false))
        assertEquals(SweepStop.COMPLETED, sweepStopReason(done = 0, total = 0, abandonCapReached = false))
    }
}
