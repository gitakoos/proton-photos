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

package eu.akoos.photos.data.face

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.usecase.REATTACH_MIN_IOU
import eu.akoos.photos.domain.usecase.ReattachFace
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.matchReattachLabels
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Proves the reattach's lean reads bind exactly what the whole-table reads did. Over a real database of
 * several pages of faces on more photos than one query slice holds, the paged name capture yields the
 * same labels in the same order (tied scores, ids whose UTF-16 and UTF-8 orders differ, removed faces,
 * blank names, another account's rows), and the photo-scoped face read gives the matchers the same
 * names, marks and removals as the whole table (id matches, renumbered faces, swapped boxes, weak
 * overlaps, photos with no face, a label whose id sits under another photo). The references below are the
 * whole-table reads as they ran before the lean ones, kept here verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceReattachReadsDbTest {

    private val acct = "acct"
    private lateinit var db: AppDatabase

    // Tied ids that UTF-16 units order one way and code points (SQLite's order) the other.
    private val bmpHigh = "u" + Char(0xFFFD)
    private val astral = "u" + String(Character.toChars(0x1F600))

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

    private fun photoKey(p: Int) =
        if (p % 4 == 0) "content://media/external/images/media/$p" else "link${(p * 7919) % 10_007}Xy-_"

    private fun boxLeft(j: Int) = 0.05f + 0.3f * j

    private fun face(
        id: String,
        photo: String,
        j: Int,
        user: String = acct,
        score: Float,
        personId: Long? = null,
        manualName: String? = null,
        rejected: Boolean = false,
    ) = FaceEntity(
        id = id, userId = user, photoKey = photo,
        left = boxLeft(j), top = 0.1f, right = boxLeft(j) + 0.2f, bottom = 0.4f, landmarks = "",
        embedding = ByteArray(8), personId = personId, score = score, rejected = rejected, manualName = manualName,
    )

    /** 700 photos of one to three faces each, with every kind of name a capture meets. */
    private suspend fun seedLibrary() {
        listOf(
            PersonEntity(id = 1, userId = acct, displayName = "Anna"),
            PersonEntity(id = 2, userId = acct, displayName = "Bob"),
            PersonEntity(id = 3, userId = acct, displayName = "   "),
            PersonEntity(id = 4, userId = acct),
            PersonEntity(id = 5, userId = acct, displayName = "Dora"),
            PersonEntity(id = 6, userId = acct, displayName = "Eve"),
            PersonEntity(id = 9, userId = "other", displayName = "Zed"),
        ).forEach { db.personDao().insert(it) }

        val random = Random(5)
        val ties = floatArrayOf(0.95f, 0.8f, 0.8f, 0.7f, 0.5f)
        val rows = ArrayList<FaceEntity>()
        var g = 0
        for (p in 0 until 700) {
            val photo = photoKey(p)
            for (j in 0 until 1 + p % 3) {
                rows += face(
                    id = "$photo#$j", photo = photo, j = j,
                    score = if (g % 5 == 0) ties[g % ties.size] else random.nextFloat(),
                    personId = when (g % 9) {
                        0 -> null
                        1, 2 -> 1L
                        3 -> 2L
                        4 -> 3L
                        5 -> 4L
                        6 -> 6L
                        else -> 7L
                    },
                    manualName = when (g % 13) {
                        0 -> "Cleo"
                        1 -> " "
                        2 -> "Anna"
                        else -> null
                    },
                    rejected = g % 11 == 0,
                )
                g++
            }
        }
        rows += face(id = "$bmpHigh#0", photo = bmpHigh, j = 0, score = 0.77f, personId = 1L)
        rows += face(id = "$astral#0", photo = astral, j = 0, score = 0.77f, personId = 1L)
        rows += face(id = "Z#0", photo = "Z", j = 0, score = 0.77f, manualName = "Cleo")
        rows += face(id = "a#10", photo = "a", j = 0, score = 0.77f, personId = 2L)
        rows += face(id = "a#2", photo = "a", j = 1, score = 0.77f, personId = 2L)
        rows += face(id = "zero#0", photo = "zero", j = 0, score = 0f, personId = 1L)
        rows += face(id = "negzero#0", photo = "negzero", j = 0, score = -0f, personId = 1L)
        for (i in 0 until 30) {
            rows += face(id = "${photoKey(i)}#9", photo = photoKey(i), j = 0, user = "other", score = 0.99f, personId = 9L, manualName = "Zed")
        }
        db.faceDao().upsert(rows)
    }

    private suspend fun nameById(): Map<Long, String> = db.personDao().namedPeopleForUser(acct)
        .mapNotNull { p -> p.displayName?.takeIf { it.isNotBlank() }?.let { p.id to it } }
        .toMap()

    /** The capture's whole-table read, as it ran before the paged one. */
    private suspend fun wholeTableMembers(nameById: Map<Long, String>): List<ReattachLabel> =
        db.faceDao().allFacesByScoreDesc(acct).mapNotNull { f ->
            val name = f.personId?.let { nameById[it] } ?: f.manualName?.takeIf { it.isNotBlank() }
            name ?: return@mapNotNull null
            ReattachLabel(f.id, f.photoKey, f.left, f.top, f.right, f.bottom, name)
        }

    /** The reattach's whole-table read, as it ran before the photo-scoped one. */
    private suspend fun wholeTableFaces(): List<ReattachFace> = db.faceDao().allFacesByScoreDesc(acct).map {
        ReattachFace(it.id, it.photoKey, it.left, it.top, it.right, it.bottom)
    }

    @Test
    fun `the paged capture gives the whole-table capture's labels in its order`() = runTest {
        seedLibrary()
        val nameById = nameById()
        val old = wholeTableMembers(nameById)
        val paged = readReattachMembers(db.faceDao(), acct, nameById)

        assertTrue("the fixture spans several pages", db.faceDao().countForUser(acct) > 1_200 && old.size > 600)
        assertEquals(old, paged)
        // The fixture exercises what it claims to: code-point order, blank names falling back, removed faces.
        val ids = paged.map { it.faceId }
        assertTrue(ids.indexOf("$bmpHigh#0") in 0 until ids.indexOf("$astral#0"))
        assertTrue(ids.indexOf("a#10") in 0 until ids.indexOf("a#2"))
        assertTrue(paged.none { it.name.isBlank() } && paged.any { it.name == "Cleo" })
        assertTrue(paged.none { it.name == "Zed" })
    }

    @Test
    fun `the photo-scoped read binds the names, marks and removals the whole table did`() = runTest {
        seedLibrary()
        val members = ArrayList<ReattachLabel>()
        val nots = ArrayList<ReattachLabel>()
        val rejected = ArrayList<ReattachBox>()
        for (p in 0 until 700) {
            val photo = photoKey(p)
            val faces = 1 + p % 3
            for (j in 0 until faces) {
                val name = "N${(p + j) % 5}"
                when (p % 8) {
                    // Same id, same box: an unchanged detector.
                    0, 1 -> members += ReattachLabel("$photo#$j", photo, boxLeft(j), 0.1f, boxLeft(j) + 0.2f, 0.4f, name)
                    // Renumbered, box a little off: bound by overlap.
                    2, 3 -> members += ReattachLabel("$photo#${j + 5}", photo, boxLeft(j) + 0.01f, 0.11f, boxLeft(j) + 0.21f, 0.41f, name)
                    // Same id but the box of the next face: unsafe on a multi-face photo, falls to overlap.
                    4, 5 -> {
                        val k = (j + 1) % faces
                        members += ReattachLabel("$photo#$j", photo, boxLeft(k), 0.1f, boxLeft(k) + 0.2f, 0.4f, name)
                    }
                    // Too weak an overlap to bind.
                    6 -> members += ReattachLabel("$photo#${j + 5}", photo, boxLeft(j) + 0.15f, 0.3f, boxLeft(j) + 0.35f, 0.6f, name)
                }
            }
            if (p % 10 == 1) nots += ReattachLabel("$photo#0", photo, boxLeft(0), 0.1f, boxLeft(0) + 0.2f, 0.4f, "Anna")
            if (p % 12 == 3) rejected += ReattachBox(photo, boxLeft(0) + 0.01f, 0.1f, boxLeft(0) + 0.2f, 0.41f)
        }
        // A label losing its face to an id match, labels on photos with no face, and a label whose id names
        // a face filed under a different photo than the one it carries.
        members += ReattachLabel("${photoKey(16)}#7", photoKey(16), boxLeft(0) + 0.02f, 0.1f, boxLeft(0) + 0.2f, 0.4f, "Rival")
        for (i in 0 until 20) members += ReattachLabel("ghost$i#0", "ghost$i", 0.1f, 0.1f, 0.3f, 0.4f, "Ghost")
        members += ReattachLabel("${photoKey(47)}#0", photoKey(46), boxLeft(0), 0.1f, boxLeft(0) + 0.2f, 0.4f, "Elsewhere")
        val data = FaceReattachData(members, emptyList(), nots, rejected)

        val old = wholeTableFaces()
        val scoped = readReattachFaces(db.faceDao(), acct, data)

        assertTrue("the fixture spans several slices of photos", members.map { it.photoKey }.toSet().size > 600)
        assertTrue("only the labelled photos are read", scoped.size < old.size)
        val oldNames = matchReattachLabels(data.members, old)
        assertEquals(oldNames, matchReattachLabels(data.members, scoped))
        assertEquals(matchReattachLabels(data.nots, old), matchReattachLabels(data.nots, scoped))
        assertEquals(
            matchRejectedByGeometry(data.rejected, old, REATTACH_MIN_IOU),
            matchRejectedByGeometry(data.rejected, scoped, REATTACH_MIN_IOU),
        )
        // The fixture binds by id and by overlap, and leaves the weak ones, the loser and the ghosts unnamed.
        assertTrue(oldNames.size > 800)
        assertEquals("Elsewhere", oldNames["${photoKey(47)}#0"])
        assertTrue(oldNames.values.none { it == "Ghost" || it == "Rival" })
        assertTrue(matchReattachLabels(data.nots, scoped).isNotEmpty())
        assertTrue(matchRejectedByGeometry(data.rejected, scoped, REATTACH_MIN_IOU).isNotEmpty())
        assertTrue(scoped.none { it.faceId.endsWith("#9") })
    }

    @Test
    fun `a snapshot with nothing to bind reads no face`() = runTest {
        seedLibrary()
        val data = FaceReattachData(emptyList(), listOf(ReattachManual("Anna", photoKey(1))), emptyList())
        assertEquals(emptyList<ReattachFace>(), readReattachFaces(db.faceDao(), acct, data))
    }
}
