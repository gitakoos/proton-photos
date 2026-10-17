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
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import kotlinx.coroutines.test.runTest
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The batched incremental placement a library too big for the whole-library rebuild relies on: a backlog
 * past the incremental cap is still placed against the cached people, a bounded slice per call, and the
 * cursor moves on so every ungrouped face is visited exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterFacesBatchDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private lateinit var db: AppDatabase
    private lateinit var cluster: ClusterFacesUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        cluster = ClusterFacesUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db,
        )
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

    private suspend fun face(id: String, personId: Long?, axis: Int) = db.faceDao().upsert(
        FaceEntity(
            id = id, userId = acct, photoKey = id.substringBefore('#'),
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
            embedding = embedding(axis), personId = personId, score = 0.9f,
        ),
    )

    private suspend fun seedAnna(members: Int) {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, displayName = "Anna", faceCount = members))
        for (i in 1..members) face("a$i#0", 1L, axis = (i % 8) + 1)
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, embedding(axis = 1), members, FACE_MODEL_VERSION))
    }

    @Test
    fun `a batched call places only its slice and advances the cursor`() = runTest {
        seedAnna(members = 4)
        for (i in 1..5) face("n$i#0", null, axis = i)

        val batch = IncrementalBatch(limit = 2)
        assertTrue(cluster.assignNewFaces(user, batch = batch))

        assertEquals(2, batch.scanned)
        assertTrue(batch.afterRow > 0L)
        assertEquals(1L, db.faceDao().faceById("n1#0")!!.personId)
        assertEquals(1L, db.faceDao().faceById("n2#0")!!.personId)
        assertNull(db.faceDao().faceById("n3#0")!!.personId)
    }

    @Test
    fun `looping the batches visits every ungrouped face and then reads none`() = runTest {
        seedAnna(members = 4)
        for (i in 1..5) face("n$i#0", null, axis = i)

        val batch = IncrementalBatch(limit = 2)
        var calls = 0
        do {
            batch.scanned = 0
            assertTrue(cluster.assignNewFaces(user, batch = batch))
            calls++
        } while (batch.scanned == batch.limit)

        assertEquals(3, calls)
        assertEquals(0, db.faceDao().unclusteredCount(acct))
        assertEquals(9, db.clusterSummaryDao().getAllForUser(acct).sumOf { it.memberCount })
    }

    @Test
    fun `a backlog past the incremental cap is refused whole but placed in batches`() = runTest {
        seedAnna(members = 4)
        val backlog = 600
        for (i in 1..backlog) face("n${"%04d".format(i)}#0", null, axis = (i % 8) + 1)

        assertFalse(cluster.assignNewFaces(user))
        assertEquals(backlog, db.faceDao().unclusteredCount(acct))

        val batch = IncrementalBatch(limit = 200)
        do {
            batch.scanned = 0
            assertTrue(cluster.assignNewFaces(user, batch = batch))
        } while (batch.scanned == batch.limit)

        assertEquals(0, db.faceDao().unclusteredCount(acct))
    }

    private suspend fun weakFace(id: String, axis: Int) {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[axis] = 1f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = buffer.array(), personId = null, score = 0.3f, blur = 1f,
            ),
        )
    }

    @Test
    fun `a run that starts after a row reads only the faces stored since`() = runTest {
        seedAnna(members = 4)
        for (i in 1..3) weakFace("w$i#0", axis = 30 + i)
        val first = IncrementalBatch(limit = 10)
        do {
            first.scanned = 0
            assertTrue(cluster.assignNewFaces(user, batch = first))
        } while (first.scanned == first.limit)
        assertEquals(3, db.faceDao().unclusteredCount(acct))
        assertTrue(first.maxRow > 0L)

        face("n1#0", null, axis = 1)
        face("n2#0", null, axis = 2)
        assertEquals(2, db.faceDao().unclusteredCountSince(acct, first.maxRow))
        assertEquals(5, db.faceDao().unclusteredCountSince(acct, 0L))

        val later = IncrementalBatch(limit = 10).also { it.minRow = first.maxRow }
        assertTrue(cluster.assignNewFaces(user, batch = later))
        assertEquals(2, later.scanned)
        assertEquals(1L, db.faceDao().faceById("n1#0")!!.personId)
        assertEquals(1L, db.faceDao().faceById("n2#0")!!.personId)
        assertEquals(3, db.faceDao().unclusteredCount(acct))
        assertTrue(later.maxRow > first.maxRow)

        val everything = IncrementalBatch(limit = 10)
        assertTrue(cluster.assignNewFaces(user, batch = everything))
        assertEquals(3, everything.scanned)
    }

    @Test
    fun `a person's photo count moves by the photos gained, not the faces`() = runTest {
        seedAnna(members = 4)
        // A second face on a photo Anna already holds, two faces on one new photo, one face on another new
        // photo, and one on a photo attached to Anna by hand: three of the four add a photo only if the
        // hand-attached one is not counted twice.
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", "m1")))
        face("a1#1", null, axis = 1)
        face("n1#0", null, axis = 2)
        face("n1#1", null, axis = 3)
        face("n2#0", null, axis = 4)
        face("m1#0", null, axis = 5)

        val batch = IncrementalBatch(limit = 10)
        assertTrue(cluster.assignNewFaces(user, batch = batch))

        assertEquals(1L, db.faceDao().faceById("m1#0")!!.personId)
        assertEquals(4 + 2, db.personDao().personById(1L)!!.faceCount)
    }

    @Test
    fun `a batched call whose write check says no places nothing`() = runTest {
        seedAnna(members = 4)
        face("n1#0", null, axis = 1)

        assertFalse(cluster.assignNewFaces(user, IncrementalBatch(limit = 10)) { false })
        assertNull(db.faceDao().faceById("n1#0")!!.personId)
    }
}
