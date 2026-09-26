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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Runs the person page's centroid source against a real database: a person whose cached centroid still
 * counts its kept faces is read from the cache, one whose cache drifted or is missing is summed from its
 * own faces (across several pages, removed faces left out), the compared person is always summed, and only
 * the people asked for are read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonCentroidsDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase
    private lateinit var centroids: PersonCentroids
    private val random = Random(3)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        centroids = PersonCentroids(db.faceDao(), db.clusterSummaryDao())
    }

    @After
    fun tearDown() = db.close()

    private fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    private fun unit() = normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextFloat() - 0.5f })

    /** Seeds [count] kept faces for [personId] plus one removed face, and returns the kept faces' vectors. */
    private suspend fun seedPerson(personId: Long, count: Int): List<ByteArray> {
        val vectors = List(count) { pack(unit()) }
        db.faceDao().upsert(
            vectors.mapIndexed { i, v ->
                FaceEntity(
                    id = "p$personId-${i.toString().padStart(4, '0')}#0", userId = acct, photoKey = "p$personId-$i",
                    left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                    embedding = v, personId = personId, score = 0.9f,
                )
            } + FaceEntity(
                id = "p$personId-removed#0", userId = acct, photoKey = "p$personId-removed",
                left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                embedding = pack(unit()), personId = personId, score = 0.9f, rejected = true,
            ),
        )
        return vectors
    }

    private suspend fun cache(personId: Long, centroid: FloatArray, members: Int) =
        db.clusterSummaryDao().upsert(
            ClusterSummaryEntity(personId, acct, pack(centroid), members, FACE_MODEL_VERSION),
        )

    private fun summed(vectors: List<ByteArray>): FloatArray = CentroidSum().apply { vectors.forEach { add(it) } }.centroid()!!

    @Test
    fun `a current cache is read, a drifted or missing one is summed from the person's own faces`() = runTest {
        seedPerson(1L, 3)
        val b = seedPerson(2L, 1_200)
        val c = seedPerson(3L, 4)
        val cachedA = unit()
        cache(1L, cachedA, members = 3)
        cache(2L, unit(), members = 1_199)

        val out = centroids.load(acct, exactFor = null) { true }

        assertEquals(setOf(1L, 2L, 3L), out.keys)
        assertArrayEquals(cachedA, out[1L], 0f)
        assertArrayEquals(summed(b), out[2L], 0f)
        assertArrayEquals(summed(c), out[3L], 0f)
    }

    @Test
    fun `the compared person is summed even when its cache is current, and only wanted people are read`() = runTest {
        val a = seedPerson(1L, 5)
        seedPerson(2L, 2)
        cache(1L, unit(), members = 5)

        val out = centroids.load(acct, exactFor = 1L) { it == 1L }

        assertEquals(setOf(1L), out.keys)
        assertArrayEquals(summed(a), out[1L], 0f)
    }

    @Test
    fun `another account's people are never read`() = runTest {
        seedPerson(1L, 2)
        assertEquals(emptyMap<Long, FloatArray>(), centroids.load("other", exactFor = null) { true })
    }
}
