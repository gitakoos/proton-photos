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
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.domain.usecase.GuestFaceMigration
import eu.akoos.photos.domain.usecase.MigrateGuestFaceDataUseCase
import eu.akoos.photos.domain.usecase.RekeyPhotoFacesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executes the real re-key SQL against an in-memory database, since the DAO UPDATE that rewrites a
 * face id (photoKey#index) via substr is the part a pure test cannot cover. Two paths: adopting a
 * guest's face data on sign-in, and moving a device photo's faces onto its cloud key on backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceDataKeyingDbTest {

    private lateinit var db: AppDatabase
    private lateinit var migrate: MigrateGuestFaceDataUseCase
    private lateinit var rekey: RekeyPhotoFacesUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        migrate = MigrateGuestFaceDataUseCase(
            db.faceDao(), db.faceScanDao(), db.personDao(), db.notPersonDao(),
            db.personCoverDao(), db.personManualPhotoDao(), db.clusterSummaryDao(), db,
        )
        rekey = RekeyPhotoFacesUseCase(
            db.faceDao(), db.faceScanDao(), db.personCoverDao(),
            db.personManualPhotoDao(), db.notPersonDao(), db.personDao(), db.clusterSummaryDao(), db,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the device photos holding faces are listed for the backed-up re-key`() = runTest {
        for (key in listOf("content://media/1", "content://media/2", "linkA")) {
            db.faceDao().upsert(
                FaceEntity(
                    id = "$key#0", userId = "acct", photoKey = key,
                    left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                    embedding = ByteArray(0), personId = null, score = 0.9f,
                ),
            )
        }
        db.faceDao().upsert(
            FaceEntity(
                id = "content://media/9#0", userId = "other", photoKey = "content://media/9",
                left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                embedding = ByteArray(0), personId = null, score = 0.9f,
            ),
        )

        assertEquals(setOf("content://media/1", "content://media/2"), db.faceDao().devicePhotoKeys("acct").toSet())
    }

    @Test
    fun `a backed-up device photo pairs with its listed Drive key, a hidden, unsent or unlisted one does not`() {
        fun row(uri: String, user: String, cloud: String?, status: eu.akoos.photos.domain.entity.SyncStatus) =
            eu.akoos.photos.data.db.entity.SyncStateEntity(
                localUri = uri, userId = user, cloudFileId = cloud, localHash = "", cloudHash = null, status = status,
                lastSyncAttemptMs = 0, lastSyncSuccessMs = null, backedUpAtMs = null, sizeBytes = 0,
            )
        val synced = eu.akoos.photos.domain.entity.SyncStatus.SYNCED
        val rows = listOf(
            row("content://media/1", "acct", "linkA", synced),
            row("content://media/2", "acct", null, eu.akoos.photos.domain.entity.SyncStatus.LOCAL_ONLY),
            row("content://media/3", "acct", "linkC", eu.akoos.photos.domain.entity.SyncStatus.HIDDEN),
            row("content://media/4", "other", "linkD", synced),
            row("content://media/5", "acct", "", synced),
            row("content://media/6", "acct", "linkF", synced),
            row("content://media/7", "acct", "content://media/7", synced),
            row("content://media/8", "acct", "linkH", eu.akoos.photos.domain.entity.SyncStatus.LOCAL_MODIFIED),
        )
        val listed = setOf("linkA", "linkC", "linkD", "content://media/7", "linkH")

        assertEquals(listOf("content://media/1" to "linkA"), syncStateRekeyPairs("acct", rows, listed))
    }

    @Test
    fun `a skipped marker keeps its flag through a re-key and still counts as scanned`() = runTest {
        db.faceScanDao().upsert(listOf(FaceScanEntity("acct", "content://media/3", skipped = true)))

        assertTrue(rekey.invoke("acct", "content://media/3", "linkC"))

        assertEquals(listOf("linkC"), db.faceScanDao().scannedKeysForUser("acct"))
        assertTrue(db.faceScanDao().markersAfter("acct", "", 10).single().skipped)

        db.faceScanDao().clearForUser("acct")
        assertTrue(db.faceScanDao().scannedKeysForUser("acct").isEmpty())
    }

    @Test
    fun `the pairs are empty when nothing is listed, and a second run over the moved keys finds nothing`() {
        fun row(uri: String, cloud: String) =
            eu.akoos.photos.data.db.entity.SyncStateEntity(
                localUri = uri, userId = "acct", cloudFileId = cloud, localHash = "", cloudHash = null,
                status = eu.akoos.photos.domain.entity.SyncStatus.SYNCED, lastSyncAttemptMs = 0, lastSyncSuccessMs = null, backedUpAtMs = null, sizeBytes = 0,
            )
        val rows = listOf(row("content://media/1", "linkA"))

        assertTrue(syncStateRekeyPairs("acct", rows, emptySet()).isEmpty())
        val first = syncStateRekeyPairs("acct", rows, setOf("linkA"))
        assertEquals(1, first.size)
        val deviceKeysAfterMove = setOf<String>()
        assertTrue(syncStateRekeyPairs("acct", rows.filter { it.localUri in deviceKeysAfterMove }, setOf("linkA")).isEmpty())
    }

    @Test
    fun `the listing query names the listed links among those asked`() = runTest {
        fun listing(id: String, user: String) = eu.akoos.photos.data.db.entity.PhotoListingEntity(
            linkId = id, userId = user, shareId = "s", volumeId = "v", captureTime = 1L, displayName = "$id.jpg",
            mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r",
        )
        db.photoListingDao().upsertAll(listOf(listing("linkA", "acct"), listing("linkB", "other")))
        assertEquals(listOf("linkA"), db.photoListingDao().listedLinkIds("acct", listOf("linkA", "linkB", "linkC")))
    }

    private suspend fun seedPerson(user: String, id: Long, name: String, photoKey: String) {
        db.personDao().upsert(PersonEntity(id = id, userId = user, displayName = name, faceCount = 1))
        db.faceDao().upsert(
            FaceEntity(
                id = "$photoKey#0", userId = user, photoKey = photoKey,
                left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                embedding = ByteArray(0), personId = id, score = 0.9f,
            ),
        )
        db.faceScanDao().upsert(listOf(FaceScanEntity(user, photoKey)))
        db.personCoverDao().set(PersonCoverEntity(user, name, photoKey))
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(user, name, photoKey)))
        db.notPersonDao().add(listOf(NotPersonEntity(user, name, "$photoKey#0")))
        db.clusterSummaryDao().upsert(ClusterSummaryEntity(id, user, ByteArray(0), 1, 1))
    }

    @Test
    fun `adopt re-keys a guest's whole face graph to the account`() = runTest {
        seedPerson("local", 5L, "Alice", "content://media/1")

        val result = migrate.invoke("acct")

        assertEquals(GuestFaceMigration.ADOPT, result.action)
        assertTrue("no guest faces left", db.faceDao().observeFacesForUser("local").first().isEmpty())
        val faces = db.faceDao().observeFacesForUser("acct").first()
        assertEquals(1, faces.size)
        assertEquals("id unchanged, only owner moved", "content://media/1#0", faces[0].id)
        assertEquals("person link intact", 5L, faces[0].personId)
        assertEquals(listOf("content://media/1"), db.faceScanDao().scannedKeysForUser("acct"))
        assertEquals("Alice", db.personDao().allForUser("acct").single().displayName)
        assertTrue(db.personDao().allForUser("local").isEmpty())
    }

    @Test
    fun `adopt is skipped and guest data dropped when the account already has faces`() = runTest {
        seedPerson("local", 5L, "Alice", "content://media/1")
        // Account already scanned something of its own.
        db.faceScanDao().upsert(listOf(FaceScanEntity("acct", "linkOwn")))

        val result = migrate.invoke("acct")

        assertEquals(GuestFaceMigration.DISCARD, result.action)
        assertTrue("guest rows dropped", db.faceDao().observeFacesForUser("local").first().isEmpty())
        assertTrue(db.personDao().allForUser("local").isEmpty())
        // The account keeps its own marker.
        assertEquals(listOf("linkOwn"), db.faceScanDao().scannedKeysForUser("acct"))
    }

    @Test
    fun `backup re-keys a photo's faces onto its cloud key`() = runTest {
        seedPerson("acct", 7L, "Bob", "content://media/2")

        val moved = rekey.invoke("acct", "content://media/2", "linkB")

        assertTrue(moved)
        val faces = db.faceDao().observeFacesForUser("acct").first()
        assertEquals(1, faces.size)
        assertEquals("id re-prefixed to the cloud key", "linkB#0", faces[0].id)
        assertEquals("linkB", faces[0].photoKey)
        assertEquals("person link intact", 7L, faces[0].personId)
        assertEquals(listOf("linkB"), db.faceScanDao().scannedKeysForUser("acct"))
        assertEquals("linkB#0", db.notPersonDao().allForUser("acct").single().faceId)
    }

    @Test
    fun `backup re-key merges into the cloud copy when it was already scanned`() = runTest {
        seedPerson("acct", 7L, "Bob", "content://media/2")
        // The cloud copy holds its own face at the same place, scanned (a restored snapshot).
        db.faceDao().upsert(
            FaceEntity(
                id = "linkB#0", userId = "acct", photoKey = "linkB",
                left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                embedding = ByteArray(0), personId = 7L, score = 0.8f, manualName = "Bob",
            ),
        )
        db.faceScanDao().upsert(listOf(FaceScanEntity("acct", "linkB")))

        val merged = rekey.invoke("acct", "content://media/2", "linkB")

        assertTrue(merged)
        // One face is left for the photo, the cloud one, and nothing stays on the device key.
        val faces = db.faceDao().observeFacesForUser("acct").first()
        assertEquals(listOf("linkB#0"), faces.map { it.id })
        assertEquals(7L, faces.single().personId)
        assertEquals(listOf("linkB"), db.faceScanDao().scannedKeysForUser("acct"))
        // The device face's "not Bob" mark never lands on a face confirmed as Bob.
        assertTrue(db.notPersonDao().allForUser("acct").isEmpty())
        assertEquals("linkB", db.personManualPhotoDao().allForUser("acct").single().photoKey)
        assertEquals("linkB", db.personCoverDao().photoKeyForName("acct", "Bob"))
        assertEquals("one photo, counted once", 1, db.personDao().personById(7L)!!.faceCount)
    }

    @Test
    fun `backup re-key writes nothing when the fence refuses`() = runTest {
        seedPerson("acct", 7L, "Bob", "content://media/2")

        val moved = rekey.invoke("acct", "content://media/2", "linkB") { false }

        assertFalse(moved)
        assertEquals("content://media/2#0", db.faceDao().observeFacesForUser("acct").first().single().id)
        assertEquals(listOf("content://media/2"), db.faceScanDao().scannedKeysForUser("acct"))
    }
}
