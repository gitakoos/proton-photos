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
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving photos from the person page to another person goes through the shared face assignment: the faces
 * land on the person confirmed under their name, a "not this person" mark for that name on them is lifted
 * and noted for the shared copy, and the person they left is recounted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonMoveToPersonDbTest {

    private val acct = "acct"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
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
            faceIndexingScheduler = scheduler,
            ignorePeople = mockk(relaxed = true),
            renamePerson = mockk(relaxed = true),
            assignFacesToPerson = AssignFacesToPersonUseCase(
                db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.personCoverDao(), db.notPersonDao(),
                db.clusterSummaryDao(), scheduler, db, edits,
            ),
            faceEdits = edits,
            personCentroids = mockk(relaxed = true),
            selectionFactory = mockk(relaxed = true),
            context = context,
        )
    }

    private suspend fun face(id: String, personId: Long, confirmed: String?) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = ByteArray(0), personId = personId, score = 0.5f, manualName = confirmed,
            ),
        )

    @Test
    fun `moved faces land confirmed on the person, their mark for that name lifted`() = runBlocking {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, displayName = "Anna", faceCount = 2))
        db.personDao().upsert(PersonEntity(id = 2L, userId = acct, displayName = "Bob", faceCount = 1))
        face("p1#0", 1L, "Anna")
        face("p2#0", 1L, "Anna")
        face("p3#0", 2L, "Bob")
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Bob", "p1#0")))
        val vm = viewModel()

        vm.moveSelectedToPerson(1L, listOf("p1"), "Bob")
        withTimeout(10_000) { vm.message.first { it != null } }

        assertEquals(R.string.person_msg_moved, vm.message.value)
        val moved = db.faceDao().faceById("p1#0")!!
        assertEquals(2L, moved.personId)
        assertEquals("Bob", moved.manualName)
        assertTrue("the mark for that name is lifted", db.notPersonDao().allForUser(acct).isEmpty())
        assertTrue(
            db.faceCurationTimeDao().allForUser(acct).any {
                it.kind == FaceCurationTimeEntity.KIND_NOT_PERSON && it.ref == "p1#0" && it.removed
            },
        )
        assertEquals(1, db.personDao().personById(1L)!!.faceCount)
        assertEquals(2, db.personDao().personById(2L)!!.faceCount)
        verify { scheduler.requestRecluster(UserId(acct)) }
    }
}
