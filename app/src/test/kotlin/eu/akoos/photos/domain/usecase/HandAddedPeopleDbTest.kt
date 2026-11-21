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
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase.Target.Existing
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase.Target.Named
import eu.akoos.photos.presentation.viewer.manualPeopleOnPhoto
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
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
 * A named person stands while it holds faces or photos added to it by hand, and every way a person loses its
 * name or its row handles those photos the same: a regroup keeps the person, a merge hands the photos to the
 * surviving name, and deleting the person, taking its name off or taking the last photo off it removes them,
 * noted for the shared copy. A name left holding such photos with no person gets one back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandAddedPeopleDbTest {

    private val acct = "acct"
    private val user = UserId(acct)
    private val scheduler = mockk<FaceIndexingScheduler>(relaxed = true)
    private val accounts = mockk<AccountManager>(relaxed = true)
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        every { accounts.getPrimaryUserId() } returns flowOf(user)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun assign() = AssignFacesToPersonUseCase(
        db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.personCoverDao(),
        db.notPersonDao(), db.clusterSummaryDao(), scheduler, db,
    )

    private fun regroup() = ClusterFacesUseCase(
        db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
        db.personCoverDao(), db.clusterSummaryDao(), db,
    )

    private fun notThisPerson() = NotThisPersonUseCase(
        accounts, db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(), db.personCoverDao(),
        db.faceCurationTimeDao(), db.clusterSummaryDao(), scheduler, db, FaceEditCounter(),
    )

    private fun fold() = AssignPersonNameUseCase(
        db.faceDao(), db.personDao(), db.personManualPhotoDao(), db.notPersonDao(),
        db.personCoverDao(), db.clusterSummaryDao(), db,
    )

    private fun ignore() = IgnorePeopleUseCase(
        db.faceDao(), db.personDao(), db.clusterSummaryDao(), db,
        HideSimilarFacesUseCase(db.faceDao(), db.personDao()), FaceEditCounter(),
    )

    private fun rename() = RenamePersonUseCase(db.faceDao(), db.personDao(), db, FaceEditCounter())

    /** One identity's direction with a small lean on [axis], so the faces built with it group together. */
    private fun embedding(axis: Int): ByteArray {
        val v = FloatArray(FACE_EMBEDDING_DIM)
        v[0] = 1f
        v[axis] = 0.1f
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in normalize(v)) buffer.putFloat(x)
        return buffer.array()
    }

    private suspend fun face(id: String, personId: Long?, manualName: String? = null, axis: Int = 1) =
        db.faceDao().upsert(
            FaceEntity(
                id = id, userId = acct, photoKey = id.substringBefore('#'),
                left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f, landmarks = "",
                embedding = embedding(axis), personId = personId, score = 0.9f, manualName = manualName,
            ),
        )

    private suspend fun person(id: Long, name: String?, photos: Int = 1) =
        db.personDao().upsert(PersonEntity(id = id, userId = acct, displayName = name, faceCount = photos))

    private suspend fun attach(name: String, vararg photoKeys: String) =
        db.personManualPhotoDao().add(photoKeys.map { PersonManualPhotoEntity(acct, name, it) })

    private suspend fun attached(name: String) = db.personManualPhotoDao().photoKeysForNameList(acct, name).toSet()

    private suspend fun namedPeople() =
        db.personDao().namedPeopleForUser(acct).associate { it.displayName!! to it }

    private suspend fun chips(photoKey: String) =
        manualPeopleOnPhoto(db.personDao(), db.personManualPhotoDao(), acct, photoKey).mapNotNull { it.name }.toSet()

    private suspend fun removalNoted(name: String, photoKey: String) =
        db.faceCurationTimeDao().allForUser(acct).any {
            it.kind == FaceCurationTimeEntity.KIND_MANUAL_PHOTO && it.personName == name && it.ref == photoKey && it.removed
        }

    @Test
    fun `a person whose only face moved back keeps its hand-added photo through a regroup, and Not this person removes both`() = runTest {
        person(1L, "Lincoln", photos = 3)
        for (i in 1..3) face("p$i#0", 1L, manualName = "Lincoln", axis = i)
        val teszt = assign()(user, listOf("p3#0"), Named("Teszt58"), recluster = false)
        attach("Lincoln", "m")
        attach("Teszt58", "m")

        assign()(user, listOf("p3#0"), Existing(1L), recluster = false)
        regroup()(user)

        val kept = db.personDao().personById(teszt)
        assertEquals("the person keeps its id and name", "Teszt58", kept?.displayName)
        assertEquals("counted by its hand-added photo", 1, kept!!.faceCount)
        assertEquals(setOf("Lincoln", "Teszt58"), chips("m"))
        assertEquals(1L, db.faceDao().faceById("p3#0")!!.personId)

        notThisPerson()(acct, teszt, emptyList(), listOf("m"))

        assertNull("a person left with no photo goes", db.personDao().personById(teszt))
        assertTrue(attached("Teszt58").isEmpty())
        assertEquals(setOf("m"), attached("Lincoln"))
        assertTrue("the removal reaches the other devices", removalNoted("Teszt58", "m"))
        assertEquals(setOf("Lincoln"), chips("m"))
    }

    @Test
    fun `a regroup with no face at all keeps a person with hand-added photos and gives an orphaned name one`() = runTest {
        person(5L, "Kept")
        attach("Kept", "m1")
        attach("Orphan", "m2", "m3")

        regroup()(user)

        val people = namedPeople()
        assertEquals(5L, people.getValue("Kept").id)
        assertEquals(2, people.getValue("Orphan").faceCount)
        assertNull(people.getValue("Orphan").coverFaceId)
    }

    @Test
    fun `a name holding hand-added photos gets one person, never a second, and nothing is noted for other devices`() = runTest {
        val token = HiddenPersonToken.make()
        person(1L, "Anna")
        attach("anna", "m1")
        attach("Bob", "m2", "m3")
        attach(token, "m4")

        val made = db.withTransaction { keepHandAddedPeople(db, acct) }
        val again = db.withTransaction { keepHandAddedPeople(db, acct) }

        assertEquals(1, made)
        assertEquals(0, again)
        assertEquals(setOf("Anna", "Bob"), namedPeople().keys)
        assertEquals(2, namedPeople().getValue("Bob").faceCount)
        assertTrue(db.faceCurationTimeDao().allForUser(acct).isEmpty())
    }

    @Test
    fun `a merge hands the hand-added photos to the surviving name and no regroup brings the merged name back`() = runTest {
        person(1L, "Lincoln", photos = 2)
        face("p1#0", 1L, manualName = "Lincoln")
        person(2L, "Teszt58")
        attach("Lincoln", "m")
        attach("Teszt58", "m", "m2")

        fold()(acct, 2L, "Lincoln")
        regroup()(user)

        assertEquals(setOf("Lincoln"), namedPeople().keys)
        assertEquals(setOf("m", "m2"), attached("Lincoln"))
        assertTrue(attached("Teszt58").isEmpty())
    }

    @Test
    fun `deleting a person takes its hand-added photos with it, noted for other devices`() = runTest {
        person(2L, "Teszt58")
        attach("Teszt58", "m")

        ignore()(acct, listOf(2L))
        db.withTransaction { keepHandAddedPeople(db, acct) }

        assertNull(db.personDao().personById(2L))
        assertTrue(attached("Teszt58").isEmpty())
        assertTrue(removalNoted("Teszt58", "m"))
    }

    @Test
    fun `taking the name off drops its hand-added photos, a person with no face goes and one with faces stays unnamed`() = runTest {
        person(1L, "Solo")
        attach("Solo", "m1")
        person(2L, "Duo", photos = 2)
        face("d1#0", 2L, manualName = "Duo")
        attach("Duo", "m2")

        rename()(acct, 1L, null)
        rename()(acct, 2L, null)
        db.withTransaction { keepHandAddedPeople(db, acct) }

        assertNull(db.personDao().personById(1L))
        assertTrue(attached("Solo").isEmpty())
        assertTrue(removalNoted("Solo", "m1"))
        val duo = db.personDao().personById(2L)
        assertNotNull(duo)
        assertNull(duo!!.displayName)
        assertEquals("counted by its faces alone", 1, duo.faceCount)
        assertTrue(attached("Duo").isEmpty())
        assertTrue(namedPeople().isEmpty())
    }

    @Test
    fun `the last face taken off leaves the person for its hand-added photos, and the last of those removes it`() = runTest {
        person(3L, "Rae", photos = 2)
        face("r1#0", 3L, manualName = "Rae")
        attach("Rae", "m1")

        notThisPerson()(acct, 3L, listOf("r1#0"), listOf("r1"))
        assertEquals(1, db.personDao().personById(3L)?.faceCount)

        notThisPerson()(acct, 3L, emptyList(), listOf("m1"))
        assertNull(db.personDao().personById(3L))
    }

    @Test
    fun `a page still open on a person that is gone takes the photo off the name it shows`() = runTest {
        attach("Gone", "m")

        notThisPerson()(acct, 99L, emptyList(), listOf("m"))
        assertEquals("without the shown name there is nothing to go by", setOf("m"), attached("Gone"))

        notThisPerson()(acct, 99L, emptyList(), listOf("m"), shownName = "Gone")
        assertTrue(attached("Gone").isEmpty())
        assertTrue(removalNoted("Gone", "m"))
    }

    @Test
    fun `the viewer lists no name that no person holds`() = runTest {
        person(1L, "Lincoln")
        attach("Lincoln", "m")
        attach("Teszt58", "m")

        assertEquals(setOf("Lincoln"), chips("m"))
    }
}
