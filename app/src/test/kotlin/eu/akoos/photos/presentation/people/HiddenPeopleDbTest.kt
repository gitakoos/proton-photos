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

package eu.akoos.photos.presentation.people

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.HidePersonUseCase
import eu.akoos.photos.domain.usecase.HiddenPersonToken
import eu.akoos.photos.domain.usecase.ShowPersonUseCase
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.presentation.gallery.PersonUi
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Showing a hidden person again, undoing a hide or a show from the shared bar, and the excluded-faces screen's
 * three kinds of exclusion, against an in-memory database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenPeopleDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val edits = FaceEditCounter()
    private val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
    private val undoController = mockk<UndoController>(relaxed = true)
    private lateinit var db: AppDatabase
    private lateinit var hide: HidePersonUseCase
    private lateinit var show: ShowPersonUseCase
    private lateinit var accounts: AccountManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        hide = HidePersonUseCase(db.faceDao(), db.personDao(), db, edits)
        show = ShowPersonUseCase(db.faceDao(), db.personDao(), db, edits)
        accounts = mockk(relaxed = true)
        every { accounts.getPrimaryUserId() } returns flowOf(user)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun face(id: String, personId: Long?, name: String? = null, rejected: Boolean = false) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = ByteArray(0), personId = personId, score = 0.5f, manualName = name, rejected = rejected,
            ),
        )

    private suspend fun person(id: Long, name: String?, vararg faces: String) {
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = faces.size))
        for (f in faces) face(f, id, name)
    }

    private fun hiddenPeople() =
        HiddenPeople(accounts, mockk(relaxed = true), mockk(relaxed = true), show, scheduler, undoController, mockk(relaxed = true))

    @Test
    fun `showing a hidden named person needs no regroup and offers the undo`() = runBlocking {
        person(1L, "Anna", "a#0")
        hide(acct, 1L)

        hiddenPeople().show(1L)

        assertTrue(db.personDao().hiddenPersonIds(acct).isEmpty())
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
        verify(exactly = 0) { scheduler.requestRecluster(any()) }
        verify { undoController.offer(UndoAction.PersonVisibility(listOf(1L), nowHidden = false)) }
    }

    @Test
    fun `showing a hidden never-named group makes it unnamed again and asks for a regroup`() = runBlocking {
        person(2L, null, "b#0", "c#0")
        hide(acct, 2L)
        assertTrue(HiddenPersonToken.isToken(db.personDao().personById(2L)!!.displayName))

        hiddenPeople().show(2L)

        assertNull(db.personDao().personById(2L)!!.displayName)
        assertTrue(db.personDao().hiddenPersonIds(acct).isEmpty())
        verify { scheduler.requestRecluster(user) }
    }

    private fun undoBar(): UndoController =
        UndoController(context, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), accounts, hide, show, scheduler)

    private fun UndoController.undoAndAwait(action: UndoAction) = runBlocking {
        val done = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { restored.first() } }
        offer(action)
        undo()
        done.await()
    }

    @Test
    fun `undoing a hide from the bar shows the person again`() = runBlocking {
        person(3L, "Cleo", "d#0")
        hide(acct, 3L)

        undoBar().undoAndAwait(UndoAction.PersonVisibility(listOf(3L), nowHidden = true))

        assertTrue(db.personDao().hiddenPersonIds(acct).isEmpty())
    }

    @Test
    fun `undoing a show from the bar hides the person again`() = runBlocking {
        person(4L, "Dan", "e#0")

        undoBar().undoAndAwait(UndoAction.PersonVisibility(listOf(4L), nowHidden = false))

        assertEquals(listOf(4L), db.personDao().hiddenPersonIds(acct))
    }

    private fun exclusionsViewModel(source: HiddenPeople) = FaceExclusionsViewModel(
        accountManager = accounts,
        faceDao = db.faceDao(),
        notPersonDao = db.notPersonDao(),
        faceCurationTimeDao = db.faceCurationTimeDao(),
        faceIndexingScheduler = scheduler,
        faceEdits = edits,
        hiddenPeopleSource = source,
        context = context,
    )

    private fun hiddenSource(hidden: List<PersonUi>): HiddenPeople {
        val source = mockk<HiddenPeople>(relaxed = true)
        every { source.observe() } returns flowOf(hidden)
        return source
    }

    private suspend fun faceRecognitionOn() = context.settingsDataStore.edit {
        it[SettingsKeys.AI_FEATURES_ENABLED] = true
        it[SettingsKeys.FACE_ENABLED] = true
    }

    @Test
    fun `the excluded faces screen reads all three kinds of exclusion`() = runBlocking {
        faceRecognitionOn()
        person(5L, "Eve", "f#0", "g#0")
        face("h#0", null)
        face("i#0", null, rejected = true)
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Eve", "h#0")))
        val hidden = PersonUi(personId = 9L, displayName = null, coverPhotoKey = "z", faceCount = 4)
        val vm = exclusionsViewModel(hiddenSource(listOf(hidden)))

        vm.load()
        val state = withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
        val shown = withTimeout(10_000) { vm.hiddenPeople.first { it != null } }

        assertEquals(listOf("Eve"), state.notThisPerson.map { it.name })
        assertEquals(listOf("h#0"), state.notThisPerson.single().faces.map { it.faceId })
        assertEquals(listOf("i#0"), state.ignored.map { it.faceId })
        assertEquals(listOf(hidden), shown)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `with nothing excluded the state is empty`() = runBlocking {
        faceRecognitionOn()
        val vm = exclusionsViewModel(hiddenSource(emptyList()))

        vm.load()
        val state = withTimeout(10_000) { vm.uiState.first { !it.isLoading } }

        assertTrue(state.isEmpty)
        assertEquals(emptyList<PersonUi>(), withTimeout(10_000) { vm.hiddenPeople.first { it != null } })
    }

    @Test
    fun `showing from the excluded faces screen goes through the shared show`() = runBlocking {
        val source = hiddenSource(emptyList())
        val vm = exclusionsViewModel(source)

        vm.showPerson(7L)

        coVerify(timeout = 10_000) { source.show(7L) }
    }
}
