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
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.RekeyPhotoFacesUseCase
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
 * Taking photos' face data off the device, run against an in-memory database: the reaper moves a freed-up
 * photo's faces onto its Drive copy instead of losing them and drops the rest, and every drop leaves the
 * people it touched with honest counts and covers, an emptied one removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FacePhotoRemovalDbTest {

    private val acct = "acct"
    private val dev = "content://media/external/images/media/7"
    private val dev2 = "content://media/external/images/media/8"
    private val link = "linkF"
    private val edits = FaceEditCounter()

    private lateinit var db: AppDatabase
    private lateinit var removal: FacePhotoRemoval

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        val rekey = RekeyPhotoFacesUseCase(
            db.faceDao(), db.faceScanDao(), db.personCoverDao(),
            db.personManualPhotoDao(), db.notPersonDao(), db.personDao(), db.clusterSummaryDao(), db,
        )
        removal = FacePhotoRemoval(db, db.syncStateDao(), rekey, edits)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun person(id: Long, name: String?, faceCount: Int, cover: String? = null, user: String = acct) =
        db.personDao().upsert(PersonEntity(id = id, userId = user, displayName = name, coverFaceId = cover, faceCount = faceCount))

    private suspend fun face(id: String, personId: Long?, confirmed: String? = null, score: Float = 0.5f, user: String = acct) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = user, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f, landmarks = "",
                embedding = ByteArray(0), personId = personId, score = score, manualName = confirmed,
            ),
        )

    private suspend fun synced(localUri: String, linkId: String, status: SyncStatus = SyncStatus.CLOUD_ONLY) =
        db.syncStateDao().insertIgnore(
            SyncStateEntity(
                localUri = localUri, userId = acct, cloudFileId = linkId, localHash = "", cloudHash = null,
                status = status, lastSyncAttemptMs = 0L, lastSyncSuccessMs = null, backedUpAtMs = null, sizeBytes = 0L,
            ),
        )

    @Test
    fun `a freed-up photo keeps its faces, name and mark on its Drive copy`() = runTest {
        person(1L, "Anna", faceCount = 1, cover = "$dev#0")
        face("$dev#0", 1L, confirmed = "Anna")
        face("$dev#1", null)
        db.faceScanDao().upsert(listOf(FaceScanEntity(acct, dev)))
        db.notPersonDao().add(listOf(NotPersonEntity(acct, "Bob", "$dev#1")))
        synced(dev, link)
        val mark = edits.mark()

        val outcome = removal.reap(acct, setOf(dev)) { false }

        assertEquals(1, outcome.moved)
        assertEquals(0, outcome.dropped)
        assertNull(db.faceDao().faceById("$dev#0"))
        val moved = db.faceDao().faceById("$link#0")!!
        assertEquals("Anna", moved.manualName)
        assertEquals(1L, moved.personId)
        assertTrue(db.faceScanDao().isScanned(acct, link))
        assertFalse(db.faceScanDao().isScanned(acct, dev))
        assertEquals(listOf("$link#1"), db.notPersonDao().allForUser(acct).map { it.faceId })
        assertEquals("$link#0", db.personDao().personById(1L)!!.coverFaceId)
        assertFalse("the move counts as an edit", edits.unchangedSince(mark))
    }

    @Test
    fun `a gone photo with no Drive copy is dropped and its people are brought up to date`() = runTest {
        person(1L, "Anna", faceCount = 2, cover = "$dev#0")
        person(2L, null, faceCount = 1, cover = "$dev#1")
        face("$dev#0", 1L, confirmed = "Anna", score = 0.9f)
        face("$link#0", 1L, confirmed = "Anna", score = 0.4f)
        face("$dev#1", 2L)
        db.faceScanDao().upsert(listOf(FaceScanEntity(acct, dev), FaceScanEntity(acct, link)))
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(1L, acct, ByteArray(8), 2, FACE_MODEL_VERSION))

        val outcome = removal.reap(acct, setOf(dev)) { false }

        assertEquals(0, outcome.moved)
        assertEquals(1, outcome.dropped)
        assertNull(db.faceDao().faceById("$dev#0"))
        assertFalse(db.faceScanDao().isScanned(acct, dev))
        assertTrue(db.faceScanDao().isScanned(acct, link))
        val anna = db.personDao().personById(1L)!!
        assertEquals(1, anna.faceCount)
        assertEquals("$link#0", anna.coverFaceId)
        assertNull("a person left with no face is removed", db.personDao().personById(2L))
        assertTrue("the stale centroid goes", db.clusterSummaryDao().getAllForUser(acct).isEmpty())
    }

    @Test
    fun `a photo moved into the vault is dropped, never moved onto its Drive copy`() = runTest {
        person(1L, "Anna", faceCount = 1)
        face("$dev#0", 1L, confirmed = "Anna")
        synced(dev, link, status = SyncStatus.HIDDEN)

        val outcome = removal.reap(acct, setOf(dev)) { false }

        assertEquals(0, outcome.moved)
        assertNull(db.faceDao().faceById("$dev#0"))
        assertNull(db.faceDao().faceById("$link#0"))
    }

    @Test
    fun `a device photo whose Drive copy is gone too is dropped`() = runTest {
        face("$dev#0", null)
        synced(dev, link)

        val outcome = removal.reap(acct, setOf(dev)) { it == link }

        assertEquals(0, outcome.moved)
        assertNull(db.faceDao().faceById("$link#0"))
        assertNull(db.faceDao().faceById("$dev#0"))
    }

    @Test
    fun `a guest photo that is gone is dropped, as a guest has no Drive copy`() = runTest {
        val guest = PhotoLocationEntity.LOCAL_USER
        person(1L, null, faceCount = 1, user = guest)
        face("$dev#0", 1L, user = guest)

        val outcome = removal.reap(guest, setOf(dev)) { false }

        assertEquals(1, outcome.dropped)
        assertNull(db.faceDao().faceById("$dev#0"))
        assertNull(db.personDao().personById(1L))
    }

    @Test
    fun `a named person whose last face is dropped stays while photos are added to them by hand`() = runTest {
        person(1L, "Anna", faceCount = 2, cover = "$link#0")
        face("$link#0", 1L, confirmed = "Anna")
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(acct, "Anna", dev2)))

        removal.dropPhotos(acct, listOf(link))

        val anna = db.personDao().personById(1L)!!
        assertEquals(1, anna.faceCount)
        assertNull(anna.coverFaceId)
    }

    @Test
    fun `dropping photos of one account leaves the people of another alone`() = runTest {
        person(1L, "Anna", faceCount = 1, cover = "$link#0")
        face("$link#0", 1L, confirmed = "Anna")
        person(2L, "Theirs", faceCount = 1, cover = "$link#9", user = "other")
        face("$link#9", 2L, user = "other")

        removal.dropPhotos(acct, listOf(link))

        assertNull(db.personDao().personById(1L))
        assertEquals(1, db.personDao().personById(2L)!!.faceCount)
        assertEquals(2L, db.faceDao().faceById("$link#9")!!.personId)
    }
}
