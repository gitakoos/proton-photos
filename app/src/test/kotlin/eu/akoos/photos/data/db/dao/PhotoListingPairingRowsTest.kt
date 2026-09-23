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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * [PhotoListingDao.getOwnStreamPairingRows] must hand reconcile exactly what the timeline stream it
 * replaces held: the same rows in the same order, ties on the capture time included, since the
 * pairing lookups keep the last row that shares a key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoListingPairingRowsTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoListingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoListingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        linkId: String,
        captureTime: Long,
        name: String = "$linkId.jpg",
        size: Long = 1024L,
        hash: String? = null,
        userId: String = "user1",
        isChildOfAlbum: Boolean = false,
    ) = PhotoListingEntity(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        userId = userId,
        captureTime = captureTime,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        revisionId = "rev-$linkId",
        contentHash = hash,
        tagsCsv = "1",
        isChildOfAlbum = isChildOfAlbum,
    )

    /** The timeline stream rows, cut down to the pairing columns. */
    private suspend fun streamAsPairingRows(userId: String): List<PhotoPairingRow> =
        dao.observeOwnStreamLite(userId).first().map {
            PhotoPairingRow(it.linkId, it.contentHash, it.captureTime, it.displayName, it.sizeBytes)
        }

    @Test
    fun `pairing rows are the stream rows in the stream order, ties included`() = runTest {
        dao.insertAll(
            listOf(
                entity("c", captureTime = 500L, hash = "H1"),
                entity("a", captureTime = 500L, hash = "H1"),
                entity("e", captureTime = 0L, name = "same.jpg"),
                entity("b", captureTime = 900L, hash = ""),
                entity("d", captureTime = 500L, name = "same.jpg"),
                entity("f", captureTime = 0L, name = "same.jpg", size = 1L),
            ),
        )
        // A replace deletes and re-inserts, so the row lands at a new position among its ties.
        dao.insertAll(listOf(entity("c", captureTime = 500L, hash = "H2")))

        val rows = dao.getOwnStreamPairingRows("user1")

        assertEquals(streamAsPairingRows("user1"), rows)
        assertEquals(6, rows.size)
        assertEquals("H2", rows.single { it.linkId == "c" }.contentHash)
        assertEquals("an empty content hash is read as stored", "", rows.single { it.linkId == "b" }.contentHash)
    }

    @Test
    fun `album-only photos and other accounts are left out, as the stream leaves them out`() = runTest {
        dao.insertAll(
            listOf(
                entity("own", captureTime = 100L),
                entity("albumChild", captureTime = 200L, isChildOfAlbum = true),
                entity("otherUser", captureTime = 300L, userId = "user2"),
            ),
        )

        val rows = dao.getOwnStreamPairingRows("user1")

        assertEquals(listOf("own"), rows.map { it.linkId })
        assertEquals(streamAsPairingRows("user1"), rows)
        assertEquals(streamAsPairingRows("user2"), dao.getOwnStreamPairingRows("user2"))
    }

    @Test
    fun `a shuffled library with many ties keeps the stream order row for row`() = runTest {
        val random = Random(42)
        val entities = List(600) { i ->
            entity(
                linkId = "link$i",
                // Few distinct capture times, so most rows tie with others.
                captureTime = random.nextInt(0, 12).toLong() * 100L,
                name = "IMG_${random.nextInt(0, 20)}.jpg",
                size = random.nextInt(0, 5).toLong(),
                hash = listOf(null, "", "Ha", "Hb", "Hc")[random.nextInt(0, 5)],
                userId = if (random.nextInt(10) == 0) "user2" else "user1",
                isChildOfAlbum = random.nextInt(8) == 0,
            )
        }
        for (chunk in entities.shuffled(random).chunked(37)) dao.insertAll(chunk)
        // Rewrite a slice so replaced rows move among their ties, and drop a few.
        dao.insertAll(entities.shuffled(random).take(90).map { it.copy(sizeBytes = it.sizeBytes + 1) })
        dao.deleteByLinkIds("user1", entities.shuffled(random).take(40).map { it.linkId })

        val rows = dao.getOwnStreamPairingRows("user1")

        assertTrue("the fixture must hold a large stream", rows.size > 300)
        assertEquals(streamAsPairingRows("user1"), rows)
    }
}
