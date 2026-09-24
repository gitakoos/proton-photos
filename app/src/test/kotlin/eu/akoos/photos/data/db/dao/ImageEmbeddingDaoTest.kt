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
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Just the embedding table, so these tests need nothing else from the app's schema. */
@Database(entities = [ImageEmbeddingEntity::class], version = 1, exportSchema = false)
abstract class ImageEmbeddingDaoTestDatabase : RoomDatabase() {
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
}

/**
 * The backup re-key: a photo embedded under its device content URI and then backed up must end with
 * exactly one row, under its Drive linkId. Either its own row moved there, or a row the linkId already
 * held won and the device-keyed one was dropped. Nothing else in the table, and nothing in another
 * account, may move with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageEmbeddingDaoTest {

    private lateinit var db: ImageEmbeddingDaoTestDatabase
    private lateinit var dao: ImageEmbeddingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImageEmbeddingDaoTestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.imageEmbeddingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun blob(fill: Int) = ByteArray(8) { fill.toByte() }

    private fun embedding(photoKey: String, userId: String = USER, fill: Int = DEVICE_VECTOR) =
        ImageEmbeddingEntity(userId, photoKey, blob(fill), modelVersion = 2, indexedAt = 1_000L)

    private suspend fun blobAt(photoKey: String, userId: String = USER): ByteArray? =
        dao.allEmbeddingsForUser(userId).firstOrNull { it.photoKey == photoKey }?.embedding

    @Test
    fun `a device-keyed row moves to its cloud key`() = runTest {
        dao.upsert(embedding(URI_A))

        val moved = dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals(1, moved)
        assertEquals("the photo is keyed by its linkId only", listOf(LINK_A), dao.scannedKeysForUser(USER))
        assertArrayEquals("the same vector moved, nothing was re-embedded", blob(DEVICE_VECTOR), blobAt(LINK_A))
    }

    @Test
    fun `an existing cloud-keyed row wins and the device-keyed row is dropped`() = runTest {
        dao.upsert(listOf(embedding(URI_A), embedding(LINK_A, fill = CLOUD_VECTOR)))

        val moved = dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals("nothing moved: the device row was dropped instead", 0, moved)
        assertEquals("one row for the photo, no orphan", listOf(LINK_A), dao.scannedKeysForUser(USER))
        assertArrayEquals("the row the linkId already held is the one kept", blob(CLOUD_VECTOR), blobAt(LINK_A))
    }

    @Test
    fun `rows the pairs do not name are left alone`() = runTest {
        dao.upsert(listOf(embedding(URI_A), embedding(URI_B, fill = 21), embedding(LINK_C, fill = 22)))

        dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals(setOf(LINK_A, URI_B, LINK_C), dao.scannedKeysForUser(USER).toSet())
        assertArrayEquals(blob(21), blobAt(URI_B))
        assertArrayEquals(blob(22), blobAt(LINK_C))
    }

    @Test
    fun `another account's rows neither move nor count as a clash`() = runTest {
        // The same device photo embedded under a second account, which also holds the cloud key.
        dao.upsert(
            listOf(
                embedding(URI_A),
                embedding(URI_A, userId = OTHER_USER, fill = 31),
                embedding(LINK_A, userId = OTHER_USER, fill = 32),
            ),
        )

        val moved = dao.rekeyPhotos(USER, listOf(URI_A to LINK_A))

        assertEquals("the other account's linkId row is no clash for this account", 1, moved)
        assertArrayEquals(blob(DEVICE_VECTOR), blobAt(LINK_A))
        assertEquals(setOf(URI_A, LINK_A), dao.scannedKeysForUser(OTHER_USER).toSet())
        assertArrayEquals(blob(31), blobAt(URI_A, OTHER_USER))
        assertArrayEquals(blob(32), blobAt(LINK_A, OTHER_USER))
    }

    @Test
    fun `more than 500 pairs are processed in chunks`() = runTest {
        val allPairs = List(1_201) { "content://media/external/images/media/${2_000_000 + it}" to "link-$it" }
        // Clashes on both sides of each 500-pair boundary, so every batch meets both outcomes.
        val clashing = setOf(0, 499, 500, 999, 1_000, 1_200)
        dao.upsert(allPairs.map { (uri, _) -> embedding(uri) })
        dao.upsert(clashing.map { embedding(allPairs[it].second, fill = CLOUD_VECTOR) })
        // Records each transaction rekeyPhotos opens: its own default body runs here, and every batch
        // it cuts goes on to the real DAO.
        val batchSizes = mutableListOf<Int>()
        val counting = object : ImageEmbeddingDao by dao {
            override suspend fun rekeyPhotos(userId: String, pairs: List<Pair<String, String>>): Int =
                super<ImageEmbeddingDao>.rekeyPhotos(userId, pairs)

            override suspend fun rekeyPhotoBatch(userId: String, pairs: List<Pair<String, String>>): Int {
                batchSizes += pairs.size
                return dao.rekeyPhotoBatch(userId, pairs)
            }
        }

        val moved = counting.rekeyPhotos(USER, allPairs)

        assertEquals("one transaction per 500 pairs", listOf(500, 500, 201), batchSizes)
        assertEquals(allPairs.size - clashing.size, moved)
        val rows = dao.allEmbeddingsForUser(USER).associate { it.photoKey to it.embedding }
        assertEquals("one row per photo, every one under its linkId", allPairs.map { it.second }.toSet(), rows.keys)
        allPairs.forEachIndexed { i, (_, linkId) ->
            val expected = if (i in clashing) CLOUD_VECTOR else DEVICE_VECTOR
            assertArrayEquals("pair $i", blob(expected), rows[linkId])
        }
    }

    @Test
    fun `a self pair and a device key with no row change nothing`() = runTest {
        dao.upsert(embedding(LINK_A, fill = CLOUD_VECTOR))

        // A self pair must not drop the row it names; a pair whose device key holds no row, whether its
        // cloud key is taken or free, must neither touch nor create anything.
        val moved = dao.rekeyPhotos(USER, listOf(LINK_A to LINK_A, URI_B to LINK_A, URI_C to LINK_C))

        assertEquals(0, moved)
        assertEquals(listOf(LINK_A), dao.scannedKeysForUser(USER))
        assertArrayEquals(blob(CLOUD_VECTOR), blobAt(LINK_A))
    }

    @Test
    fun `the single-photo re-key follows the same rule and a re-run is a no-op`() = runTest {
        dao.upsert(listOf(embedding(URI_A), embedding(URI_B), embedding(LINK_B, fill = CLOUD_VECTOR)))

        assertTrue("a free linkId takes the row", dao.rekeyPhoto(USER, URI_A, LINK_A))
        assertFalse("a taken linkId keeps its own row", dao.rekeyPhoto(USER, URI_B, LINK_B))
        assertFalse("already moved, so a second walk finds nothing to do", dao.rekeyPhoto(USER, URI_A, LINK_A))

        assertEquals(setOf(LINK_A, LINK_B), dao.scannedKeysForUser(USER).toSet())
        assertArrayEquals(blob(DEVICE_VECTOR), blobAt(LINK_A))
        assertArrayEquals(blob(CLOUD_VECTOR), blobAt(LINK_B))
    }

    private companion object {
        const val USER = "user-1"
        const val OTHER_USER = "user-2"
        const val URI_A = "content://media/external/images/media/1000012591"
        const val URI_B = "content://media/external/images/media/1000012592"
        const val URI_C = "content://media/external/images/media/1000012593"
        const val LINK_A = "link-a"
        const val LINK_B = "link-b"
        const val LINK_C = "link-c"

        /** Blob fills telling the two sources apart: the vector embedded from the device file, and one
         *  a cloud key already held. */
        const val DEVICE_VECTOR = 11
        const val CLOUD_VECTOR = 12
    }
}
