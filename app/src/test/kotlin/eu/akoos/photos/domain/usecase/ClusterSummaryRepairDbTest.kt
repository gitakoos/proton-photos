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
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceVector
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * The centroid cache a batched grouping matches against is repaired only where a curation edit left it off: a
 * merge of two groups reads the faces of the person that took the other in, the rest of the cache stays as it
 * was, and what the repair leaves equals a rebuild of every person.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSummaryRepairDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private lateinit var db: AppDatabase
    private lateinit var faces: CountingFaceDao
    private lateinit var cluster: ClusterFacesUseCase

    /** Every call is the real one; the vector reads are noted by person. */
    private class CountingFaceDao(private val inner: FaceDao) : FaceDao by inner {
        val vectorReads = ArrayList<Long>()

        override suspend fun personVectorsAfter(userId: String, personId: Long, afterId: String, limit: Int): List<FaceVector> =
            inner.personVectorsAfter(userId, personId, afterId, limit).also { vectorReads.add(personId) }
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        faces = CountingFaceDao(db.faceDao())
        cluster = ClusterFacesUseCase(
            faces, db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun embedding(seed: Long): ByteArray {
        val random = Random(seed)
        val v = normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextGaussian().toFloat() })
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    /** [PEOPLE] people of two faces each, on photos of their own, the first one named; no cached centroid yet. */
    private suspend fun seed() {
        db.withTransaction {
            for (id in 1L..PEOPLE) {
                db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = if (id == 1L) "Anna" else null, faceCount = 2))
            }
        }
        val rows = (1L..PEOPLE).flatMap { id ->
            (0 until 2).map { i ->
                FaceEntity(
                    id = "p$id-$i#0", userId = acct, photoKey = "p$id-$i",
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = embedding(id * 10 + i), personId = id, score = 0.9f,
                )
            }
        }
        for (chunk in rows.chunked(500)) db.faceDao().upsert(chunk)
    }

    /** A batch call with no face waiting: it only brings the cache in line with the groups. */
    private suspend fun repair(onTick: () -> Unit = {}) {
        faces.vectorReads.clear()
        assertTrue(cluster.assignNewFaces(user, IncrementalBatch(limit = 100), onTick = onTick))
    }

    private suspend fun cache(): Map<Long, Pair<List<Byte>, Int>> =
        db.clusterSummaryDao().getAllForUser(acct).associate { it.personId to (it.centroid.toList() to it.memberCount) }

    @Test
    fun `a merge repairs only the person that took the other in`() = runBlocking {
        seed()
        var ticks = 0
        repair { ticks++ }
        assertEquals(PEOPLE.toInt(), faces.vectorReads.toSet().size)
        assertTrue("ticks=$ticks", ticks >= PEOPLE.toInt() / 64)
        val before = cache()

        val assign = AssignPersonNameUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db,
        )
        assertEquals(1L, assign(acct, fromPersonId = 2L, rawName = "Anna"))
        repair()

        assertEquals(listOf(1L), faces.vectorReads)
        val after = cache()
        assertNull(after[2L])
        assertEquals(4, after.getValue(1L).second)
        for (id in 3L..PEOPLE) assertEquals("person $id", before.getValue(id), after.getValue(id))

        // A rebuild of every person from scratch leaves exactly what the repair did.
        db.clusterSummaryDao().clearForUser(acct)
        repair()
        assertEquals(PEOPLE.toInt() - 1, faces.vectorReads.toSet().size)
        assertEquals(after, cache())
    }

    @Test
    fun `a row whose count drifted and a row of a person gone are repaired alone`() = runBlocking {
        seed()
        repair()
        val before = cache()
        val drifted = db.clusterSummaryDao().getAllForUser(acct).first { it.personId == 7L }
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(7L, acct, drifted.centroid, 1, drifted.modelVersion))
        db.faceDao().rejectAllForPerson(acct, 9L)

        repair()

        assertEquals(listOf(7L), faces.vectorReads)
        val after = cache()
        assertEquals(before.getValue(7L), after.getValue(7L))
        assertNull(after[9L])
        assertEquals(PEOPLE.toInt() - 1, after.size)
    }

    private companion object {
        const val PEOPLE = 3_200L
    }
}
