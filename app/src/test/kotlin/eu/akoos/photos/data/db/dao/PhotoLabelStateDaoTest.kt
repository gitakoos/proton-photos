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

package eu.akoos.photos.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.entity.PhotoLabelEntity
import eu.akoos.photos.util.LabelNormalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The per-photo change times the Drive copy of the labels merges on: which writes stamp them, and which do not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelStateDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoLabelDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoLabelDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(photoKey: String, label: String, userId: String = USER) =
        PhotoLabelEntity(userId, photoKey, label, LabelNormalizer.fold(label), 1L)

    private suspend fun put(photoKey: String, vararg labels: String, userId: String = USER) =
        dao.insertIgnore(labels.map { row(photoKey, it, userId) })

    private suspend fun states(userId: String = USER): Map<String, Long> =
        dao.statesForUser(userId).associate { it.photoKey to it.changedAt }

    @Test
    fun `replacing a list stamps the photo and emptying it keeps the stamp`() = runTest {
        val before = System.currentTimeMillis()
        dao.replaceLabelsOfPhoto(USER, PHOTO_A, listOf(row(PHOTO_A, "a")))
        assertTrue(states().getValue(PHOTO_A) >= before)

        dao.replaceLabelsOfPhoto(USER, PHOTO_A, emptyList())
        assertTrue("a removal leaves a stamp behind", PHOTO_A in states())
        assertTrue(dao.labelsOfPhoto(USER, PHOTO_A).isEmpty())
    }

    @Test
    fun `adding stamps only the photos that gained a label`() = runTest {
        put(PHOTO_A, "beach")

        dao.addLabelsToPhotos(USER, listOf(PHOTO_A, PHOTO_B), listOf("beach"), now = 777L)

        assertNull("a photo that already had it is untouched", states()[PHOTO_A])
        assertEquals(777L, states()[PHOTO_B])
    }

    @Test
    fun `removing one label stamps the photo, and a label that was not there does not`() = runTest {
        put(PHOTO_A, "a", "b")

        assertTrue(dao.removeLabelOfPhoto(USER, PHOTO_A, "a"))
        val stamped = states().getValue(PHOTO_A)
        assertTrue(!dao.removeLabelOfPhoto(USER, PHOTO_A, "missing"))

        assertEquals(stamped, states()[PHOTO_A])
        assertEquals(listOf("b"), dao.labelsOfPhoto(USER, PHOTO_A).map { it.label })
    }

    @Test
    fun `deleting a label everywhere stamps every photo that lost it`() = runTest {
        put(PHOTO_A, "x")
        put(PHOTO_B, "x", "y")
        put(PHOTO_C, "y")

        assertEquals(2, dao.removeLabelEverywhere(USER, "x"))

        assertEquals(setOf(PHOTO_A, PHOTO_B), states().keys)
    }

    @Test
    fun `renaming a label stamps the photos of both labels`() = runTest {
        put(PHOTO_A, "old")
        put(PHOTO_B, "new")
        put(PHOTO_C, "other")

        dao.renameLabel(USER, "old", "new", "New")

        assertEquals(setOf(PHOTO_A, PHOTO_B), states().keys)
    }

    @Test
    fun `moving a photo's labels to a new key stamps both keys`() = runTest {
        put(URI, "a")

        dao.rekeyPhotos(USER, listOf(URI to LINK))

        assertEquals(setOf(URI, LINK), states().keys)
        assertEquals(listOf("a"), dao.labelsOfPhoto(USER, LINK).map { it.label })
    }

    @Test
    fun `dropping the labels of vaulted photos stamps every owner's list`() = runTest {
        put(PHOTO_A, "a")
        put(PHOTO_A, "g", userId = GUEST)
        put(PHOTO_B, "b")

        dao.deleteForPhotoKeys(listOf(PHOTO_A))

        assertTrue(dao.labelsOfPhoto(USER, PHOTO_A).isEmpty())
        assertEquals(setOf(PHOTO_A), states().keys)
        assertEquals(setOf(PHOTO_A), states(GUEST).keys)
    }

    @Test
    fun `adopting the guest's labels stamps the account's photos and clears the guest's stamps`() = runTest {
        put(PHOTO_A, "g", userId = GUEST)
        dao.touch(GUEST, PHOTO_A, 5L)

        dao.adoptUserRows(GUEST, USER)

        assertTrue(PHOTO_A in states())
        assertTrue(states(GUEST).isEmpty())
        assertEquals(listOf("g"), dao.labelsOfPhoto(USER, PHOTO_A).map { it.label })
    }

    @Test
    fun `wiping an account removes its labels and its stamps, not another's`() = runTest {
        put(PHOTO_A, "a")
        dao.touch(USER, PHOTO_A, 5L)
        put(PHOTO_A, "z", userId = OTHER)
        dao.touch(OTHER, PHOTO_A, 6L)

        dao.deleteAllForUser(USER)

        assertTrue(states().isEmpty())
        assertTrue(dao.labelsOfPhoto(USER, PHOTO_A).isEmpty())
        assertEquals(6L, states(OTHER)[PHOTO_A])
    }

    @Test
    fun `a list taken from Drive keeps the time it was written with`() = runTest {
        put(PHOTO_A, "stale")

        dao.applyRemoteList(USER, PHOTO_A, listOf(row(PHOTO_A, "fresh")), changedAt = 4_242L)
        dao.applyRemoteList(USER, PHOTO_B, emptyList(), changedAt = 99L)

        assertEquals(listOf("fresh"), dao.labelsOfPhoto(USER, PHOTO_A).map { it.label })
        assertEquals(4_242L, states()[PHOTO_A])
        assertEquals("an empty list is still stamped", 99L, states()[PHOTO_B])
    }

    @Test
    fun `change times of a large key set read in slices`() = runTest {
        val keys = (0 until 1_200).map { "k$it" }
        keys.forEach { dao.touch(USER, it, 1L) }

        assertEquals(keys.size, dao.statesForKeys(USER, keys).size)
    }

    private companion object {
        const val USER = "user-1"
        const val OTHER = "user-2"
        const val GUEST = "local"
        const val PHOTO_A = "photo-a"
        const val PHOTO_B = "photo-b"
        const val PHOTO_C = "photo-c"
        const val URI = "content://media/external/images/media/1"
        const val LINK = "link-1"
    }
}
