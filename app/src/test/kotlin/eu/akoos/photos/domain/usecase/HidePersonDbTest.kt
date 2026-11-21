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
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceEditCounter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hiding and showing a person against an in-memory database: a named person is recorded under its name, a
 * never-named group under a reserved name its faces are confirmed under, no people stream or suggestion read
 * offers a hidden person, no face is rejected, a regrouping keeps a hidden group hidden, and showing a
 * never-named group returns it to an ordinary unnamed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HidePersonDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private val edits = FaceEditCounter()
    private lateinit var db: AppDatabase
    private lateinit var hide: HidePersonUseCase
    private lateinit var show: ShowPersonUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        hide = HidePersonUseCase(db.faceDao(), db.personDao(), db, edits)
        show = ShowPersonUseCase(db.faceDao(), db.personDao(), db, edits)
    }

    @After
    fun tearDown() = db.close()

    private fun embedding(axis: Int): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[0] = 1f
        v[axis] = 0.1f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun person(id: Long, name: String?, faces: Int, owner: String = acct, isOther: Boolean = false) {
        db.personDao().upsert(PersonEntity(id = id, userId = owner, displayName = name, faceCount = faces, isOther = isOther))
        for (i in 1..faces) {
            db.faceDao().upsert(
                FaceEntity(
                    id = "p$id-$i#0", userId = owner, photoKey = "p$id-$i",
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = embedding(i), personId = id, score = 0.9f, manualName = name,
                ),
            )
        }
    }

    private suspend fun listed() = db.personDao().observePeopleForUser(acct).first().map { it.id }
    private suspend fun listedNamed() = db.personDao().observeNamedPeopleForUser(acct).first().map { it.id }
    private suspend fun hiddenList() = db.personDao().observeHiddenPeopleForUser(acct).first().map { it.id }

    @Test
    fun `hiding a named person keeps its name and faces and drops it from every list`() = runTest {
        person(1L, "Anna", 3)
        person(2L, "Bob", 2)

        assertEquals("Anna", hide(acct, 1L))

        assertEquals(listOf(2L), listed())
        assertEquals(listOf(2L), listedNamed())
        assertEquals(listOf(1L), hiddenList())
        assertEquals(listOf(1L), db.personDao().hiddenPersonIds(acct))
        assertEquals(listOf(2L), db.personDao().visibleNamedPeopleForUser(acct).map { it.id })
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
        for (i in 1..3) {
            val face = db.faceDao().faceById("p1-$i#0")!!
            assertFalse(face.rejected)
            assertEquals(1L, face.personId)
        }
        assertEquals(true, db.personHiddenDao().isHidden(acct, "Anna"))
    }

    @Test
    fun `hiding a never-named group labels its faces with a reserved name and keeps them`() = runTest {
        person(1L, null, 3)

        val token = hide(acct, 1L)!!

        assertTrue(HiddenPersonToken.isToken(token))
        assertEquals(token, db.personDao().personById(1L)!!.displayName)
        for (i in 1..3) {
            val face = db.faceDao().faceById("p1-$i#0")!!
            assertEquals(token, face.manualName)
            assertFalse(face.rejected)
            assertEquals(1L, face.personId)
        }
        assertTrue(listed().isEmpty())
        assertTrue(listedNamed().isEmpty())
        assertEquals(listOf(1L), hiddenList())
        assertNotNull(db.personNameDao().namedAt(acct, token))
    }

    @Test
    fun `the Unsorted pile, another account's person and a missing one are not hidden`() = runTest {
        person(1L, null, 2, isOther = true)
        person(2L, "Eve", 2, owner = "other")

        assertNull(hide(acct, 1L))
        assertNull(hide(acct, 2L))
        assertNull(hide(acct, 99L))
        assertTrue(db.personHiddenDao().rowsForUser(acct).isEmpty())
    }

    @Test
    fun `hiding works for the signed-out local partition`() = runTest {
        val local = PhotoLocationEntity.LOCAL_USER
        db.personDao().upsert(PersonEntity(id = 1L, userId = local, displayName = null, faceCount = 1))
        db.faceDao().upsert(
            FaceEntity(
                id = "l1#0", userId = local, photoKey = "l1",
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = embedding(1), personId = 1L, score = 0.9f,
            ),
        )

        assertNotNull(hide(local, 1L))
        assertTrue(db.personDao().observePeopleForUser(local).first().isEmpty())
        assertEquals(1, db.personDao().observeHiddenPeopleForUser(local).first().size)
    }

    @Test
    fun `showing a named person brings it back without a regroup`() = runTest {
        person(1L, "Anna", 3)
        hide(acct, 1L)

        assertFalse(show(acct, 1L))

        assertEquals(listOf(1L), listed())
        assertTrue(hiddenList().isEmpty())
        val row = db.personHiddenDao().rowsForUser(acct).single()
        assertFalse(row.hidden)
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
    }

    @Test
    fun `showing a never-named group clears the reserved labels and asks for a regroup`() = runTest {
        person(1L, null, 3)
        val token = hide(acct, 1L)!!

        assertTrue(show(acct, 1L))

        assertNull(db.personDao().personById(1L)!!.displayName)
        for (i in 1..3) assertNull(db.faceDao().faceById("p1-$i#0")!!.manualName)
        assertEquals(listOf(1L), listed())
        assertTrue(listedNamed().isEmpty())
        assertEquals(false, db.personHiddenDao().isHidden(acct, token))
    }

    @Test
    fun `a later hide after a show wins`() = runTest {
        person(1L, "Anna", 2)
        hide(acct, 1L)
        show(acct, 1L)
        hide(acct, 1L)

        assertEquals(listOf(1L), hiddenList())
        assertTrue(listed().isEmpty())
    }

    @Test
    fun `a regrouping keeps a hidden never-named group together and hidden`() = runTest {
        person(1L, null, 4)
        val token = hide(acct, 1L)!!
        val cluster = ClusterFacesUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db, edits,
        )

        assertTrue(cluster(user).committed)

        val all = db.personDao().allForUser(acct)
        assertEquals(1, all.size)
        assertEquals(token, all.single().displayName)
        assertTrue(listed().isEmpty())
        assertEquals(listOf(all.single().id), hiddenList())
        for (i in 1..4) assertFalse(db.faceDao().faceById("p1-$i#0")!!.rejected)

        assertTrue(show(acct, all.single().id))
        assertTrue(cluster(user).committed)

        val after = db.personDao().allForUser(acct)
        assertTrue(after.all { it.displayName == null })
        assertEquals(after.map { it.id }, listed())
    }

    @Test
    fun `auto-merge never folds a group into a hidden person`() = runTest {
        person(1L, "Anna", 3)
        person(2L, null, 3)
        hide(acct, 1L)
        val assign = AssignPersonNameUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db,
        )

        val merged = AutoMergePeopleUseCase(db.faceDao(), db.personDao(), db.notPersonDao(), assign, db)(acct)

        assertEquals(0, merged)
        assertEquals(3, db.faceDao().faceIdsForPerson(acct, 2L).size)
    }

    @Test
    fun `a hidden choice follows a rename and a reserved name is not accepted as one`() = runTest {
        person(1L, "Anna", 2)
        hide(acct, 1L)
        val rename = RenamePersonUseCase(db.faceDao(), db.personDao(), db, edits)

        rename(acct, 1L, "Anne")

        assertEquals(true, db.personHiddenDao().isHidden(acct, "Anne"))
        assertNull(db.personHiddenDao().isHidden(acct, "Anna"))
        assertEquals(listOf(1L), hiddenList())

        assertEquals("Anne", rename(acct, 1L, HiddenPersonToken.make()))
        assertEquals("Anne", db.personDao().personById(1L)!!.displayName)
    }
}
