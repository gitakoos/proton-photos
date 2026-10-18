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
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Proves the auto-merge's sliced read chooses exactly what the whole-table read did: over a real database
 * holding several slices of faces (varied and tied scores, removed and ungrouped faces, unreadable
 * vectors, "not this person" marks, the Unsorted bucket, a blank-named person, another account's rows),
 * the centroids match bit for bit and in the same map order, the rejected pairs match, the chosen folds
 * match, and a full pass leaves two identical databases identical. The reference below is the whole-table
 * pass as it ran before the sliced one, kept here verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoMergePagedLoadDbTest {

    private val acct = "acct"
    private val dbs = ArrayList<AppDatabase>()

    @After
    fun tearDown() = dbs.forEach { it.close() }

    private fun newDb(): AppDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
            .also { dbs += it }
    }

    private fun assignPersonName(db: AppDatabase) = AssignPersonNameUseCase(
        db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
        db.personCoverDao(), db.clusterSummaryDao(), db,
    )

    private fun useCase(db: AppDatabase) =
        AutoMergePeopleUseCase(db.faceDao(), db.personDao(), db.notPersonDao(), assignPersonName(db), db)

    private fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    private fun near(random: Random, center: FloatArray): ByteArray =
        pack(normalize(FloatArray(FACE_EMBEDDING_DIM) { center[it] + (random.nextFloat() - 0.5f) * 0.1f }))

    private fun add(a: FloatArray, b: FloatArray, bWeight: Float) =
        normalize(FloatArray(FACE_EMBEDDING_DIM) { a[it] + b[it] * bWeight })

    /**
     * The same fixture into any database: three named people, look-alike unnamed clusters (one nearest
     * Anna, one nearest Bob, one between Anna and Cleo, one near Anna but marked not-Anna), the Unsorted
     * bucket near Anna, a blank-named person and a distant cluster, over more than two slices of faces.
     */
    private suspend fun seed(db: AppDatabase) {
        val random = Random(11)
        val centers = List(5) { normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextFloat() - 0.5f }) }
        val anna = centers[0]
        val bob = centers[1]
        val cleo = centers[2]
        val far = centers[3]
        val statue = centers[4]
        listOf(
            PersonEntity(id = 1, userId = acct, displayName = "Anna"),
            PersonEntity(id = 2, userId = acct, displayName = "Bob"),
            PersonEntity(id = 3, userId = acct, displayName = "Cleo"),
            PersonEntity(id = 4, userId = acct, displayName = "  "),
            PersonEntity(id = 10, userId = acct),
            PersonEntity(id = 11, userId = acct),
            PersonEntity(id = 12, userId = acct),
            PersonEntity(id = 13, userId = acct),
            PersonEntity(id = 14, userId = acct),
            PersonEntity(id = 15, userId = acct),
            PersonEntity(id = 99, userId = acct, isOther = true),
            PersonEntity(id = 200, userId = "other", displayName = "Anna"),
        ).forEach { db.personDao().insert(it) }

        val plan = listOf(
            1L to anna, 2L to bob, 3L to cleo, 4L to statue,
            10L to anna, 11L to bob, 12L to anna, 13L to add(anna, cleo, 1.5f), 14L to far, 99L to anna,
        )
        val rows = ArrayList<FaceEntity>()
        var g = 0
        for ((personId, center) in plan) {
            repeat(130) {
                val photo = if (g % 3 == 0) "content://media/external/images/media/$g" else "link${(g * 7919) % 10_007}Q-_"
                rows += FaceEntity(
                    id = "$photo#${g % 4}", userId = acct, photoKey = photo,
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                    embedding = if (g % 37 == 0) ByteArray(40) else near(random, center),
                    personId = personId,
                    score = if (g % 5 == 0) 0.8f else random.nextFloat(),
                    rejected = false,
                )
                g++
            }
        }
        // Removed faces still carrying a person, ungrouped faces, and a person with no readable vector.
        for (i in 0 until 60) {
            rows += FaceEntity(
                id = "gone$i#0", userId = acct, photoKey = "gone$i", left = 0f, top = 0f, right = 1f, bottom = 1f,
                landmarks = "", embedding = near(random, far), personId = if (i % 2 == 0) 10L else 11L,
                score = random.nextFloat(), rejected = true,
            )
            rows += FaceEntity(
                id = "loose$i#0", userId = acct, photoKey = "loose$i", left = 0f, top = 0f, right = 1f, bottom = 1f,
                landmarks = "", embedding = near(random, bob), personId = null, score = random.nextFloat(),
            )
        }
        for (i in 0 until 5) {
            rows += FaceEntity(
                id = "stale$i#0", userId = acct, photoKey = "stale$i", left = 0f, top = 0f, right = 1f, bottom = 1f,
                landmarks = "", embedding = ByteArray(64), personId = 15L, score = 0.9f,
            )
        }
        // Tied scores whose ids sort one way by UTF-16 unit and the other by code point.
        val bmpHigh = "u" + Char(0xFFFD)
        val astral = "u" + String(Character.toChars(0x1F600))
        rows += FaceEntity(
            id = "$bmpHigh#0", userId = acct, photoKey = bmpHigh, left = 0f, top = 0f, right = 1f, bottom = 1f,
            landmarks = "", embedding = near(random, anna), personId = 10L, score = 0.8f,
        )
        rows += FaceEntity(
            id = "$astral#0", userId = acct, photoKey = astral, left = 0f, top = 0f, right = 1f,
            bottom = 1f, landmarks = "", embedding = near(random, anna), personId = 10L, score = 0.8f,
        )
        // Another account's faces under this account's person ids never enter its centroids.
        for (i in 0 until 40) {
            rows += FaceEntity(
                id = "theirs$i#0", userId = "other", photoKey = "theirs$i", left = 0f, top = 0f, right = 1f,
                bottom = 1f, landmarks = "", embedding = near(random, far), personId = if (i % 2 == 0) 10L else 200L,
                score = 0.99f,
            )
        }
        db.faceDao().upsert(rows)

        val cluster12 = rows.first { it.personId == 12L && it.userId == acct }.id
        val cluster11 = rows.first { it.personId == 11L && it.userId == acct }.id
        db.notPersonDao().add(
            listOf(
                NotPersonEntity(acct, "Anna", cluster12),
                NotPersonEntity(acct, "Cleo", cluster11),
                NotPersonEntity(acct, "Bob", "stale0#0"),
                NotPersonEntity(acct, "Bob", "gone0#0"),
                NotPersonEntity(acct, "Bob", "loose0#0"),
                NotPersonEntity(acct, "Dora", cluster12),
                NotPersonEntity("other", "Anna", rows.first { it.personId == 10L && it.userId == acct }.id),
            ),
        )
    }

    private class Reference(val centroids: Map<Long, FloatArray>, val rejectedPairs: Set<Pair<Long, Long>>)

    /** The auto-merge's whole-table read, as it ran before the sliced one. */
    private suspend fun wholeTableInput(db: AppDatabase, nameById: Map<Long, String>): Reference {
        val faces = db.faceDao().allFacesByScoreDesc(acct)
        val centroids = centroidsByPerson(faces)
        val faceIdsByPerson = HashMap<Long, MutableSet<String>>()
        for (f in faces) {
            if (f.rejected) continue
            val pid = f.personId ?: continue
            faceIdsByPerson.getOrPut(pid) { HashSet() }.add(f.id)
        }
        val rejectedByName = db.notPersonDao().allForUser(acct).groupBy({ it.personName }, { it.faceId })
        val rejectedPairs = HashSet<Pair<Long, Long>>()
        for ((namedId, name) in nameById) {
            val rejected = rejectedByName[name]?.toHashSet() ?: continue
            for (cid in centroids.keys) {
                if (cid == namedId) continue
                if (faceIdsByPerson[cid]?.any { it in rejected } == true) rejectedPairs.add(namedId to cid)
            }
        }
        return Reference(centroids, rejectedPairs)
    }

    private fun centroidsByPerson(faces: List<FaceEntity>): Map<Long, FloatArray> {
        val sums = HashMap<Long, DoubleArray>()
        for (f in faces) {
            if (f.rejected) continue
            val pid = f.personId ?: continue
            val e = unpackEmbedding(f.embedding)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val acc = sums.getOrPut(pid) { DoubleArray(e.size) }
            for (i in e.indices) acc[i] += e[i]
        }
        val out = HashMap<Long, FloatArray>()
        for ((pid, acc) in sums) {
            var norm = 0.0
            for (v in acc) norm += v * v
            val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
            out[pid] = FloatArray(acc.size) { (acc[it] * inv).toFloat() }
        }
        return out
    }

    /** The whole auto-merge pass as it ran before the sliced one, folding through the same use case. */
    private suspend fun wholeTableAutoMerge(db: AppDatabase): Int {
        val named = db.personDao().namedPeopleForUser(acct).filter { !it.displayName.isNullOrBlank() }
        val nameById = named.associate { it.id to it.displayName!!.trim() }
        val old = wholeTableInput(db, nameById)
        if (old.centroids.isEmpty() || named.isEmpty()) return 0
        val otherId = db.personDao().otherPersonId(acct) ?: -1L
        val pairs = pickAutoMergePairs(
            old.centroids, named.mapTo(HashSet()) { it.id }, otherId, old.rejectedPairs, FACE_SUGGEST_THRESHOLD,
        )
        val assign = assignPersonName(db)
        for (pair in pairs) assign(acct, pair.fromClusterId, nameById[pair.toPersonId] ?: continue)
        return pairs.size
    }

    private suspend fun nameById(db: AppDatabase): Map<Long, String> =
        db.personDao().namedPeopleForUser(acct).filter { !it.displayName.isNullOrBlank() }
            .associate { it.id to it.displayName!!.trim() }

    @Test
    fun `the sliced read gives the whole-table read's centroids, rejected pairs and folds`() = runTest {
        val db = newDb()
        seed(db)
        val nameById = nameById(db)
        val old = wholeTableInput(db, nameById)
        val sliced = useCase(db).readInput(acct, nameById)

        // Each person's vectors are summed in the whole-table read's order, the kept grouped faces of it.
        val links = db.faceDao().keptPersonLinksByScoreDesc(acct)
        assertTrue("the fixture spans several slices", links.size > 1_100)
        assertEquals(
            db.faceDao().allFacesByScoreDesc(acct).filter { !it.rejected && it.personId != null }.map { it.id to it.personId },
            links.map { it.id to it.personId },
        )
        assertEquals(old.centroids.keys.toList(), sliced.centroids.keys.toList())
        for ((pid, c) in old.centroids) {
            assertEquals("centroid $pid", c.map { it.toRawBits() }, sliced.centroids.getValue(pid).map { it.toRawBits() })
        }
        assertEquals(old.rejectedPairs, sliced.rejectedPairs)
        assertEquals(setOf(1L to 12L, 3L to 11L), sliced.rejectedPairs)
        assertTrue("a person with no readable vector has no centroid", 15L !in sliced.centroids)

        val named = nameById.keys
        val oldPairs = pickAutoMergePairs(old.centroids, named, 99L, old.rejectedPairs, FACE_SUGGEST_THRESHOLD)
        val newPairs = pickAutoMergePairs(sliced.centroids, named, 99L, sliced.rejectedPairs, FACE_SUGGEST_THRESHOLD)
        assertEquals(oldPairs, newPairs)
        assertEquals(mapOf(10L to 1L, 11L to 2L, 13L to 3L), newPairs.associate { it.fromClusterId to it.toPersonId })
    }

    @Test
    fun `a full pass leaves the database exactly as the whole-table pass did`() = runTest {
        val before = newDb()
        val after = newDb()
        seed(before)
        seed(after)

        val oldMerged = wholeTableAutoMerge(before)
        val newMerged = useCase(after)(acct)

        assertEquals(3, oldMerged)
        assertEquals(oldMerged, newMerged)
        suspend fun faces(db: AppDatabase) = db.faceDao().allFacesByScoreDesc(acct)
            .map { Triple(it.id, it.personId, it.manualName) to it.rejected }
        assertEquals(faces(before), faces(after))
        assertEquals(before.personDao().allForUser(acct).sortedBy { it.id }, after.personDao().allForUser(acct).sortedBy { it.id })
        assertEquals(
            before.notPersonDao().allForUser(acct).toSet(),
            after.notPersonDao().allForUser(acct).toSet(),
        )
    }

    @Test
    fun `a pass the fence refuses folds nothing`() = runTest {
        val refused = newDb()
        val untouched = newDb()
        seed(refused)
        seed(untouched)

        assertEquals(0, useCase(refused)(acct) { false })

        suspend fun faces(db: AppDatabase) = db.faceDao().allFacesByScoreDesc(acct)
            .map { Triple(it.id, it.personId, it.manualName) to it.rejected }
        assertEquals(faces(untouched), faces(refused))
        assertEquals(
            untouched.personDao().allForUser(acct).sortedBy { it.id },
            refused.personDao().allForUser(acct).sortedBy { it.id },
        )
    }

    @Test
    fun `nothing folds while no person is named`() = runTest {
        val db = newDb()
        db.personDao().insert(PersonEntity(id = 10, userId = acct))
        val v = pack(normalize(FloatArray(FACE_EMBEDDING_DIM) { 1f }))
        db.faceDao().upsert(
            FaceEntity(
                id = "p#0", userId = acct, photoKey = "p", left = 0f, top = 0f, right = 1f, bottom = 1f,
                landmarks = "", embedding = v, personId = 10L, score = 0.9f,
            ),
        )
        assertEquals(0, useCase(db)(acct))
    }
}
