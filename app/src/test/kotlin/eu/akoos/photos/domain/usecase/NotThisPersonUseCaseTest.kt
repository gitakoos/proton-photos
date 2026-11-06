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
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

/** The shared "this isn't them" bookkeeping, run against an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotThisPersonUseCaseTest {

    private val acct = "acct"
    private val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
    private val accounts = mockk<AccountManager>(relaxed = true)
    private val edits = FaceEditCounter()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        every { accounts.getPrimaryUserId() } returns flowOf(UserId(acct))
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun useCase(personDao: PersonDao = db.personDao()) = NotThisPersonUseCase(
        accounts, db.faceDao(), personDao, db.personManualPhotoDao(), db.notPersonDao(), db.personCoverDao(),
        db.faceCurationTimeDao(), db.clusterSummaryDao(), scheduler, db, edits,
    )

    private suspend fun person(id: Long, name: String?, faceCount: Int) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = faceCount))

    private suspend fun face(id: String, personId: Long, score: Float) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = ByteArray(0), personId = personId, score = score, manualName = null,
            ),
        )

    private suspend fun anna() {
        person(7L, "Anna", faceCount = 3)
        face("a#0", 7L, score = 0.9f)
        face("b#0", 7L, score = 0.5f)
        face("c#0", 7L, score = 0.4f)
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", "a")))
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(7L, acct, ByteArray(8), 3, FACE_MODEL_VERSION))
    }

    @Test
    fun `a named person records the rejections, frees the faces and refreshes`() = runBlocking {
        anna()

        useCase()(acct, 7L, listOf("a#0", "c#0"), listOf("a"))

        assertEquals(
            setOf("Anna" to "a#0", "Anna" to "c#0"),
            db.notPersonDao().allForUser(acct).map { it.personName to it.faceId }.toSet(),
        )
        assertNull(db.faceDao().faceById("a#0")!!.personId)
        assertFalse("freed, not removed", db.faceDao().faceById("a#0")!!.rejected)
        assertTrue(db.personManualPhotoDao().photoKeysForNameList(acct, "Anna").isEmpty())
        val person = db.personDao().personById(7L)!!
        assertEquals(1, person.faceCount)
        assertEquals("b#0", person.coverFaceId)
        assertTrue(db.clusterSummaryDao().getAllForUser(acct).isEmpty())
        verify { scheduler.requestRecluster(UserId(acct)) }
    }

    @Test
    fun `a nameless person records no rejection`() = runBlocking {
        person(9L, null, faceCount = 1)
        face("a#0", 9L, score = 0.9f)

        useCase()(acct, 9L, listOf("a#0"), listOf("a"))

        assertTrue(db.notPersonDao().allForUser(acct).isEmpty())
        assertNull(db.faceDao().faceById("a#0")!!.personId)
        verify { scheduler.requestRecluster(UserId(acct)) }
    }

    @Test
    fun `a write that throws leaves the person as they were and requests nothing`() = runBlocking {
        anna()
        val failing = spyk(db.personDao())
        coEvery { failing.updateCoverAndCount(any(), any(), any()) } throws IllegalStateException("write failed")

        val thrown = runCatching { useCase(failing)(acct, 7L, listOf("a#0"), listOf("a")) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(db.notPersonDao().allForUser(acct).isEmpty())
        assertEquals(7L, db.faceDao().faceById("a#0")!!.personId)
        assertEquals(listOf("a"), db.personManualPhotoDao().photoKeysForNameList(acct, "Anna"))
        assertTrue(db.faceCurationTimeDao().allForUser(acct).isEmpty())
        verify(exactly = 0) { scheduler.requestRecluster(any()) }
    }
}
