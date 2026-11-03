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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PersonNameChangeEntity
import eu.akoos.photos.data.db.entity.PersonNameStateEntity
import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.util.NetworkObserver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * "Clear face data" and removing the face model wipe every face table of the account in ONE transaction
 * that outlives the screen asking for it. Cleared one table at a time from a screen's own scope, leaving the
 * screen stopped the wipe half-way: faces gone, scan markers kept (so nothing was scanned again), people
 * still listed. Another account's face data is never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceDataWipeTest {

    @get:Rule
    val files = TemporaryFolder()

    private val acct = "acct"
    private val other = "other"
    private lateinit var db: AppDatabase
    private lateinit var store: GatedPreferencesDataStore
    private lateinit var scheduler: FaceIndexingScheduler

    /** The preferences store, able to hold a write until the test lets it go. */
    private class GatedPreferencesDataStore : DataStore<Preferences> {
        private val inner = InMemoryPreferencesDataStore()
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override val data: Flow<Preferences> get() = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            entered.complete(Unit)
            gate?.await()
            return inner.updateData(transform)
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files.root
        every { context.applicationContext } returns context
        store = GatedPreferencesDataStore()
        InMemoryPreferencesDataStore.install(context, store)
        val network = mockk<NetworkObserver>(relaxed = true)
        every { network.isUnmetered } returns MutableStateFlow(false)
        scheduler = FaceIndexingScheduler(
            context = context,
            faceDao = db.faceDao(),
            faceScanDao = db.faceScanDao(),
            personDao = db.personDao(),
            personManualPhotoDao = db.personManualPhotoDao(),
            notPersonDao = db.notPersonDao(),
            photoListingDao = db.photoListingDao(),
            thumbnailScheduler = mockk(relaxed = true),
            photoDownloadService = mockk(relaxed = true),
            networkObserver = network,
            galleryItemsProvider = Provider { mockk(relaxed = true) },
            clusterFacesUseCase = mockk(relaxed = true),
            autoMergePeople = mockk(relaxed = true),
            migrateGuestFaceData = mockk(relaxed = true),
            rekeyPhotoFaces = mockk(relaxed = true),
            deviceHealth = mockk(relaxed = true),
            mlWalkGate = mockk(relaxed = true),
            appDatabase = db,
            accountManager = Provider { mockk(relaxed = true) },
        )
    }

    @After
    fun tearDown() {
        InMemoryPreferencesDataStore.uninstall()
        db.close()
    }

    private suspend fun seed(owner: String, personId: Long) {
        db.personDao().upsert(PersonEntity(id = personId, userId = owner, displayName = "Anna"))
        db.faceDao().upsert(
            FaceEntity(
                id = "$owner-face", userId = owner, photoKey = "$owner-photo",
                left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
                embedding = ByteArray(4), personId = personId, score = 0.9f,
            ),
        )
        db.faceScanDao().upsert(listOf(FaceScanEntity(userId = owner, photoKey = "$owner-photo")))
        db.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(userId = owner, personName = "Anna", photoKey = "$owner-photo")))
        db.notPersonDao().add(listOf(NotPersonEntity(userId = owner, personName = "Bob", faceId = "$owner-face")))
        db.personCoverDao().set(PersonCoverEntity(userId = owner, personName = "Anna", photoKey = "$owner-photo"))
        db.personNameDao().setState(PersonNameStateEntity(userId = owner, name = "Anna", namedAt = 1L))
        db.personNameDao().addChange(PersonNameChangeEntity(userId = owner, fromName = "Ann", toName = "Anna", changedAt = 1L))
        db.faceCurationTimeDao().set(
            listOf(FaceCurationTimeEntity(userId = owner, kind = 0, personName = "Anna", ref = "r", removed = false, changedAt = 1L)),
        )
        db.clusterSummaryDao().upsert(
            ClusterSummaryEntity(personId = personId, userId = owner, centroid = ByteArray(4), memberCount = 1, modelVersion = 1),
        )
    }

    private val tables = listOf(
        "face", "person", "face_scan", "person_manual_photo", "not_person", "person_cover",
        "person_name_state", "person_name_change", "face_curation_time", "cluster_summary",
    )

    private fun rowsOf(owner: String): Map<String, Int> = tables.associateWith { table ->
        db.query("SELECT COUNT(*) FROM $table WHERE userId = ?", arrayOf(owner)).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    @Test
    fun `the wipe clears every face table of the account and leaves another account alone`() = runBlocking {
        seed(acct, personId = 1L)
        seed(other, personId = 2L)

        scheduler.wipeFaceData(UserId(acct), thenIndex = false)

        assertEquals(tables.associateWith { 0 }, rowsOf(acct))
        assertEquals(tables.associateWith { 1 }, rowsOf(other))
    }

    @Test
    fun `a wipe that fails part-way leaves every table as it was`() = runBlocking {
        seed(acct, personId = 1L)
        // The last table the wipe clears refuses, after the faces and scan markers have already gone.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_wipe BEFORE DELETE ON cluster_summary BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )

        val failure = runCatching { scheduler.wipeFaceData(UserId(acct), thenIndex = false) }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(tables.associateWith { 1 }, rowsOf(acct))
    }

    @Test
    fun `leaving the screen part-way does not stop the wipe`() = runBlocking {
        seed(acct, personId = 1L)
        store.gate = CompletableDeferred()

        val screen = launch(Dispatchers.Default) { scheduler.wipeFaceData(UserId(acct), thenIndex = false) }
        withTimeout(10_000L) { store.entered.await() }
        screen.cancel()
        store.gate!!.complete(Unit)
        withTimeout(10_000L) { screen.join() }

        assertEquals(tables.associateWith { 0 }, rowsOf(acct))
    }
}
