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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Folding a group into a named person writes in one transaction, so a write that fails leaves both as they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignPersonNameTransactionDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun assign(personDao: PersonDao = db.personDao()) = AssignPersonNameUseCase(
        db.faceDao(), personDao, db.personManualPhotoDao(), db.notPersonDao(),
        db.personCoverDao(), db.clusterSummaryDao(), db,
    )

    private suspend fun person(id: Long, photos: List<String>, name: String? = null) {
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = photos.size))
        photos.forEachIndexed { i, photo ->
            db.faceDao().upsert(
                FaceEntity(
                    id = "$photo#$i", userId = acct, photoKey = photo,
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = ByteArray(8), personId = id, score = 0.9f, manualName = name,
                ),
            )
        }
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(id, acct, ByteArray(8), photos.size, FACE_MODEL_VERSION))
    }

    @Test
    fun `a group folded into a named person leaves one person holding every face`() = runBlocking {
        person(1, listOf("a", "b"), name = "Anna")
        person(2, listOf("c", "d", "e"))

        assertEquals(1L, assign()(acct, 2L, "Anna"))

        assertEquals(listOf(1L), db.personDao().allForUser(acct).map { it.id })
        assertEquals(5, db.personDao().personById(1)!!.faceCount)
        assertEquals(5, db.faceDao().faceIdsForPerson(acct, 1).size)
        assertTrue(db.clusterSummaryDao().getAllForUser(acct).isEmpty())
    }

    @Test
    fun `a write that throws leaves both people as they were`() = runBlocking {
        person(1, listOf("a", "b"), name = "Anna")
        person(2, listOf("c", "d", "e"))
        val failing = spyk(db.personDao())
        coEvery { failing.deleteEmpty(any()) } throws IllegalStateException("write failed")

        val thrown = runCatching { assign(failing)(acct, 2L, "Anna") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(setOf(1L, 2L), db.personDao().allForUser(acct).map { it.id }.toSet())
        assertEquals(2, db.personDao().personById(1)!!.faceCount)
        assertEquals(3, db.personDao().personById(2)!!.faceCount)
        assertNull(db.personDao().personById(2)!!.displayName)
        assertEquals(3, db.faceDao().faceIdsForPerson(acct, 2).size)
        assertNull(db.faceDao().faceById("c#0")!!.manualName)
        assertEquals(setOf(1L, 2L), db.clusterSummaryDao().getAllForUser(acct).map { it.personId }.toSet())
    }
}
