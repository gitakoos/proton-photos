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
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.batchedRunDue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
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
 * How a library too big for the whole-library rebuild gets its people: the batched placement opens the
 * first people itself when none exist yet, rebuilds a missing or drifted centroid cache from the existing
 * assignments instead of refusing, and keeps the cached photo count and cover of every person it touches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterFacesBootstrapDbTest {

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

    /** A unit vector on [axis], nudged a little along one of twenty side axes, so faces of one identity sit
     *  near 0.99 cosine and faces of different identities (different axes) at 0. */
    private fun embedding(axis: Int, nudge: Int = 0): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[axis] = 1f
        v[200 + nudge % 20] = 0.1f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun face(photo: String, index: Int, personId: Long?, axis: Int, nudge: Int = 0, score: Float = 0.9f) =
        db.faceDao().upsert(
            FaceEntity(
                id = "$photo#$index", userId = acct, photoKey = photo,
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = embedding(axis, nudge), personId = personId, score = score,
            ),
        )

    /** Three identities of six clear faces each (two faces per photo) and four weak faces matching nobody. */
    private suspend fun seedLibrary() {
        for (identity in 0 until 3) {
            for (i in 0 until 6) face("p$identity-${i / 2}", i % 2, null, axis = identity, nudge = i)
        }
        for (i in 0 until 4) face("w$i", 0, null, axis = 300 + i, score = 0.3f)
    }

    private suspend fun runBatches(limit: Int): IncrementalBatch {
        val batch = IncrementalBatch(limit)
        do {
            batch.scanned = 0
            assertTrue(cluster.assignNewFaces(user, batch = batch))
        } while (batch.scanned == batch.limit)
        return batch
    }

    @Test
    fun `an empty cache over ungrouped faces is bootstrapped into people by the batches`() = runTest {
        seedLibrary()
        assertEquals(0, db.clusterSummaryDao().countForUser(acct))

        val batch = runBatches(limit = 5)

        val people = db.personDao().allForUser(acct)
        assertEquals(3, people.size)
        assertEquals(18, batch.placed)
        assertEquals(3, batch.created)
        assertEquals(4, db.faceDao().unclusteredCount(acct))
        val summaries = db.clusterSummaryDao().getAllForUser(acct)
        assertEquals(3, summaries.size)
        assertEquals(db.faceDao().assignedFaceCount(acct), summaries.sumOf { it.memberCount })
    }

    @Test
    fun `a person the batches open carries its photo count and a cover face`() = runTest {
        seedLibrary()

        runBatches(limit = 100)

        for (person in db.personDao().allForUser(acct)) {
            assertEquals(3, person.faceCount)
            val cover = person.coverFaceId
            assertNotNull(cover)
            assertEquals(person.id, db.faceDao().faceById(cover!!)!!.personId)
        }
    }

    @Test
    fun `a missing cache over existing people is rebuilt from the assignments and then used`() = runTest {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, faceCount = 2))
        face("a0", 0, 1L, axis = 0)
        face("a1", 0, 1L, axis = 0, nudge = 1)
        face("n0", 0, null, axis = 0, nudge = 2)
        assertEquals(0, db.clusterSummaryDao().countForUser(acct))

        val batch = runBatches(limit = 10)

        assertEquals(1L, db.faceDao().faceById("n0#0")!!.personId)
        assertEquals(1, batch.placed)
        val summaries = db.clusterSummaryDao().getAllForUser(acct)
        assertEquals(1, summaries.size)
        assertEquals(3, summaries.single().memberCount)
    }

    @Test
    fun `a drifted cache is rebuilt rather than refused`() = runTest {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, faceCount = 2))
        face("a0", 0, 1L, axis = 0)
        face("a1", 0, 1L, axis = 0, nudge = 1)
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, embedding(axis = 0), 1, FACE_MODEL_VERSION))
        face("n0", 0, null, axis = 0, nudge = 2)

        val batch = runBatches(limit = 10)

        assertEquals(1L, db.faceDao().faceById("n0#0")!!.personId)
        assertEquals(1, batch.placed)
        assertEquals(3, db.clusterSummaryDao().getAllForUser(acct).single().memberCount)
    }

    @Test
    fun `an unnamed person that gains faces on new photos gets its photo count recounted`() = runTest {
        db.personDao().upsert(PersonEntity(id = 1L, userId = acct, faceCount = 2))
        face("a0", 0, 1L, axis = 0)
        face("a1", 0, 1L, axis = 0, nudge = 1)
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, embedding(axis = 0), 2, FACE_MODEL_VERSION))
        face("n0", 0, null, axis = 0, nudge = 2)
        face("n1", 0, null, axis = 0, nudge = 3)

        runBatches(limit = 10)

        assertEquals(4, db.personDao().personById(1L)!!.faceCount)
    }

    private suspend fun personOf(faceId: String): Long = db.faceDao().faceById(faceId)!!.personId!!

    @Test
    fun `a group whose faces carry a confirmed name takes that name`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1", "p0-1#0"), "Anna")

        assertEquals(1, cluster.nameGroupsFromConfirmedFaces(user))

        assertEquals("Anna", db.personDao().personById(personOf("p0-0#0"))!!.displayName)
        assertEquals(null, db.personDao().personById(personOf("p1-0#0"))!!.displayName)
    }

    @Test
    fun `the name most of a group's faces carry wins`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1"), "Anna")
        db.faceDao().labelFacesByIds(listOf("p0-1#0"), "Ben")

        cluster.nameGroupsFromConfirmedFaces(user)

        assertEquals("Anna", db.personDao().personById(personOf("p0-0#0"))!!.displayName)
    }

    @Test
    fun `a name a person already holds is never put on a second group`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        val other = personOf("p1-0#0")
        db.personDao().updateName(other, "Anna")
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1"), "Anna")

        assertEquals(0, cluster.nameGroupsFromConfirmedFaces(user))

        assertEquals(null, db.personDao().personById(personOf("p0-0#0"))!!.displayName)
    }

    @Test
    fun `a person left with a name and no faces gives the name to the group that carries it`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        val leftBehind = 9_000L
        db.personDao().upsert(PersonEntity(id = leftBehind, userId = acct, displayName = "Anna", faceCount = 5))
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1"), "Anna")

        assertEquals(1, cluster.nameGroupsFromConfirmedFaces(user))

        assertEquals("Anna", db.personDao().personById(personOf("p0-0#0"))!!.displayName)
        assertEquals(null, db.personDao().personById(leftBehind)!!.displayName)
    }

    @Test
    fun `a recount sets an unnamed group's photo count from the faces it holds`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        val group = personOf("p0-0#0")
        val row = db.personDao().personById(group)!!
        db.personDao().updateCoverAndCount(group, row.coverFaceId, 0)

        assertEquals(3, cluster.recountPhotoCounts(user))

        assertEquals(3, db.personDao().personById(group)!!.faceCount)
    }

    @Test
    fun `a recount of a named person adds the photos put on the name by hand`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1", "p0-1#0"), "Anna")
        cluster.nameGroupsFromConfirmedFaces(user)
        val group = personOf("p0-0#0")
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", "hand-1")))
        db.personDao().updateCoverAndCount(group, db.personDao().personById(group)!!.coverFaceId, 0)

        cluster.recountPhotoCounts(user)

        assertEquals(4, db.personDao().personById(group)!!.faceCount)
    }

    @Test
    fun `a write check that says no leaves the photo counts as they were`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        val group = personOf("p0-0#0")
        db.personDao().updateCoverAndCount(group, db.personDao().personById(group)!!.coverFaceId, 0)

        cluster.recountPhotoCounts(user) { false }

        assertEquals(0, db.personDao().personById(group)!!.faceCount)
    }

    @Test
    fun `of two groups carrying the same name the one with more named faces claims it`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1", "p0-1#0"), "Anna")
        db.faceDao().labelFacesByIds(listOf("p1-0#0"), "Anna")

        assertEquals(1, cluster.nameGroupsFromConfirmedFaces(user))

        assertEquals("Anna", db.personDao().personById(personOf("p0-0#0"))!!.displayName)
        assertEquals(null, db.personDao().personById(personOf("p1-0#0"))!!.displayName)
    }

    @Test
    fun `a write check that says no names nothing`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        db.faceDao().labelFacesByIds(listOf("p0-0#0", "p0-0#1"), "Anna")

        assertEquals(0, cluster.nameGroupsFromConfirmedFaces(user) { false })

        assertEquals(null, db.personDao().personById(personOf("p0-0#0"))!!.displayName)
    }

    @Test
    fun `matching in slices gives the assignment one call gives`() = runTest {
        val rnd = java.util.Random(5)
        fun unit(): FloatArray = normalize(FloatArray(FACE_EMBEDDING_DIM) { rnd.nextGaussian().toFloat() })
        val centroids = List(260) { unit() }
        val samples = List(640) { i ->
            val base = centroids[i % centroids.size]
            val noisy = FloatArray(FACE_EMBEDDING_DIM) { base[it] + 0.03f * rnd.nextGaussian().toFloat() }
            FaceSample(normalize(noisy), score = 0.9f, confident = i % 5 != 0)
        }

        val whole = assignIncremental(samples, centroids)
        val sliced = assignIncrementalParallel(samples, centroids)

        assertTrue(whole.any { it >= 0 })
        assertTrue(whole.contentEquals(sliced))
    }

    /** A unit vector [degrees] round in the plane of the first two axes, nudged along a side axis, so faces
     *  at the same angle sit near 0.99 cosine and faces a known angle apart at the cosine of that angle. */
    private fun embeddingAt(degrees: Double, nudge: Int = 0): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[0] = Math.cos(Math.toRadians(degrees)).toFloat()
        v[1] = Math.sin(Math.toRadians(degrees)).toFloat()
        v[200 + nudge % 20] = 0.05f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun groupAt(personId: Long, degrees: Double, faces: Int, name: String? = null, other: Boolean = false) {
        db.personDao().upsert(PersonEntity(id = personId, userId = acct, displayName = name, faceCount = faces, isOther = other))
        for (i in 0 until faces) {
            db.faceDao().upsert(
                FaceEntity(
                    id = "g$personId-$i#0", userId = acct, photoKey = "g$personId-$i",
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = embeddingAt(degrees, i), personId = personId, score = 0.9f,
                ),
            )
        }
    }

    /** Builds the centroid cache from the assignments, the way a batched run's first call does. */
    private suspend fun cacheTheGroups() {
        assertTrue(cluster.assignNewFaces(user, IncrementalBatch(limit = 10)))
    }

    @Test
    fun `two groups of one person are merged and the bigger one survives`() = runTest {
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        cacheTheGroups()

        assertEquals(1, cluster.mergeSimilarGroups(user))

        assertEquals(listOf(1L), db.personDao().allForUser(acct).map { it.id })
        assertEquals(6, db.faceDao().faceCountForPerson(acct, 1L))
        assertEquals(6, db.personDao().personById(1L)!!.faceCount)
        val summaries = db.clusterSummaryDao().getAllForUser(acct)
        assertEquals(1, summaries.size)
        assertEquals(6, summaries.single().memberCount)
    }

    @Test
    fun `groups of different people stay apart`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        groupAt(2L, 90.0, faces = 3)
        cacheTheGroups()

        assertEquals(0, cluster.mergeSimilarGroups(user))

        assertEquals(2, db.personDao().countForUser(acct))
    }

    @Test
    fun `two groups carrying different names never merge`() = runTest {
        groupAt(1L, 0.0, faces = 3, name = "Anna")
        groupAt(2L, 3.0, faces = 3, name = "Ben")
        cacheTheGroups()

        assertEquals(0, cluster.mergeSimilarGroups(user))

        assertEquals(2, db.personDao().countForUser(acct))
    }

    @Test
    fun `a named group outlives a bigger unnamed one`() = runTest {
        groupAt(1L, 0.0, faces = 5)
        groupAt(2L, 3.0, faces = 2, name = "Anna")
        cacheTheGroups()

        assertEquals(1, cluster.mergeSimilarGroups(user))

        val left = db.personDao().allForUser(acct).single()
        assertEquals(2L, left.id)
        assertEquals("Anna", left.displayName)
        assertEquals(7, db.faceDao().faceCountForPerson(acct, 2L))
    }

    @Test
    fun `two groups holding the same name are merged`() = runTest {
        groupAt(1L, 0.0, faces = 3, name = "Anna")
        groupAt(2L, 3.0, faces = 2, name = "Anna")
        cacheTheGroups()

        assertEquals(1, cluster.mergeSimilarGroups(user))

        assertEquals("Anna", db.personDao().allForUser(acct).single().displayName)
    }

    @Test
    fun `the Unsorted bucket takes no part in a merge`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        groupAt(2L, 3.0, faces = 3, other = true)
        cacheTheGroups()

        assertEquals(0, cluster.mergeSimilarGroups(user))

        assertEquals(2, db.personDao().countForUser(acct))
    }

    @Test
    fun `a chain of near groups does not fuse the people at its ends`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        groupAt(2L, 50.0, faces = 10)
        groupAt(3L, 100.0, faces = 3)
        cacheTheGroups()

        assertEquals(1, cluster.mergeSimilarGroups(user))

        assertEquals(2, db.personDao().countForUser(acct))
        assertEquals(db.faceDao().assignedFaceCount(acct), db.clusterSummaryDao().getAllForUser(acct).sumOf { it.memberCount })
    }

    @Test
    fun `a write check that says no merges nothing`() = runTest {
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        cacheTheGroups()

        assertEquals(0, cluster.mergeSimilarGroups(user, { false }))

        assertEquals(2, db.personDao().countForUser(acct))
    }

    @Test
    fun `the faces nobody took go to one Unsorted bucket that the cache accounts for`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        assertEquals(4, db.faceDao().unclusteredCount(acct))

        assertEquals(4, cluster.moveLeftoverToUnsorted(user))

        assertEquals(0, db.faceDao().unclusteredCount(acct))
        val other = db.personDao().otherPersonId(acct)!!
        assertEquals(4, db.faceDao().faceCountForPerson(acct, other))
        assertEquals(4, db.personDao().personById(other)!!.faceCount)
        assertNotNull(db.personDao().personById(other)!!.coverFaceId)
        assertEquals(db.faceDao().assignedFaceCount(acct), db.clusterSummaryDao().getAllForUser(acct).sumOf { it.memberCount })
        assertEquals(0, cluster.moveLeftoverToUnsorted(user))
    }

    @Test
    fun `a second pass reuses the Unsorted bucket and a face in it is never matched`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        cluster.moveLeftoverToUnsorted(user)
        val other = db.personDao().otherPersonId(acct)!!
        face("late", 0, null, axis = 400, score = 0.3f)

        assertEquals(1, cluster.moveLeftoverToUnsorted(user))

        assertEquals(other, db.personDao().otherPersonId(acct))
        assertEquals(5, db.faceDao().faceCountForPerson(acct, other))
        assertEquals(db.faceDao().assignedFaceCount(acct), db.clusterSummaryDao().getAllForUser(acct).sumOf { it.memberCount })
    }

    @Test
    fun `a confirmed name on a face in the Unsorted bucket names nothing`() = runTest {
        seedLibrary()
        runBatches(limit = 100)
        cluster.moveLeftoverToUnsorted(user)
        db.faceDao().labelFacesByIds(listOf("w0#0", "w1#0"), "Anna")

        assertEquals(0, cluster.nameGroupsFromConfirmedFaces(user))
    }

    @Test
    fun `a batch call with no cache and no people still refuses the unbatched path`() = runTest {
        seedLibrary()

        assertEquals(false, cluster.assignNewFaces(user))
        assertEquals(0, db.personDao().countForUser(acct))
    }

    private suspend fun assertCacheDescribesGroups() {
        assertEquals(db.faceDao().assignedFaceCount(acct), db.clusterSummaryDao().getAllForUser(acct).sumOf { it.memberCount })
    }

    @Test
    fun `a group named after the merge was planned is not absorbed`() = runTest {
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        cacheTheGroups()
        var renamed = false

        // The plan is made on the people as read; the user names the smaller group before the merge writes.
        val folded = cluster.mergeSimilarGroups(user, onTick = {
            if (!renamed) {
                renamed = true
                runBlocking { db.personDao().updateName(2L, "Ben") }
            }
        })

        assertEquals(0, folded)
        assertEquals(setOf(1L, 2L), db.personDao().allForUser(acct).map { it.id }.toSet())
        assertEquals("Ben", db.personDao().personById(2L)!!.displayName)
        assertEquals(2, db.faceDao().faceCountForPerson(acct, 2L))
        assertEquals(4, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 1L }.memberCount)
        assertCacheDescribesGroups()
    }

    @Test
    fun `a face marked as not being the named person keeps its group out of that person`() = runTest {
        groupAt(1L, 0.0, faces = 3, name = "Anna")
        groupAt(2L, 3.0, faces = 2)
        db.notPersonDao().add(listOf(NotPersonEntity(userId = acct, personName = "Anna", faceId = "g2-0#0")))
        cacheTheGroups()

        assertEquals(0, cluster.mergeSimilarGroups(user))

        assertEquals(2, db.personDao().countForUser(acct))
        assertEquals(2, db.faceDao().faceCountForPerson(acct, 2L))
    }

    @Test
    fun `a not-this-person mark made while the merge runs still blocks it`() = runTest {
        groupAt(1L, 0.0, faces = 3, name = "Anna")
        groupAt(2L, 3.0, faces = 2)
        cacheTheGroups()
        var marked = false

        val folded = cluster.mergeSimilarGroups(user, onTick = {
            if (!marked) {
                marked = true
                runBlocking { db.notPersonDao().add(listOf(NotPersonEntity(userId = acct, personName = "Anna", faceId = "g2-1#0"))) }
            }
        })

        assertEquals(0, folded)
        assertEquals(2, db.personDao().countForUser(acct))
        assertEquals(3, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 1L }.memberCount)
        assertCacheDescribesGroups()
    }

    @Test
    fun `a survivor keeps only the groups it really took in`() = runTest {
        groupAt(1L, 0.0, faces = 5)
        groupAt(2L, 2.0, faces = 2)
        groupAt(3L, 4.0, faces = 2)
        cacheTheGroups()
        var renamed = false

        val folded = cluster.mergeSimilarGroups(user, onTick = {
            if (!renamed) {
                renamed = true
                runBlocking { db.personDao().updateName(3L, "Cleo") }
            }
        })

        assertEquals(1, folded)
        assertEquals(setOf(1L, 3L), db.personDao().allForUser(acct).map { it.id }.toSet())
        assertEquals(7, db.faceDao().faceCountForPerson(acct, 1L))
        assertEquals(7, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 1L }.memberCount)
        assertEquals(2, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 3L }.memberCount)
        assertCacheDescribesGroups()
    }

    @Test
    fun `a merge over a drifted cache rebuilds it first`() = runTest {
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, embeddingAt(0.0), 1, FACE_MODEL_VERSION))

        assertEquals(1, cluster.mergeSimilarGroups(user))

        assertEquals(6, db.faceDao().faceCountForPerson(acct, 1L))
        assertCacheDescribesGroups()
    }

    /**
     * A batched run's steps in the order the scheduler takes them: the batches, the names, then (only for a
     * run that ends a walk) the merge, the names again and the Unsorted bucket.
     */
    private suspend fun batchedRun(finalPass: Boolean): Int {
        runBatches(limit = 10)
        cluster.nameGroupsFromConfirmedFaces(user)
        if (!finalPass) return 0
        val merged = cluster.mergeSimilarGroups(user)
        cluster.nameGroupsFromConfirmedFaces(user)
        cluster.moveLeftoverToUnsorted(user)
        return merged
    }

    @Test
    fun `a final run with no new faces still merges and fills Unsorted after a run during the scan`() = runTest {
        // Two groups of one person the batches left apart, and weak faces nobody takes.
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        for (i in 0 until 3) face("w$i", 0, null, axis = 300 + i, score = 0.3f)
        assertEquals(0, batchedRun(finalPass = false))
        val leftover = db.faceDao().unclusteredCount(acct)
        assertEquals(3, leftover)
        assertEquals(2, db.personDao().countForUser(acct))

        // Nothing new since: the run that ends the walk is due only because the closing steps are owed.
        assertEquals(false, batchedRunDue(leftover, baseline = leftover, finalizeOwed = false, finalPass = true))
        assertTrue(batchedRunDue(leftover, baseline = leftover, finalizeOwed = true, finalPass = true))
        assertEquals(1, batchedRun(finalPass = true))

        assertEquals(0, db.faceDao().unclusteredCount(acct))
        val other = db.personDao().otherPersonId(acct)!!
        assertEquals(3, db.faceDao().faceCountForPerson(acct, other))
        assertEquals(6, db.faceDao().faceCountForPerson(acct, 1L))
        assertNull(db.personDao().personById(2L))
        assertCacheDescribesGroups()
    }

    @Test
    fun `two groups carrying different confirmed names are named before the merge and stay apart`() = runTest {
        groupAt(1L, 0.0, faces = 4)
        groupAt(2L, 3.0, faces = 2)
        db.faceDao().labelFacesByIds(listOf("g1-0#0", "g1-1#0"), "Anna")
        db.faceDao().labelFacesByIds(listOf("g2-0#0", "g2-1#0"), "Ben")

        assertEquals(0, batchedRun(finalPass = true))

        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
        assertEquals("Ben", db.personDao().personById(2L)!!.displayName)
    }

    @Test
    fun `faces left under a removed person are put back among the ungrouped`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        face("o0", 0, 99L, axis = 0)
        face("o1", 0, 99L, axis = 0, nudge = 1)
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(99L, acct, embeddingAt(0.0), 2, FACE_MODEL_VERSION))

        assertEquals(2, cluster.releaseOrphanedFaces(user))

        assertNull(db.faceDao().faceById("o0#0")!!.personId)
        assertNull(db.faceDao().faceById("o1#0")!!.personId)
        assertEquals(1L, db.faceDao().faceById("g1-0#0")!!.personId)
        assertTrue(db.clusterSummaryDao().getAllForUser(acct).none { it.personId == 99L })
        assertEquals(0, cluster.releaseOrphanedFaces(user))
        assertEquals(0, cluster.releaseOrphanedFaces(user) { false })
    }

    private suspend fun newFace(photo: String, degrees: Double, nudge: Int) = db.faceDao().upsert(
        FaceEntity(
            id = "$photo#0", userId = acct, photoKey = photo, left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            landmarks = "", embedding = embeddingAt(degrees, nudge), personId = null, score = 0.9f,
        ),
    )

    @Test
    fun `a batch never puts a face on a person removed since the people were read`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        groupAt(2L, 90.0, faces = 3)
        val batch = IncrementalBatch(limit = 10)
        // The first call reads the people and keeps them for the run.
        assertTrue(cluster.assignNewFaces(user, batch))
        assertEquals(2, batch.centroidCount)
        // A curation edit folds person 1 away without the run seeing it.
        db.personDao().deleteByIds(listOf(1L))
        newFace("n0", 0.0, 5)
        newFace("n1", 90.0, 5)

        batch.scanned = 0
        assertTrue(cluster.assignNewFaces(user, batch))

        assertNull(db.faceDao().faceById("n0#0")!!.personId)
        assertEquals(2L, db.faceDao().faceById("n1#0")!!.personId)
        assertEquals(1, batch.placed)
        assertEquals(3, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 1L }.memberCount)
        assertEquals(4, db.clusterSummaryDao().getAllForUser(acct).single { it.personId == 2L }.memberCount)
    }

    @Test
    fun `the people kept between batches follow what each batch wrote`() = runTest {
        groupAt(1L, 0.0, faces = 3)
        val batch = IncrementalBatch(limit = 1)
        assertTrue(cluster.assignNewFaces(user, batch))
        for (i in 0 until 3) newFace("n$i", 0.0, 6 + i)
        do {
            batch.scanned = 0
            assertTrue(cluster.assignNewFaces(user, batch))
        } while (batch.scanned == batch.limit)

        assertEquals(6, db.faceDao().faceCountForPerson(acct, 1L))
        assertEquals(6, db.clusterSummaryDao().getAllForUser(acct).single().memberCount)
        assertCacheDescribesGroups()
        // An edit the batches did not make (a summary dropped) is seen, and the cache is rebuilt for the next one.
        db.clusterSummaryDao().deleteByPerson(1L)
        newFace("n9", 0.0, 12)
        batch.scanned = 0
        assertTrue(cluster.assignNewFaces(user, batch))
        assertEquals(1L, db.faceDao().faceById("n9#0")!!.personId)
        assertEquals(7, db.clusterSummaryDao().getAllForUser(acct).single().memberCount)
        assertCacheDescribesGroups()
    }
}
