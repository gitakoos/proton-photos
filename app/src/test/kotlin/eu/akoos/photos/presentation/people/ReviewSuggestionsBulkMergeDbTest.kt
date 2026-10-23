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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.R
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The review screen's bulk merge folds the chosen clusters into one named person in a single transaction, and
 * reports a failure instead of ending the app when a read or a write throws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewSuggestionsBulkMergeDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(personDao: eu.akoos.photos.data.db.dao.PersonDao = db.personDao()): ReviewSuggestionsViewModel {
        val accounts = mockk<AccountManager>(relaxed = true)
        coEvery { accounts.getPrimaryUserId() } returns flowOf(UserId(acct))
        return ReviewSuggestionsViewModel(
            accountManager = accounts,
            observePeopleUseCase = mockk(relaxed = true),
            getGalleryItems = mockk(relaxed = true),
            appDatabase = db,
            faceDao = db.faceDao(),
            personDao = personDao,
            personManualPhotoDao = db.personManualPhotoDao(),
            notPersonDao = db.notPersonDao(),
            hideSimilarFaces = mockk(relaxed = true),
        )
    }

    private suspend fun cluster(id: Long, photos: List<String>, name: String? = null) {
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = photos.size))
        photos.forEachIndexed { i, photo ->
            db.faceDao().upsert(
                FaceEntity(
                    id = "c$id-$i", userId = acct, photoKey = photo,
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = ByteArray(8), personId = id, score = 0.9f,
                ),
            )
        }
    }

    private fun ReviewSuggestionsViewModel.awaitMessage(): Int = runBlocking {
        withTimeout(10_000) { message.first { it != null } }!!
    }

    @Test
    fun `two clusters merged under a new name become one person with the larger one kept`() = runBlocking {
        cluster(1, listOf("a", "b", "c"))
        cluster(2, listOf("d", "e"))
        val vm = viewModel()

        vm.bulkMerge(setOf(1, 2), "Anna")

        assertEquals(R.string.person_msg_merged, vm.awaitMessage())
        val people = db.personDao().allForUser(acct)
        assertEquals(listOf(1L), people.map { it.id })
        assertEquals("Anna", people.single().displayName)
        assertEquals(5, people.single().faceCount)
        assertEquals(5, db.faceDao().faceIdsForPerson(acct, 1).size)
    }

    @Test
    fun `a cluster merged under an existing name joins that person`() = runBlocking {
        cluster(7, listOf("x", "y"), name = "Anna")
        cluster(2, listOf("d", "e", "f"))
        val vm = viewModel()

        vm.bulkMerge(setOf(2), "Anna")

        assertEquals(R.string.person_msg_merged, vm.awaitMessage())
        val people = db.personDao().allForUser(acct)
        assertEquals(listOf(7L), people.map { it.id })
        assertEquals(5, people.single().faceCount)
    }

    @Test
    fun `a read that throws is reported instead of ending the app`() = runBlocking {
        cluster(1, listOf("a", "b"))
        val failing = spyk(db.personDao())
        coEvery { failing.namedPeopleForUser(any()) } throws IllegalStateException("torn read")
        val vm = viewModel(failing)

        vm.bulkMerge(setOf(1), "Anna")

        assertEquals(R.string.name_or_merge_failed, vm.awaitMessage())
        assertNull(db.personDao().personById(1)!!.displayName)
    }

    @Test
    fun `a write that throws leaves the clusters as they were`() = runBlocking {
        cluster(1, listOf("a", "b", "c"))
        cluster(2, listOf("d", "e"))
        val failing = spyk(db.personDao())
        coEvery { failing.deleteEmpty(any()) } throws IllegalStateException("write failed")
        val vm = viewModel(failing)

        vm.bulkMerge(setOf(1, 2), "Anna")

        assertEquals(R.string.name_or_merge_failed, vm.awaitMessage())
        assertEquals(setOf(1L, 2L), db.personDao().allForUser(acct).map { it.id }.toSet())
        assertNull(db.personDao().personById(1)!!.displayName)
        assertEquals(2, db.faceDao().faceIdsForPerson(acct, 2).size)
    }
}
