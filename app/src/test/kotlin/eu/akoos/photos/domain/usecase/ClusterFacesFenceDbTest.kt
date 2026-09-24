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
 * Runs the people rebuild and the incremental placement against a real in-memory database: a write check
 * that says no (an import began after the pass read the faces) leaves every person and assignment as it
 * was, and a completed rebuild reports exactly the faces it left ungrouped on purpose, the count the
 * indexer measures new faces against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterFacesFenceDbTest {

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

    /** A unit embedding leaning slightly off one shared direction along [axis], so every face built with
     *  it reads as the same identity to the clusterer (cosine far above the join floor). */
    private fun embedding(axis: Int): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[0] = 1f
        v[axis] = 0.1f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun face(
        id: String,
        personId: Long?,
        manualName: String? = null,
        rejected: Boolean = false,
        embedding: ByteArray = ByteArray(0),
    ) = db.faceDao().upsert(
        FaceEntity(
            id = id, userId = acct, photoKey = id.substringBefore('#'),
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
            embedding = embedding, personId = personId, score = 0.9f,
            rejected = rejected, manualName = manualName,
        ),
    )

    private suspend fun person(id: Long, name: String?, faceCount: Int) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = faceCount))

    @Test
    fun `a rebuild whose write check says no leaves the people as they were`() = runTest {
        person(1L, "Anna", faceCount = 3)
        for (i in 1..3) face("p$i#0", 1L, manualName = "Anna", embedding = embedding(axis = i))
        face("p4#0", null, embedding = embedding(axis = 4))

        val outcome = cluster(user) { false }

        assertFalse(outcome.committed)
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
        for (i in 1..3) assertEquals(1L, db.faceDao().faceById("p$i#0")!!.personId)
        assertNull("nothing was placed either", db.faceDao().faceById("p4#0")!!.personId)
    }

    @Test
    fun `a rebuild reports the faces it left ungrouped on purpose`() = runTest {
        person(1L, "Anna", faceCount = 5)
        for (i in 1..4) face("p$i#0", 1L, manualName = "Anna", embedding = embedding(axis = i))
        // Groups with Anna's faces but was marked as not being her, so the rebuild keeps it out.
        face("p5#0", 1L, embedding = embedding(axis = 5))
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Anna", "p5#0")))
        // An unreadable vector, which no grouping can place.
        face("p6#0", null)
        // Removed by the user: not waiting for a group at all.
        face("p7#0", null, rejected = true, embedding = embedding(axis = 7))

        val outcome = cluster(user)

        assertTrue(outcome.committed)
        assertEquals(2, outcome.leftUngrouped)
        assertEquals(db.faceDao().unclusteredCount(acct), outcome.leftUngrouped)
        assertNull(db.faceDao().faceById("p5#0")!!.personId)
        assertEquals(1L, db.faceDao().faceById("p1#0")!!.personId)
    }

    @Test
    fun `an incremental placement whose write check says no places nothing`() = runTest {
        person(1L, "Anna", faceCount = 4)
        for (i in 1..4) face("p$i#0", 1L, embedding = embedding(axis = i))
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, embedding(axis = 1), 4, FACE_MODEL_VERSION))
        face("p5#0", null, embedding = embedding(axis = 5))

        assertFalse(cluster.assignNewFaces(user) { false })
        assertNull(db.faceDao().faceById("p5#0")!!.personId)

        assertTrue(cluster.assignNewFaces(user))
        assertEquals(1L, db.faceDao().faceById("p5#0")!!.personId)
    }
}
