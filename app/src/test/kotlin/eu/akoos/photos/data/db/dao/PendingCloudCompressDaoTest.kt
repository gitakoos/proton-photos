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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The compression queue a selection of thousands is worked through: slices, retries, resume, cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingCloudCompressDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PendingCloudCompressDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.pendingCloudCompressDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: String, at: Long = 1L) = PendingCloudCompressEntity(linkId = id, userId = "u", enqueuedAt = at)

    @Test
    fun `ten thousand queued photos are read a slice at a time in queue order`() = runTest {
        val ids = (0 until 10_000).map { "link-%05d".format(it) }
        ids.chunked(500).forEachIndexed { slice, chunk -> dao.insertAll(chunk.map { row(it, at = 1_000L + slice) }) }
        assertEquals(10_000, dao.count())

        var seen = 0
        while (true) {
            val slice = dao.nextSlice("run", 20)
            if (slice.isEmpty()) break
            assertTrue(slice.size <= 20)
            assertEquals(ids.subList(seen, seen + slice.size), slice.map { it.linkId })
            slice.forEach { dao.delete(it.linkId) }
            seen += slice.size
        }
        assertEquals(10_000, seen)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a row kept for a retry is not served again in the same run, and is in the next`() = runTest {
        dao.insertAll(listOf(row("a"), row("b")))
        dao.markTried("a", "run1", attempts = 1)

        assertEquals(listOf("b"), dao.nextSlice("run1", 20).map { it.linkId })
        val next = dao.nextSlice("run2", 20)
        assertEquals(listOf("a", "b"), next.map { it.linkId })
        assertEquals(1, next.first().attempts)
    }

    @Test
    fun `asking again for a queued photo keeps its uploaded copy`() = runTest {
        dao.insertAll(listOf(row("a")))
        dao.setNewLinkId("a", "copy")
        dao.insertAll(listOf(row("a", at = 99L)))

        val kept = dao.nextSlice("run", 20).single()
        assertEquals("copy", kept.newLinkId)
        assertEquals(1L, kept.enqueuedAt)
    }

    @Test
    fun `a photo asked for again after a cancel is not dropped by that cancel`() = runTest {
        dao.insertAll(listOf(row("kept", at = 5L), row("uploaded", at = 5L)))
        dao.setNewLinkId("uploaded", "copy")

        dao.requeue(listOf("kept", "uploaded"), enqueuedAt = 20L)
        dao.clearNotStarted(enqueuedUpTo = 10L, running = emptyList())

        val rows = dao.nextSlice("run", 20).associateBy { it.linkId }
        assertEquals(20L, rows.getValue("kept").enqueuedAt)
        assertEquals(5L, rows.getValue("uploaded").enqueuedAt)
    }

    @Test
    fun `the move of labels and faces to the copy is remembered across runs`() = runTest {
        dao.insertAll(listOf(row("a")))
        dao.setCarriedOver("a")

        assertTrue(dao.nextSlice("run", 20).single().carriedOver)
    }

    @Test
    fun `a cancel drops what has not started and keeps the running photo and one whose copy is up`() = runTest {
        dao.insertAll(
            listOf(row("started", at = 5L), row("running", at = 5L), row("waiting", at = 5L), row("later", at = 50L)),
        )
        dao.setNewLinkId("started", "copy")

        dao.clearNotStarted(enqueuedUpTo = 10L, running = listOf("running"))

        assertEquals(listOf("running", "started", "later"), dao.nextSlice("run", 20).map { it.linkId })
    }
}
