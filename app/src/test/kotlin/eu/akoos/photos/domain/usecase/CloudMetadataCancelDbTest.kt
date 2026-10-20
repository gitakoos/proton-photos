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
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.PendingMetadataEditDao
import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudMetadataCancelDbTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingMetadataEditDao
    private var enqueued = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.pendingMetadataEditDao()
        enqueued = 0
    }

    @After
    fun tearDown() = db.close()

    private fun row(linkId: String, enqueuedAt: Long = 1_000L, newLinkId: String? = null) = PendingMetadataEditEntity(
        linkId = linkId,
        userId = "user",
        deviceUri = null,
        newCaptureMs = 1_700_000_000_000L,
        locationMode = PendingMetadataEditEntity.MODE_UNCHANGED,
        lat = null,
        lng = null,
        description = null,
        artist = null,
        copyright = null,
        enqueuedAt = enqueuedAt,
        newLinkId = newLinkId,
    )

    private suspend fun cancel(at: Long = 2_000L, whileStopping: suspend () -> Unit = {}) =
        cancelPendingEdits(dao, cancelledAt = at, awaitStopped = whileStopping, enqueue = { enqueued++ })

    @Test
    fun `an edit whose copy is already in Drive survives the cancel and is handed back to the drain`() = runTest {
        dao.upsertAll(listOf(row("a"), row("b", newLinkId = "b-copy"), row("c")))

        cancel()

        assertEquals(listOf("b"), dao.getAll().map { it.linkId })
        assertEquals("b-copy", dao.getAll().single().newLinkId)
        assertEquals(1, enqueued)
    }

    @Test
    fun `a cancel before any copy went up empties the queue and starts nothing`() = runTest {
        dao.upsertAll(listOf(row("a"), row("b")))

        cancel()

        assertEquals(0, dao.count())
        assertEquals(0, enqueued)
    }

    @Test
    fun `a copy the drain records while it stops is not lost to the delete`() = runTest {
        dao.upsertAll(listOf(row("a"), row("b")))

        cancel(whileStopping = { dao.setNewLinkId("a", "a-copy") })

        assertEquals(listOf("a"), dao.getAll().map { it.linkId })
        assertEquals(1, enqueued)
    }

    @Test
    fun `a batch queued after the cancel is left for its own drain`() = runTest {
        dao.upsertAll(listOf(row("old", enqueuedAt = 1_000L), row("new", enqueuedAt = 3_000L)))

        cancel(at = 2_000L)

        assertEquals(listOf("new"), dao.getAll().map { it.linkId })
    }
}
