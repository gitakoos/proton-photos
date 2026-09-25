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
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.usecase.RekeyPhotoFacesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

/**
 * Folding a photo's device-keyed face data into its cloud-keyed face data when both copies were scanned
 * (a restored face snapshot wrote the cloud copy's), run against an in-memory database so the re-pointing
 * SQL and the refreshed people are covered together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceRekeyMergeDbTest {

    private val acct = "acct"
    private val dev = "content://media/external/images/media/42"
    private val link = "linkM"

    private val boxA = floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f)
    private val boxA2 = floatArrayOf(0.11f, 0.1f, 0.31f, 0.3f)
    private val boxB = floatArrayOf(0.6f, 0.1f, 0.8f, 0.3f)
    private val boxB2 = floatArrayOf(0.61f, 0.11f, 0.81f, 0.31f)
    private val boxX = floatArrayOf(0.4f, 0.6f, 0.6f, 0.8f)

    private lateinit var db: AppDatabase
    private lateinit var rekey: RekeyPhotoFacesUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        rekey = RekeyPhotoFacesUseCase(
            db.faceDao(), db.faceScanDao(), db.personCoverDao(),
            db.personManualPhotoDao(), db.notPersonDao(), db.personDao(), db.clusterSummaryDao(), db,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun d(index: Int) = "$dev#$index"
    private fun c(index: Int) = "$link#$index"

    private suspend fun person(id: Long, name: String?, faceCount: Int = 1, cover: String? = null) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, coverFaceId = cover, faceCount = faceCount))

    private suspend fun face(
        id: String,
        photoKey: String,
        box: FloatArray,
        personId: Long? = null,
        confirmed: String? = null,
        rejected: Boolean = false,
        score: Float = 0.5f,
        user: String = acct,
    ) = db.faceDao().upsert(
        FaceEntity(
            id = id, userId = user, photoKey = photoKey,
            left = box[0], top = box[1], right = box[2], bottom = box[3], landmarks = "",
            embedding = ByteArray(0), personId = personId, score = score, rejected = rejected, manualName = confirmed,
        ),
    )

    private suspend fun scanned(photoKey: String, hiRes: Boolean = false) =
        db.faceScanDao().upsert(listOf(FaceScanEntity(acct, photoKey, hiRes)))

    private suspend fun bothScanned() {
        scanned(dev)
        scanned(link)
    }

    private suspend fun faces(): Map<String, FaceEntity> =
        db.faceDao().observeFacesForUser(acct).first().associateBy { it.id }

    private suspend fun merge() = assertTrue(rekey.invoke(acct, dev, link))

    @Test
    fun `names are kept on a collision`() = runTest {
        person(1L, "Alice")
        person(2L, null)
        person(3L, "Bob")
        face(c(0), link, boxA, personId = 1L, confirmed = "Alice")
        face(c(1), link, boxB)
        face(d(0), dev, boxA2, personId = 2L)
        face(d(1), dev, boxB2, personId = 3L, confirmed = "Bob")
        bothScanned()

        merge()

        val after = faces()
        assertEquals(setOf(c(0), c(1)), after.keys)
        // The cloud face keeps its name and person; the device name fills the unconfirmed cloud face.
        assertEquals("Alice", after.getValue(c(0)).manualName)
        assertEquals(1L, after.getValue(c(0)).personId)
        assertEquals("Bob", after.getValue(c(1)).manualName)
    }

    @Test
    fun `the cloud side wins a name conflict and keeps its removal`() = runTest {
        person(1L, "Alice")
        person(2L, "Alicia", faceCount = 2)
        face(c(0), link, boxA, personId = 1L, confirmed = "Alice")
        face(c(1), link, boxB, rejected = true)
        face(d(0), dev, boxA2, personId = 2L, confirmed = "Alicia")
        face(d(1), dev, boxB2, personId = 2L, confirmed = "Alicia")
        bothScanned()

        merge()

        val after = faces()
        assertEquals(setOf(c(0), c(1)), after.keys)
        assertEquals("Alice", after.getValue(c(0)).manualName)
        assertEquals(1L, after.getValue(c(0)).personId)
        assertTrue("a removed cloud face stays removed", after.getValue(c(1)).rejected)
        assertNull(after.getValue(c(1)).manualName)
        // The device person lost both of its faces.
        assertEquals(0, db.personDao().personById(2L)!!.faceCount)
        assertEquals(1, db.personDao().personById(1L)!!.faceCount)
    }

    @Test
    fun `not-a-person marks follow the pairing without duplicates`() = runTest {
        person(1L, "Alice")
        face(c(0), link, boxA, personId = 1L, confirmed = "Alice")
        face(d(0), dev, boxA2)
        face(d(1), dev, boxX)
        bothScanned()
        db.notPersonDao().add(
            listOf(
                NotPersonEntity(acct, "Carol", c(0)),
                NotPersonEntity(acct, "Carol", d(0)),
                NotPersonEntity(acct, "Alice", d(0)),
                NotPersonEntity(acct, "Frank", d(0)),
                NotPersonEntity(acct, "Dave", d(1)),
            ),
        )

        merge()

        assertEquals(
            setOf("Carol" to c(0), "Frank" to c(0), "Dave" to c(1)),
            db.notPersonDao().allForUser(acct).map { it.personName to it.faceId }.toSet(),
        )
    }

    @Test
    fun `manual photos move and a duplicate is dropped`() = runTest {
        person(1L, "Alice", faceCount = 2)
        person(2L, "Bob")
        face(c(0), link, boxA, personId = 1L)
        face(d(0), dev, boxA2, personId = 1L)
        bothScanned()
        db.personManualPhotoDao().add(
            listOf(
                PersonManualPhotoEntity(acct, "Alice", dev),
                PersonManualPhotoEntity(acct, "Alice", link),
                PersonManualPhotoEntity(acct, "Bob", dev),
            ),
        )

        merge()

        assertEquals(
            setOf("Alice" to link, "Bob" to link),
            db.personManualPhotoDao().allForUser(acct).map { it.personName to it.photoKey }.toSet(),
        )
        assertEquals(1, db.personDao().personById(1L)!!.faceCount)
        assertEquals(1, db.personDao().personById(2L)!!.faceCount)
    }

    @Test
    fun `a cover on a device face moves to the face that stays`() = runTest {
        person(1L, "Bob", faceCount = 2, cover = d(0))
        person(2L, null, cover = d(1))
        // A clearer face of Bob on another photo, which a fresh automatic pick would take instead.
        face("linkOther#0", "linkOther", boxA, personId = 1L, score = 0.99f)
        face(c(0), link, boxA, personId = 1L)
        face(d(0), dev, boxA2, personId = 1L)
        face(d(1), dev, boxX, personId = 2L)
        bothScanned()

        merge()

        assertEquals(c(0), db.personDao().personById(1L)!!.coverFaceId)
        assertEquals(c(1), db.personDao().personById(2L)!!.coverFaceId)
    }

    @Test
    fun `the cloud marker takes the device marker's hi-res flag`() = runTest {
        scanned(dev, hiRes = true)
        scanned(link, hiRes = false)

        merge()

        assertEquals(listOf(FaceScanEntity(acct, link, true)), db.faceScanDao().markersAfter(acct, "", 10))
    }

    @Test
    fun `shifted face indices pair by box and the extra face takes a free id`() = runTest {
        face(c(0), link, boxA, personId = 1L)
        face(c(1), link, boxB, personId = 2L)
        face(d(0), dev, boxB2, personId = 3L)
        face(d(1), dev, boxX, personId = 4L)
        face(d(2), dev, boxA2, personId = 5L)
        bothScanned()

        merge()

        val after = faces()
        assertEquals(setOf(c(0), c(1), c(2)), after.keys)
        assertEquals(1L, after.getValue(c(0)).personId)
        assertEquals(2L, after.getValue(c(1)).personId)
        val extra = after.getValue(c(2))
        assertEquals(link, extra.photoKey)
        assertEquals("the extra face keeps its person", 4L, extra.personId)
        assertEquals(boxX[0], extra.left)
    }

    @Test
    fun `no duplicate face is left for the photo`() = runTest {
        face(c(0), link, boxA, rejected = true)
        face(c(1), link, boxB, personId = 1L)
        face(d(0), dev, boxA2, personId = 2L)
        face(d(1), dev, boxB2, personId = 1L)
        bothScanned()

        merge()

        val after = faces()
        assertEquals(setOf(c(0), c(1)), after.keys)
        // The device twin of the removed face does not come back as a kept one.
        assertTrue(after.getValue(c(0)).rejected)
        assertEquals(listOf(link), db.faceDao().facePhotoKeysForUser(acct))
        assertEquals(listOf(link), db.faceScanDao().scannedKeysForUser(acct))
    }

    @Test
    fun `a person is no longer counted twice for one photo`() = runTest {
        person(1L, "Alice", faceCount = 2)
        face(c(0), link, boxA, personId = 1L, confirmed = "Alice")
        face(d(0), dev, boxA2, personId = 1L)
        bothScanned()
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, ByteArray(0), 2, 1))

        merge()

        assertEquals(1, db.personDao().personById(1L)!!.faceCount)
        assertEquals(listOf(c(0)), db.faceDao().faceIdsForPerson(acct, 1L))
        assertTrue("the stale centroid is dropped", db.clusterSummaryDao().getAllForUser(acct).isEmpty())
    }

    @Test
    fun `a guest's face data is never touched`() = runTest {
        val guest = PhotoLocationEntity.LOCAL_USER
        face(d(0), dev, boxA2, user = guest)
        db.faceScanDao().upsert(listOf(FaceScanEntity(guest, dev), FaceScanEntity(guest, link)))

        assertFalse(rekey.invoke(guest, dev, link))

        assertEquals(listOf(d(0)), db.faceDao().observeFacesForUser(guest).first().map { it.id })
    }
}
