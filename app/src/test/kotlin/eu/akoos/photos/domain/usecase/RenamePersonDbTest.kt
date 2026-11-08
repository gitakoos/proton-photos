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
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonConfirmedName
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceEditCounter
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

/**
 * Renaming a person and taking the name away, against an in-memory database: a removed name leaves no face
 * confirmed under it anywhere, a confirmation marked "not this person" never names a group, and a write that
 * fails part way leaves the person as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenamePersonDbTest {

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

    private fun rename(faceDao: FaceDao = db.faceDao()) = RenamePersonUseCase(faceDao, db.personDao(), db, FaceEditCounter())

    private suspend fun person(id: Long, name: String?) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = 1))

    private suspend fun face(id: String, personId: Long?, confirmed: String?, user: String = acct) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = user, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = ByteArray(0), personId = personId, score = 0.5f, manualName = confirmed,
            ),
        )

    @Test
    fun `taking a name away clears it from every face of the account, wherever it is grouped`() = runBlocking {
        person(1L, "Anna")
        person(2L, null)
        face("a#0", 1L, "Anna")
        // A face confirmed as Anna that a rebuild grouped elsewhere, and one waiting for a group.
        face("b#0", 2L, "Anna")
        face("c#0", null, "Anna")
        face("d#0", 2L, "Bob")
        face("e#0", null, "Anna", user = "other")

        assertNull(rename()(acct, 1L, "  "))

        assertNull(db.personDao().personById(1L)!!.displayName)
        assertNull(db.faceDao().faceById("a#0")!!.manualName)
        assertNull(db.faceDao().faceById("b#0")!!.manualName)
        assertNull(db.faceDao().faceById("c#0")!!.manualName)
        assertEquals("Bob", db.faceDao().faceById("d#0")!!.manualName)
        assertEquals("another account keeps its own", "Anna", db.faceDao().faceById("e#0")!!.manualName)
    }

    @Test
    fun `a rename re-keys the curation and confirms the faces under the new name`() = runBlocking {
        person(1L, "Anna")
        face("a#0", 1L, null)
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", "m")))
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Anna", "x#0")))

        assertEquals("Annie", rename()(acct, 1L, " Annie "))

        assertEquals("Annie", db.personDao().personById(1L)!!.displayName)
        assertEquals("Annie", db.faceDao().faceById("a#0")!!.manualName)
        assertEquals(listOf("m"), db.personManualPhotoDao().photoKeysForNameList(acct, "Annie"))
        assertEquals(listOf("Annie"), db.notPersonDao().allForUser(acct).map { it.personName })
    }

    @Test
    fun `a write that throws leaves the person as they were`() = runBlocking {
        person(1L, "Anna")
        face("a#0", 1L, "Anna")
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", "m")))
        val failing = spyk(db.faceDao())
        coEvery { failing.labelFacesByIds(any(), any()) } throws IllegalStateException("write failed")

        val thrown = runCatching { rename(failing)(acct, 1L, "Annie") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
        assertEquals("Anna", db.faceDao().faceById("a#0")!!.manualName)
        assertEquals(listOf("m"), db.personManualPhotoDao().photoKeysForNameList(acct, "Anna"))
    }

    @Test
    fun `a confirmation marked as not being that name never names the group`() = runBlocking {
        person(1L, null)
        face("a#0", 1L, "Anna")
        face("b#0", 1L, "Anna")
        face("c#0", 1L, "Cleo")
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Anna", "a#0"), NotPersonEntity(acct, "Anna", "b#0")))

        val names = db.faceDao().confirmedNamesByPerson(acct)

        assertEquals(listOf(PersonConfirmedName(1L, "Cleo", 1)), names)
    }
}
