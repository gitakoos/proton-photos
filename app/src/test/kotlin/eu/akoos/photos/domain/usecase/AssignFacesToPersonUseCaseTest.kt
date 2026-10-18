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
import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase.Target.Existing
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase.Target.Named
import io.mockk.mockk
import io.mockk.verify
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
 * Runs the face assignment against a real in-memory database: a new name yields a person an empty sweep
 * keeps, an existing person (by id, or by a name in another case) takes the faces, an emptied source is
 * cleaned up with the stale summaries, a guest writes only under the local partition, and a face moved
 * to a new name keeps it through a full rebuild. Also covers the light faces-on-photos query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignFacesToPersonUseCaseTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private lateinit var db: AppDatabase
    private lateinit var scheduler: FaceIndexingScheduler
    private lateinit var assign: AssignFacesToPersonUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
        assign = AssignFacesToPersonUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.personCoverDao(),
            db.notPersonDao(), db.clusterSummaryDao(), scheduler, db,
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
        owner: String = acct,
        manualName: String? = null,
        rejected: Boolean = false,
        score: Float = 0.9f,
        embedding: ByteArray = ByteArray(0),
    ) = db.faceDao().upsert(
        FaceEntity(
            id = id, userId = owner, photoKey = id.substringBefore('#'),
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
            embedding = embedding, personId = personId, score = score,
            rejected = rejected, manualName = manualName,
        ),
    )

    private suspend fun person(id: Long, name: String?, faceCount: Int, isOther: Boolean = false) =
        db.personDao().upsert(
            PersonEntity(id = id, userId = acct, displayName = name, faceCount = faceCount, isOther = isOther),
        )

    private suspend fun summary(personId: Long) =
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(personId, acct, ByteArray(0), 1, FACE_MODEL_VERSION))

    @Test
    fun `a new name creates a person with a count and cover that an empty sweep keeps`() = runTest {
        person(1L, "Anna", faceCount = 2)
        face("p1#0", 1L, manualName = "Anna")
        face("p2#0", 1L, manualName = "Anna")

        val id = assign(user, listOf("p2#0"), Named("  Bea "), recluster = false)
        db.personDao().deleteEmpty(acct)

        val bea = db.personDao().personById(id)
        assertNotNull("the new person survives the sweep", bea)
        assertEquals("Bea", bea!!.displayName)
        assertEquals(1, bea.faceCount)
        assertEquals("p2#0", bea.coverFaceId)
        val moved = db.faceDao().faceById("p2#0")!!
        assertEquals(id, moved.personId)
        assertEquals("confirmed under the new name", "Bea", moved.manualName)
        assertEquals("the source keeps its other photo", 1, db.personDao().personById(1L)!!.faceCount)
    }

    @Test
    fun `an existing person takes the faces by id or by a name in another case`() = runTest {
        person(1L, "Anna", faceCount = 1)
        person(2L, "Bea", faceCount = 3)
        face("p1#0", 1L, manualName = "Anna")
        face("p2#0", 2L)
        face("p3#0", 2L)
        face("p4#0", 2L)
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Anna", "p2#0")))

        val byName = assign(user, listOf("p2#0"), Named("anna"), recluster = false)
        assertEquals("a name differing only in case resolves to the existing person", 1L, byName)
        assertEquals("labelled with the person's own spelling", "Anna", db.faceDao().faceById("p2#0")!!.manualName)
        assertTrue("the contrary not-this-person mark is lifted", db.notPersonDao().allForUser(acct).isEmpty())

        val byId = assign(user, listOf("p3#0"), Existing(1L), recluster = false)
        assertEquals(1L, byId)
        assertEquals(setOf("p1#0", "p2#0", "p3#0"), db.faceDao().faceIdsForPerson(acct, 1L).toSet())
        assertEquals(3, db.personDao().personById(1L)!!.faceCount)
        assertEquals("the source is refreshed", 1, db.personDao().personById(2L)!!.faceCount)
        assertEquals("no duplicate person is minted", 2, db.personDao().allForUser(acct).size)
    }

    @Test
    fun `a source left with no faces is removed along with the stale summaries`() = runTest {
        person(1L, "Anna", faceCount = 1)
        person(2L, null, faceCount = 1)
        face("p1#0", 1L, manualName = "Anna")
        face("p2#0", 2L)
        summary(1L)
        summary(2L)

        assign(user, listOf("p2#0"), Existing(1L), recluster = false)

        assertNull("the emptied source is gone", db.personDao().personById(2L))
        assertEquals(
            "the target's and the source's summaries are both dropped",
            emptyList<Long>(),
            db.clusterSummaryDao().getAllForUser(acct).map { it.personId },
        )
        assertEquals(2, db.personDao().personById(1L)!!.faceCount)
    }

    @Test
    fun `faces confirmed on the person they already sit on keep its summary`() = runTest {
        person(1L, "Anna", faceCount = 2)
        face("p1#0", 1L)
        face("p2#0", 1L)
        summary(1L)

        assign(user, listOf("p1#0"), Existing(1L), recluster = false)

        assertEquals("Anna", db.faceDao().faceById("p1#0")!!.manualName)
        assertEquals(listOf(1L), db.clusterSummaryDao().getAllForUser(acct).map { it.personId })
    }

    @Test
    fun `an unnamed target clears the old confirmation and the Unsorted bucket is refused`() = runTest {
        person(1L, "Anna", faceCount = 2)
        person(2L, null, faceCount = 1)
        person(9L, null, faceCount = 1, isOther = true)
        face("p1#0", 1L, manualName = "Anna")
        face("p2#0", 1L, manualName = "Anna")
        face("p3#0", 2L)
        face("p4#0", 9L)

        assertEquals(2L, assign(user, listOf("p2#0"), Existing(2L), recluster = false))
        val moved = db.faceDao().faceById("p2#0")!!
        assertEquals(2L, moved.personId)
        assertNull("the old name no longer pulls the face back", moved.manualName)

        assertEquals(-1L, assign(user, listOf("p1#0"), Existing(9L), recluster = false))
        assertEquals("a refused target changes nothing", 1L, db.faceDao().faceById("p1#0")!!.personId)
    }

    @Test
    fun `a guest assigns under the local partition and never touches another account`() = runTest {
        val local = PhotoLocationEntity.LOCAL_USER
        face("g1#0", null, owner = local)
        face("a1#0", null, owner = acct)

        val id = assign(null, listOf("g1#0", "a1#0"), Named("Bea"))
        db.personDao().deleteEmpty(local)

        val bea = db.personDao().personById(id)!!
        assertEquals(local, bea.userId)
        assertEquals(1, bea.faceCount)
        assertEquals(id, db.faceDao().faceById("g1#0")!!.personId)
        val foreign = db.faceDao().faceById("a1#0")!!
        assertNull("the account's face is left alone", foreign.personId)
        assertNull(foreign.manualName)
        verify(exactly = 1) { scheduler.requestRecluster(null) }
    }

    @Test
    fun `a recluster is requested once only when asked and something was assigned`() = runTest {
        face("p1#0", null)

        assertEquals(-1L, assign(user, emptyList(), Named("Bea")))
        assertEquals(-1L, assign(user, listOf("missing#0"), Named("Bea")))
        assertEquals(-1L, assign(user, listOf("p1#0"), Named("   ")))
        val first = assign(user, listOf("p1#0"), Named("Bea"), recluster = false)
        verify(exactly = 0) { scheduler.requestRecluster(any()) }

        val second = assign(user, listOf("p1#0"), Named("Bea"))
        assertEquals("the same name resolves to the same person", first, second)
        verify(exactly = 1) { scheduler.requestRecluster(user) }
    }

    @Test
    fun `a face moved to a new name keeps it through a full rebuild`() = runTest {
        person(1L, "Anna", faceCount = 5)
        for (i in 1..5) face("p$i#0", 1L, manualName = "Anna", embedding = embedding(axis = i))

        val bea = assign(user, listOf("p5#0"), Named("Bea"), recluster = false)
        ClusterFacesUseCase(
            db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
            db.personCoverDao(), db.clusterSummaryDao(), db,
        ).invoke(user)

        assertEquals("the moved face stays on the new person", bea, db.faceDao().faceById("p5#0")!!.personId)
        assertEquals("Bea", db.personDao().personById(bea)!!.displayName)
        for (i in 1..4) {
            assertEquals("the other faces stay with Anna", 1L, db.faceDao().faceById("p$i#0")!!.personId)
        }
        assertEquals("Anna", db.personDao().personById(1L)!!.displayName)
    }

    @Test
    fun `faces on photos include ungrouped faces and carry no embedding`() = runTest {
        person(1L, "Anna", faceCount = 1)
        face("k1#0", 1L)
        face("k1#1", null, score = 0.95f)
        face("k1#2", null, rejected = true)
        face("k1#9", null, owner = "another")
        face("k2#0", null)
        face("k3#0", null)

        // Wide enough to span several host-variable chunks, with the wanted photos in different ones.
        val keys = listOf("k1") + List(1100) { "pad$it" } + listOf("k2")
        val faces = db.faceDao().facesOnPhotos(acct, keys)

        assertEquals(setOf("k1#0", "k1#1", "k2#0"), faces.map { it.id }.toSet())
        assertEquals("clearest first within a photo", listOf("k1#1", "k1#0"), faces.filter { it.photoKey == "k1" }.map { it.id })
        assertNull("an ungrouped face is included", faces.single { it.id == "k1#1" }.personId)
        val grouped = faces.single { it.id == "k1#0" }
        assertEquals(1L, grouped.personId)
        assertEquals(0.1f, grouped.boxLeft, 1e-6f)
        assertEquals(0.4f, grouped.boxBottom, 1e-6f)
        assertTrue(
            "the projection never carries the embedding",
            FaceOnPhoto::class.java.declaredFields.none { it.name == "embedding" },
        )
    }
}
