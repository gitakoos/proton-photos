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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelDaoTest {

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

    private var clock = 1_000L

    private fun row(photoKey: String, label: String, userId: String = USER) =
        PhotoLabelEntity(userId, photoKey, label, LabelNormalizer.fold(label), clock++)

    private suspend fun put(photoKey: String, vararg labels: String, userId: String = USER) =
        dao.insertIgnore(labels.map { row(photoKey, it, userId) })

    private suspend fun labels(photoKey: String, userId: String = USER) =
        dao.labelsOfPhoto(userId, photoKey).map { it.label }

    @Test
    fun `labels of a photo come back in the order they were written`() = runTest {
        put(PHOTO_A, "zebra", "apple", "mango")
        assertEquals(listOf("zebra", "apple", "mango"), labels(PHOTO_A))
    }

    @Test
    fun `the same folded label cannot sit twice on one photo`() = runTest {
        put(PHOTO_A, "Café")
        put(PHOTO_A, "cafe")
        assertEquals(listOf("Café"), labels(PHOTO_A))
    }

    @Test
    fun `observe all returns every row of the account and nothing of another`() = runTest {
        put(PHOTO_A, "a", "b")
        put(PHOTO_B, "c")
        put(PHOTO_A, "x", userId = OTHER)

        val rows = dao.observeAllForUser(USER).first()

        assertEquals(
            listOf(PhotoLabelRow(PHOTO_A, "a"), PhotoLabelRow(PHOTO_A, "b"), PhotoLabelRow(PHOTO_B, "c")),
            rows,
        )
    }

    @Test
    fun `observe one photo follows writes`() = runTest {
        put(PHOTO_A, "a")
        assertEquals(listOf("a"), dao.observeLabelsOfPhoto(USER, PHOTO_A).first().map { it.label })
        put(PHOTO_A, "b")
        assertEquals(listOf("a", "b"), dao.observeLabelsOfPhoto(USER, PHOTO_A).first().map { it.label })
    }

    @Test
    fun `labels for keys reads a set larger than the host-variable limit`() = runTest {
        val keys = (0 until 1_200).map { "content://media/$it" }
        dao.insertIgnore(keys.map { row(it, "bulk") })
        put(PHOTO_A, "unrelated")

        val rows = dao.labelsForKeys(USER, keys + keys.take(10))

        assertEquals("each requested photo once, none outside the set", 1_200, rows.size)
        assertEquals(keys.toSet(), rows.map { it.photoKey }.toSet())
    }

    @Test
    fun `upsert replaces the spelling of an existing label`() = runTest {
        put(PHOTO_A, "beach")
        dao.upsert(row(PHOTO_A, "Beach"))
        assertEquals(listOf("Beach"), labels(PHOTO_A))
    }

    @Test
    fun `delete one label of a photo leaves the rest`() = runTest {
        put(PHOTO_A, "a", "b")
        put(PHOTO_B, "a")

        assertEquals(1, dao.deleteLabelOfPhoto(USER, PHOTO_A, "a"))

        assertEquals(listOf("b"), labels(PHOTO_A))
        assertEquals(listOf("a"), labels(PHOTO_B))
    }

    @Test
    fun `delete all labels of a photo leaves other photos and accounts`() = runTest {
        put(PHOTO_A, "a", "b")
        put(PHOTO_B, "c")
        put(PHOTO_A, "z", userId = OTHER)

        assertEquals(2, dao.deleteAllOfPhoto(USER, PHOTO_A))

        assertTrue(labels(PHOTO_A).isEmpty())
        assertEquals(listOf("c"), labels(PHOTO_B))
        assertEquals(listOf("z"), labels(PHOTO_A, OTHER))
    }

    @Test
    fun `delete for photo keys clears every owner and handles a large set`() = runTest {
        val keys = (0 until 1_100).map { "content://media/$it" }
        dao.insertIgnore(keys.map { row(it, "bulk") })
        put(PHOTO_A, "a")
        put(PHOTO_A, "g", userId = GUEST)
        put(PHOTO_B, "b")

        val deleted = dao.deleteForPhotoKeys(keys + PHOTO_A)

        assertEquals(1_102, deleted)
        assertTrue(labels(PHOTO_A).isEmpty())
        assertTrue(labels(PHOTO_A, GUEST).isEmpty())
        assertEquals(listOf("b"), labels(PHOTO_B))
    }

    @Test
    fun `replace swaps a photo's whole list`() = runTest {
        put(PHOTO_A, "a", "b", "c")
        dao.replaceLabelsOfPhoto(USER, PHOTO_A, listOf(row(PHOTO_A, "x"), row(PHOTO_A, "b")))
        assertEquals(listOf("x", "b"), labels(PHOTO_A))

        dao.replaceLabelsOfPhoto(USER, PHOTO_A, emptyList())
        assertTrue(labels(PHOTO_A).isEmpty())
    }

    @Test
    fun `bulk add skips present labels, honours the cap and counts capped photos`() = runTest {
        put(PHOTO_A, "a", "b", "c", "d") // room for one
        put(PHOTO_B, "a", "b", "c", "d", "e") // full
        put(PHOTO_C, "new") // already has one of the new labels

        val result = dao.addLabelsToPhotos(USER, listOf(PHOTO_A, PHOTO_B, PHOTO_C, PHOTO_D), listOf("new", "other"), 9_000L)

        assertEquals(listOf("a", "b", "c", "d", "new"), labels(PHOTO_A))
        assertEquals(listOf("a", "b", "c", "d", "e"), labels(PHOTO_B))
        assertEquals(listOf("new", "other"), labels(PHOTO_C))
        assertEquals(listOf("new", "other"), labels(PHOTO_D))
        assertEquals("A, C and D gained a label", 3, result.changedPhotos)
        assertEquals("A lost one to the cap, B both", 2, result.cappedPhotos)
    }

    @Test
    fun `bulk add over more photos than the host-variable limit lands on every photo`() = runTest {
        val keys = (0 until 1_300).map { "content://media/$it" }
        dao.insertIgnore(listOf(row(keys.last(), "keep")))

        val result = dao.addLabelsToPhotos(USER, keys, listOf("trip"), 9_000L)

        assertEquals(1_300, result.changedPhotos)
        assertEquals(0, result.cappedPhotos)
        assertEquals(1_301, dao.labelsForKeys(USER, keys).size)
        assertEquals(listOf("keep", "trip"), labels(keys.last()))
    }

    @Test
    fun `bulk add of nothing changes nothing`() = runTest {
        assertEquals(LabelBulkResult(0, 0), dao.addLabelsToPhotos(USER, listOf(PHOTO_A), emptyList(), 1L))
        assertTrue(labels(PHOTO_A).isEmpty())
    }

    @Test
    fun `spelling of returns the first spelling the account gave`() = runTest {
        put(PHOTO_A, "Beach")
        put(PHOTO_B, "beach")
        assertEquals("Beach", dao.spellingOf(USER, "beach"))
        assertNull(dao.spellingOf(USER, "missing"))
        assertNull(dao.spellingOf(OTHER, "beach"))
    }

    @Test
    fun `rename changes the label on every photo of the account only`() = runTest {
        put(PHOTO_A, "beach", "sun")
        put(PHOTO_B, "beach")
        put(PHOTO_A, "beach", userId = OTHER)

        val moved = dao.renameLabel(USER, "beach", "seaside", "Seaside")

        assertEquals(2, moved)
        assertEquals(listOf("Seaside", "sun"), labels(PHOTO_A))
        assertEquals(listOf("Seaside"), labels(PHOTO_B))
        assertEquals(listOf("beach"), labels(PHOTO_A, OTHER))
    }

    @Test
    fun `rename to the same folded key only changes the spelling`() = runTest {
        put(PHOTO_A, "beach")
        put(PHOTO_B, "BEACH")

        assertEquals(0, dao.renameLabel(USER, "beach", "beach", "Beach"))

        assertEquals(listOf("Beach"), labels(PHOTO_A))
        assertEquals(listOf("Beach"), labels(PHOTO_B))
    }

    @Test
    fun `rename into an existing label merges and never exceeds the cap`() = runTest {
        put(PHOTO_A, "a", "b", "c", "beach", "sea") // full, carries both
        put(PHOTO_B, "beach") // only the old one
        put(PHOTO_C, "sea") // only the target

        dao.renameLabel(USER, "beach", "sea", "Sea")

        assertEquals("the duplicate is dropped, the photo keeps its own", listOf("a", "b", "c", "Sea"), labels(PHOTO_A))
        assertEquals(listOf("Sea"), labels(PHOTO_B))
        assertEquals(listOf("Sea"), labels(PHOTO_C))
        assertTrue(dao.labelsForKeys(USER, listOf(PHOTO_A, PHOTO_B, PHOTO_C)).none { it.labelKey == "beach" })
        assertTrue(dao.labelsForKeys(USER, listOf(PHOTO_A, PHOTO_B, PHOTO_C)).groupBy { it.photoKey }.values.all { it.size <= 5 })
    }

    @Test
    fun `delete a label everywhere leaves other labels and accounts`() = runTest {
        put(PHOTO_A, "a", "b")
        put(PHOTO_B, "a")
        put(PHOTO_A, "a", userId = OTHER)

        assertEquals(2, dao.deleteLabelEverywhere(USER, "a"))

        assertEquals(listOf("b"), labels(PHOTO_A))
        assertTrue(labels(PHOTO_B).isEmpty())
        assertEquals(listOf("a"), labels(PHOTO_A, OTHER))
    }

    @Test
    fun `usage counts photos per label, most used first, with the first spelling`() = runTest {
        put(PHOTO_A, "Beach", "sun")
        put(PHOTO_B, "beach")
        put(PHOTO_C, "BEACH", "sun", "zoo")
        put(PHOTO_D, "beach", userId = OTHER)

        val usage = dao.usage(USER)

        assertEquals(
            listOf(LabelUsage("beach", "Beach", 3), LabelUsage("sun", "sun", 2), LabelUsage("zoo", "zoo", 1)),
            usage,
        )
        assertEquals(usage, dao.observeUsage(USER).first())
    }

    @Test
    fun `rekey moves a device-keyed photo's labels to its cloud key`() = runTest {
        put(URI_A, "a", "b")

        val moved = dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals(2, moved)
        assertTrue(labels(URI_A).isEmpty())
        assertEquals(listOf("a", "b"), labels(LINK_A))
    }

    @Test
    fun `rekey collision keeps the cloud key's labels and drops the duplicate`() = runTest {
        put(LINK_A, "Beach")
        put(URI_A, "beach", "sun")

        val moved = dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals(1, moved)
        assertEquals(listOf("Beach", "sun"), labels(LINK_A))
        assertTrue(labels(URI_A).isEmpty())
    }

    @Test
    fun `rekey caps the union at five and keeps the existing ones first`() = runTest {
        put(LINK_A, "a", "b", "c", "d")
        put(URI_A, "x", "y", "a")

        dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals(listOf("a", "b", "c", "d", "x"), labels(LINK_A))
        assertTrue(labels(URI_A).isEmpty())
    }

    @Test
    fun `rekey leaves other photos and accounts alone and skips a self pair`() = runTest {
        put(URI_A, "a")
        put(URI_B, "b")
        put(URI_A, "o", userId = OTHER)

        dao.rekeyPhotos(USER, listOf(URI_A to LINK_A, URI_B to URI_B, "content://none" to LINK_C))

        assertEquals(listOf("a"), labels(LINK_A))
        assertEquals(listOf("b"), labels(URI_B))
        assertEquals(listOf("o"), labels(URI_A, OTHER))
        assertTrue(labels(LINK_C).isEmpty())
    }

    @Test
    fun `rekey over many pairs crosses batch boundaries`() = runTest {
        val pairs = (0 until 1_050).map { "content://media/$it" to "link-$it" }
        dao.insertIgnore(pairs.map { row(it.first, "x") })

        assertEquals(1_050, dao.rekeyPhotos(USER, pairs))
        assertEquals(1_050, dao.labelsForKeys(USER, pairs.map { it.second }).size)
        assertTrue(dao.labelsForKeys(USER, pairs.map { it.first }).isEmpty())
    }

    @Test
    fun `adopt moves guest rows into an account with no labels`() = runTest {
        put(URI_A, "a", "b", userId = GUEST)
        put(URI_B, "c", userId = GUEST)

        assertEquals(3, dao.adoptUserRows(GUEST, USER))

        assertEquals(listOf("a", "b"), labels(URI_A))
        assertEquals(listOf("c"), labels(URI_B))
        assertFalse(dao.hasAnyForUser(GUEST))
    }

    @Test
    fun `adopt merges into the account's labels with the cap and leaves no guest row`() = runTest {
        put(URI_A, "a", "b", "c", "d") // account already labelled this photo
        put(URI_A, "A", "x", "y", userId = GUEST)
        put(URI_B, "g", userId = GUEST)
        put(URI_C, "own")

        val adopted = dao.adoptUserRows(GUEST, USER)

        assertEquals("x onto A, g onto B", 2, adopted)
        assertEquals(listOf("a", "b", "c", "d", "x"), labels(URI_A))
        assertEquals(listOf("g"), labels(URI_B))
        assertEquals(listOf("own"), labels(URI_C))
        assertFalse(dao.hasAnyForUser(GUEST))
        assertTrue(dao.photoKeysForUser(GUEST).isEmpty())
    }

    @Test
    fun `adopt into itself is a no-op`() = runTest {
        put(URI_A, "a", userId = GUEST)
        assertEquals(0, dao.adoptUserRows(GUEST, GUEST))
        assertEquals(listOf("a"), labels(URI_A, GUEST))
    }

    @Test
    fun `delete all for a user leaves other accounts`() = runTest {
        put(PHOTO_A, "a")
        put(PHOTO_B, "b")
        put(PHOTO_A, "z", userId = OTHER)

        assertEquals(2, dao.deleteAllForUser(USER))

        assertFalse(dao.hasAnyForUser(USER))
        assertEquals(listOf("z"), labels(PHOTO_A, OTHER))
    }

    @Test
    fun `rows that fit keeps target labels and fills up to the cap`() {
        val target = listOf("a", "b", "c").map { row("k", it) }
        val incoming = listOf("B", "d", "e", "f").map { row("k", it) }
        assertEquals(listOf("d", "e"), rowsThatFit(target, incoming).map { it.label })
        assertTrue(rowsThatFit(listOf("1", "2", "3", "4", "5").map { row("k", it) }, incoming).isEmpty())
    }

    private companion object {
        const val USER = "user-1"
        const val OTHER = "user-2"
        const val GUEST = "local"
        const val PHOTO_A = "photo-a"
        const val PHOTO_B = "photo-b"
        const val PHOTO_C = "photo-c"
        const val PHOTO_D = "photo-d"
        const val URI_A = "content://media/external/images/media/1"
        const val URI_B = "content://media/external/images/media/2"
        const val URI_C = "content://media/external/images/media/3"
        const val LINK_A = "link-a"
        const val LINK_C = "link-c"
    }
}
