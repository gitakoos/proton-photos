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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.R
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.HidePersonUseCase
import eu.akoos.photos.domain.usecase.HiddenPersonToken
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Hiding a person from its own page: a named person and a never-named group are hidden with their faces
 * kept, the page is left, and the hide is offered once on the shared undo bar; the Unsorted pile is never
 * hidden and tells the user instead; a typed reserved name never renames a person.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonHideDbTest {

    private val acct = "acct"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val undoController = mockk<UndoController>(relaxed = true)
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): PersonDetailViewModel {
        val accounts = mockk<AccountManager>(relaxed = true)
        every { accounts.getPrimaryUserId() } returns flowOf(UserId(acct))
        val edits = FaceEditCounter()
        return PersonDetailViewModel(
            accountManager = accounts,
            faceDao = db.faceDao(),
            personDao = db.personDao(),
            personManualPhotoDao = db.personManualPhotoDao(),
            notPersonDao = db.notPersonDao(),
            personCoverDao = db.personCoverDao(),
            faceCurationTimeDao = db.faceCurationTimeDao(),
            clusterSummaryDao = db.clusterSummaryDao(),
            notThisPerson = mockk(relaxed = true),
            getGalleryItems = mockk(relaxed = true),
            observePeopleUseCase = mockk(relaxed = true),
            assignPersonName = mockk(relaxed = true),
            faceIndexingScheduler = mockk<FaceIndexingScheduler>(relaxed = true),
            ignorePeople = mockk(relaxed = true),
            hidePersonUseCase = HidePersonUseCase(db.faceDao(), db.personDao(), db, edits),
            undoController = undoController,
            renamePerson = mockk(relaxed = true),
            assignFacesToPerson = mockk(relaxed = true),
            faceEdits = edits,
            personCentroids = mockk(relaxed = true),
            selectionFactory = mockk(relaxed = true),
            context = context,
        )
    }

    private suspend fun person(id: Long, name: String?, other: Boolean = false, vararg faces: String) {
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = faces.size, isOther = other))
        for (f in faces) {
            db.faceDao().upsert(
                FaceEntity(
                    id = f, userId = acct, photoKey = f.substringBefore('#'),
                    left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                    embedding = ByteArray(0), personId = id, score = 0.5f, manualName = name,
                ),
            )
        }
    }

    @Test
    fun `hiding a named person keeps its faces, leaves the page and offers the undo`() = runBlocking {
        person(1L, "Anna", faces = arrayOf("a#0", "b#0"))
        val vm = viewModel()
        val left = CompletableDeferred<Unit>()

        vm.hidePerson(1L) { left.complete(Unit) }
        withTimeout(10_000) { left.await() }

        assertEquals(listOf(1L), db.personDao().hiddenPersonIds(acct))
        assertEquals(2, db.faceDao().faceIdsForPerson(acct, 1L).size)
        assertTrue(db.faceDao().faceIdsForPerson(acct, 1L).none { db.faceDao().faceById(it)!!.rejected })
        verify { undoController.offer(UndoAction.PersonVisibility(listOf(1L), nowHidden = true)) }
    }

    @Test
    fun `hiding a never-named group labels its faces with a reserved name nothing shows`() = runBlocking {
        person(2L, null, faces = arrayOf("c#0", "d#0"))
        val vm = viewModel()
        val left = CompletableDeferred<Unit>()

        vm.hidePerson(2L) { left.complete(Unit) }
        withTimeout(10_000) { left.await() }

        val row = db.personDao().personById(2L)!!
        assertTrue(HiddenPersonToken.isToken(row.displayName))
        assertNull(HiddenPersonToken.visibleName(row.displayName))
        assertEquals(listOf(2L), db.personDao().hiddenPersonIds(acct))
    }

    @Test
    fun `the Unsorted pile is not hidden, the page stays and the user is told`() = runBlocking {
        person(3L, null, other = true, faces = arrayOf("e#0"))
        val vm = viewModel()
        var left = false

        vm.hidePerson(3L) { left = true }
        withTimeout(10_000) { vm.message.first { it != null } }

        assertEquals(R.string.name_or_merge_failed, vm.message.value)
        assertFalse(left)
        assertTrue(db.personDao().hiddenPersonIds(acct).isEmpty())
        verify(exactly = 0) { undoController.offer(any()) }
    }

    @Test
    fun `a typed reserved name never renames a person`() = runBlocking {
        person(4L, "Bob", faces = arrayOf("f#0"))
        val vm = viewModel()

        vm.rename(4L, HiddenPersonToken.make())

        assertEquals("Bob", db.personDao().personById(4L)!!.displayName)
    }
}
