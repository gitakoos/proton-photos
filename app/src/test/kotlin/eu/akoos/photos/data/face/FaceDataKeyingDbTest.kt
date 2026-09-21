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
            db.personManualPhotoDao(), db.notPersonDao(), db,
        )
    }

    @After
    fun tearDown() = db.close()

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
    fun `backup re-key is a no-op when the cloud copy was already scanned`() = runTest {
        seedPerson("acct", 7L, "Bob", "content://media/2")
        db.faceScanDao().upsert(listOf(FaceScanEntity("acct", "linkB")))

        val moved = rekey.invoke("acct", "content://media/2", "linkB")

        assertFalse(moved)
        // The device face is left untouched (no collision onto the cloud copy).
        assertEquals("content://media/2#0", db.faceDao().observeFacesForUser("acct").first().single { it.photoKey == "content://media/2" }.id)
    }
}
