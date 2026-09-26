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
import eu.akoos.photos.data.face.isConfidentFace
import eu.akoos.photos.data.face.sidewaysFromEncoded
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Proves the full regroup's paged read hands the clusterer exactly what the whole-table read did: over a
 * real database holding several pages of faces (tied scores, ids whose UTF-16 and UTF-8 orders differ,
 * removed faces, unreadable vectors, confirmed and blocked names, weak crops, another account's rows),
 * the paged load yields the same faces in the same order with bit-identical samples, and the clusterer
 * groups both inputs identically. The reference below is the whole-table read the regroup ran before the
 * paged one, kept here verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegroupPagedLoadDbTest {

    private val acct = "acct"
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

    private fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    /** A face near one of a few identities, so the clusterer forms real groups out of the fixture. */
    private fun nearIdentity(random: Random, centers: List<FloatArray>, identity: Int): ByteArray {
        val c = centers[identity]
        return pack(normalize(FloatArray(FACE_EMBEDDING_DIM) { c[it] + (random.nextFloat() - 0.5f) * 0.03f }))
    }

    private fun face(
        id: String,
        user: String = acct,
        score: Float,
        embedding: ByteArray,
        personId: Long? = null,
        rejected: Boolean = false,
        manualName: String? = null,
        blur: Float? = null,
        landmarks: String = "",
    ) = FaceEntity(
        id = id, userId = user, photoKey = id.substringBefore('#'),
        left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = landmarks,
        embedding = embedding, personId = personId, score = score, blur = blur,
        rejected = rejected, manualName = manualName,
    )

    /** Several pages of faces covering every branch of the per-face grading and ordering. */
    private suspend fun seed(): Map<String, Set<String>> {
        val random = Random(42)
        val centers = List(6) { normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextFloat() - 0.5f }) }
        val scores = floatArrayOf(0.95f, 0.8f, 0.8f, 0.7f, 0.62f, 0.61f, 0.5f, 0.5f)
        val frontal = "10,10;30,10;20,20;12,30;28,30"
        val turned = "10,10;30,10;29,20;12,30;28,30"
        val rows = ArrayList<FaceEntity>()
        for (i in 0 until 1_100) {
            val photo = if (i % 3 == 0) "content://media/external/images/media/$i" else "link${(i * 7919) % 1_100}AbC-_"
            rows += face(
                id = "$photo#${i % 4}",
                score = if (i % 5 == 0) scores[i % scores.size] else random.nextFloat(),
                embedding = when (i % 41) {
                    0 -> ByteArray(0)
                    1 -> ByteArray(40)
                    else -> nearIdentity(random, centers, i % centers.size)
                },
                personId = if (i % 4 == 0) null else (i % 7).toLong(),
                rejected = i % 17 == 0,
                manualName = when (i % 23) {
                    0 -> "Anna"
                    1 -> " "
                    2 -> "Bob"
                    else -> null
                },
                blur = when (i % 3) {
                    0 -> null
                    1 -> 5f
                    else -> 40f
                },
                landmarks = when (i % 4) {
                    0 -> ""
                    1 -> frontal
                    2 -> turned
                    else -> "1,2;3,4"
                },
            )
        }
        // Equal scores whose ids sort one way by UTF-16 unit and the other by code point (SQLite's order).
        val tie = nearIdentity(random, centers, 0)
        rows += face(id = "u�#0", score = 0.77f, embedding = tie)
        rows += face(id = "u😀#0", score = 0.77f, embedding = tie)
        rows += face(id = "Z#0", score = 0.77f, embedding = tie)
        rows += face(id = "a#10", score = 0.77f, embedding = tie)
        rows += face(id = "a#2", score = 0.77f, embedding = tie)
        rows += face(id = "zero#0", score = 0f, embedding = tie)
        rows += face(id = "negzero#0", score = -0f, embedding = tie)
        // Another account's faces never enter this account's regroup.
        for (i in 0 until 30) rows += face(id = "other$i#0", user = "other", score = 0.99f, embedding = tie)
        db.faceDao().upsert(rows)

        val blocked = rows.filter { it.userId == acct && it.manualName == "Anna" }.take(5).map { it.id } +
            rows.filter { it.userId == acct }.filterIndexed { i, _ -> i % 29 == 0 }.map { it.id }
        db.notPersonDao().add(blocked.distinct().map { NotPersonEntity(acct, "Anna", it) })
        return db.notPersonDao().allForUser(acct)
            .groupBy({ it.faceId }, { it.personName }).mapValues { it.value.toHashSet() }
    }

    private class Reference(
        val ids: List<String>,
        val oldPersonIds: List<Long?>,
        val manualNames: List<String?>,
        val blockedNames: List<Set<String>>,
        val samples: List<FaceSample>,
        val photoKeyById: Map<String, String>,
        val placeableFaces: Int,
    )

    /** The regroup's whole-table read, as it ran before the paged one. */
    private suspend fun wholeTableInput(notPersonByFace: Map<String, Set<String>>): Reference {
        val faces = db.faceDao().allFacesByScoreDesc(acct)
        val placeableFaces = faces.count { !it.rejected }
        val photoKeyById = faces.associate { it.id to it.photoKey }
        val ids = ArrayList<String>(faces.size)
        val oldPersonIds = ArrayList<Long?>(faces.size)
        val manualNames = ArrayList<String?>(faces.size)
        val blockedNames = ArrayList<Set<String>>(faces.size)
        val samples = ArrayList<FaceSample>(faces.size)
        for (face in faces) {
            if (face.rejected) continue
            val embedding = unpackEmbedding(face.embedding)
            if (embedding.size != FACE_EMBEDDING_DIM) continue
            val confident = isConfidentFace(
                detectionScore = face.score,
                blur = face.blur?.toDouble(),
                sideways = sidewaysFromEncoded(face.landmarks),
            )
            ids.add(face.id)
            oldPersonIds.add(face.personId)
            val blocked = notPersonByFace[face.id] ?: emptySet()
            blockedNames.add(blocked)
            val confirmedName = face.manualName?.takeIf { it.isNotBlank() && it !in blocked }
            manualNames.add(confirmedName)
            samples.add(FaceSample(embedding, face.score, confident, confirmed = confirmedName != null))
        }
        return Reference(ids, oldPersonIds, manualNames, blockedNames, samples, photoKeyById, placeableFaces)
    }

    @Test
    fun `the paged read gives the clusterer exactly the whole-table read's input`() = runTest {
        val notPersonByFace = seed()
        val old = wholeTableInput(notPersonByFace)
        val paged = cluster.loadRegroupInput(acct, notPersonByFace)

        assertTrue("the fixture spans several pages", old.ids.size > 800)
        assertEquals(old.placeableFaces, paged.placeableFaces)
        assertEquals(old.ids, paged.ids)
        assertEquals(old.oldPersonIds, paged.oldPersonIds)
        assertEquals(old.manualNames, paged.manualNames)
        assertEquals(old.blockedNames, paged.blockedNames)
        assertEquals(old.samples.size, paged.samples.size)
        for (i in old.samples.indices) {
            val a = old.samples[i]
            val b = paged.samples[i]
            assertArrayEquals("embedding ${old.ids[i]}", a.embedding, b.embedding, 0f)
            assertEquals("score ${old.ids[i]}", a.score.toRawBits(), b.score.toRawBits())
            assertEquals("confident ${old.ids[i]}", a.confident, b.confident)
            assertEquals("confirmed ${old.ids[i]}", a.confirmed, b.confirmed)
        }
        for (id in old.ids) assertEquals(old.photoKeyById[id], paged.photoKeyById[id])
        assertEquals(old.ids.toSet(), paged.photoKeyById.keys)

        // The fixture exercises what it claims to: groups, confirmations, blocks and weak crops.
        assertTrue(paged.samples.any { it.confirmed } && paged.samples.any { !it.confident })
        assertTrue(paged.blockedNames.any { it.isNotEmpty() })
        val codePointFirst = paged.ids.indexOf("u�#0")
        assertTrue(codePointFirst in 0 until paged.ids.indexOf("u😀#0"))

        val oldGroups = clusterFaces(old.samples)
        val pagedGroups = clusterFaces(paged.samples)
        assertTrue("the fixture forms real groups", oldGroups.toSet().size > 2)
        assertArrayEquals(oldGroups, pagedGroups)
    }

    @Test
    fun `a library that fills its pages exactly still reads every face once`() = runTest {
        val random = Random(7)
        val v = pack(normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextFloat() - 0.5f }))
        db.faceDao().upsert((0 until 800).map { face(id = "p${it.toString().padStart(4, '0')}#0", score = 0.9f, embedding = v) })

        val paged = cluster.loadRegroupInput(acct, emptyMap())

        assertEquals(800, paged.placeableFaces)
        assertEquals((0 until 800).map { "p${it.toString().padStart(4, '0')}#0" }, paged.ids)
    }

    @Test
    fun `ids order by code point, the byte order of their UTF-8 text`() {
        assertTrue(compareCodePoints("u�", "u😀") < 0)
        assertTrue("UTF-16 units order them the other way", "u�" > "u😀")
        assertTrue(compareCodePoints("a#10", "a#2") < 0)
        assertTrue(compareCodePoints("Z", "a") < 0)
        assertTrue(compareCodePoints("ab", "abc") < 0)
        assertTrue(compareCodePoints("abc", "ab") > 0)
        assertEquals(0, compareCodePoints("same#1", "same#1"))
    }
}
