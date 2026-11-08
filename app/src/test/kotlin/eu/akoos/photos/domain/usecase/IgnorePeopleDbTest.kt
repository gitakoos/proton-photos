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
import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceEditCounter
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
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
 * "This is not a person" for whole groups, against an in-memory database: the look-alike sweep leaves a named
 * person's faces and confirmed faces alone and reaches past its first page, the people it took faces from are
 * recounted, and a write that fails part way leaves everything as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IgnorePeopleDbTest {

    private val acct = "acct"
    private val edits = FaceEditCounter()
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

    private fun ignore(clusterSummaryDao: ClusterSummaryDao = db.clusterSummaryDao()) = IgnorePeopleUseCase(
        db.faceDao(), db.personDao(), clusterSummaryDao, db,
        HideSimilarFacesUseCase(db.faceDao(), db.personDao()), edits,
    )

    /** A unit vector along [axis], with a small lean on axis 1 so two faces along one axis are near, not equal. */
    private fun along(axis: Int, lean: Float = 0f): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[axis] = 1f
        v[1] = lean
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun person(id: Long, name: String?, faceCount: Int, cover: String? = null) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, coverFaceId = cover, faceCount = faceCount))

    private suspend fun face(id: String, personId: Long?, embedding: ByteArray, confirmed: String? = null, score: Float = 0.5f) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = embedding, personId = personId, score = score, manualName = confirmed,
            ),
        )

    private suspend fun seed() {
        person(1L, null, faceCount = 2)
        face("s1#0", 1L, along(0))
        face("s2#0", 1L, along(0, lean = 0.05f))
        person(2L, "Anna", faceCount = 1)
        face("a1#0", 2L, along(0, lean = 0.02f))
        person(3L, null, faceCount = 2, cover = "o1#0")
        face("o1#0", 3L, along(0, lean = 0.03f), score = 0.9f)
        face("o2#0", 3L, along(5), score = 0.4f)
        face("b1#0", null, along(0, lean = 0.04f), confirmed = "Bob")
    }

    @Test
    fun `the sweep spares named and confirmed faces and recounts the people it took faces from`() = runBlocking {
        seed()

        val removed = ignore()(acct, listOf(1L))

        assertEquals(1, removed)
        assertNull(db.personDao().personById(1L))
        assertTrue(db.faceDao().faceById("s1#0")!!.rejected)
        assertFalse("a named person's face stays", db.faceDao().faceById("a1#0")!!.rejected)
        assertFalse("a confirmed face stays", db.faceDao().faceById("b1#0")!!.rejected)
        assertTrue(db.faceDao().faceById("o1#0")!!.rejected)
        val other = db.personDao().personById(3L)!!
        assertEquals(1, other.faceCount)
        assertEquals("o2#0", other.coverFaceId)
    }

    @Test
    fun `the sweep reads past its first page`() = runBlocking {
        person(1L, null, faceCount = 1)
        face("a#0", 1L, along(0))
        val filler = (0 until 2100).map { i ->
            FaceEntity(
                id = "f${i.toString().padStart(5, '0')}#0", userId = acct, photoKey = "f$i",
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = along(2 + i % 400), personId = null, score = 0.5f,
            )
        }
        db.faceDao().upsert(filler)
        face("zz#0", null, along(0, lean = 0.05f))

        assertEquals(1, ignore()(acct, listOf(1L)))
        assertTrue(db.faceDao().faceById("zz#0")!!.rejected)
        assertFalse(db.faceDao().faceById("f00007#0")!!.rejected)
    }

    @Test
    fun `a named person is dismissed with the curation kept under the name`() = runBlocking {
        person(1L, "Statue", faceCount = 1)
        face("s1#0", 1L, along(0))
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Statue", "x#0")))

        ignore()(acct, listOf(1L))

        assertNull(db.personDao().personById(1L))
        assertTrue(db.notPersonDao().allForUser(acct).isEmpty())
        assertNotNull(db.faceCurationTimeDao().allForUser(acct).firstOrNull { it.removed && it.ref == "x#0" })
    }

    @Test
    fun `a write that throws leaves the groups as they were`() = runBlocking {
        seed()
        val failing = spyk(db.clusterSummaryDao())
        coEvery { failing.deleteOrphansForUser(any()) } throws IllegalStateException("write failed")

        val thrown = runCatching { ignore(failing)(acct, listOf(1L)) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertNotNull(db.personDao().personById(1L))
        assertFalse(db.faceDao().faceById("s1#0")!!.rejected)
        assertFalse(db.faceDao().faceById("o1#0")!!.rejected)
        assertEquals(2, db.personDao().personById(3L)!!.faceCount)
        assertTrue("the edit is over either way", edits.unchangedSince(edits.mark()))
    }

    @Test
    fun `the rule spares confirmed faces, named people and the dismissed groups`() {
        assertTrue(sweepMayRemove(personId = 3L, manualName = null, namedIds = setOf(2L), dismissed = setOf(1L)))
        assertTrue(sweepMayRemove(personId = null, manualName = " ", namedIds = emptySet(), dismissed = emptySet()))
        assertFalse(sweepMayRemove(personId = null, manualName = "Bob", namedIds = emptySet(), dismissed = emptySet()))
        assertFalse(sweepMayRemove(personId = 2L, manualName = null, namedIds = setOf(2L), dismissed = emptySet()))
        assertFalse(sweepMayRemove(personId = 1L, manualName = null, namedIds = emptySet(), dismissed = setOf(1L)))
    }
}
