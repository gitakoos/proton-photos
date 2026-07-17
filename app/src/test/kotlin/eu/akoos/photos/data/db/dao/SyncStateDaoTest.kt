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
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.domain.entity.SyncStatus
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStateDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: SyncStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.syncStateDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        uri: String,
        userId: String = "user1",
        status: SyncStatus = SyncStatus.LOCAL_ONLY,
        cloudFileId: String? = null,
        backedUpAtMs: Long? = null,
    ) = SyncStateEntity(
        localUri = uri,
        userId = userId,
        cloudFileId = cloudFileId,
        localHash = "",
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = 1024L,
    )

    @Test
    fun `upsert and observeAll returns entity for user`() = runTest {
        dao.upsert(entity("uri://1"))

        val result = dao.observeAll("user1").first()

        assertEquals(1, result.size)
        assertEquals("uri://1", result.first().localUri)
    }

    @Test
    fun `observeAll filters by userId`() = runTest {
        dao.upsert(entity("uri://1", "user1"))
        dao.upsert(entity("uri://2", "user2"))

        assertEquals(1, dao.observeAll("user1").first().size)
        assertEquals(1, dao.observeAll("user2").first().size)
    }

    @Test
    fun `upsert replaces existing entity with same localUri`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED))

        val result = dao.observeAll("user1").first()
        assertEquals(1, result.size)
        assertEquals(SyncStatus.SYNCED, result.first().status)
    }

    @Test
    fun `upsertAll inserts multiple entities`() = runTest {
        dao.upsertAll(listOf(entity("uri://1"), entity("uri://2"), entity("uri://3")))

        assertEquals(3, dao.observeAll("user1").first().size)
    }

    @Test
    fun `upsert of an existing row preserves its queue columns`() = runTest {
        // A row queued for upload (as the enqueue paths leave it via markQueued).
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://1", source = "MANUAL", at = 12345L)

        // A later reconcile re-upserts a plain domain SyncState (queue columns default to un-queued).
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))

        val row = dao.getByUri("uri://1")
        assertNotNull(row)
        assertTrue(row!!.queued)
        assertEquals("MANUAL", row.queueSource)
        assertEquals(12345L, row.queuedAt)
    }

    @Test
    fun `upsert of an existing row still updates its domain columns`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://1", source = "MANUAL", at = 12345L)

        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))

        val row = dao.getByUri("uri://1")
        assertNotNull(row)
        assertEquals(SyncStatus.SYNCED, row!!.status)
        assertEquals("cloud-1", row.cloudFileId)
        // ...while the queue intent set earlier survives the domain-only upsert.
        assertTrue(row.queued)
        assertEquals("MANUAL", row.queueSource)
    }

    @Test
    fun `upsertAll preserves queue columns on existing rows`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://1", source = "ALBUM_ADD", at = 777L)

        dao.upsertAll(listOf(
            entity("uri://1", status = SyncStatus.LOCAL_ONLY),
            entity("uri://2", status = SyncStatus.LOCAL_ONLY),
        ))

        assertEquals(2, dao.observeAll("user1").first().size)
        val row1 = dao.getByUri("uri://1")
        assertNotNull(row1)
        assertTrue(row1!!.queued)
        assertEquals("ALBUM_ADD", row1.queueSource)
        // A brand-new row inserted by the same batch is un-queued (INSERT defaults).
        assertEquals(false, dao.getByUri("uri://2")?.queued)
    }

    @Test
    fun `getByUri returns entity when present`() = runTest {
        dao.upsert(entity("uri://1"))

        val result = dao.getByUri("uri://1")

        assertNotNull(result)
        assertEquals("uri://1", result?.localUri)
    }

    @Test
    fun `getByUri returns null when absent`() = runTest {
        val result = dao.getByUri("nonexistent")
        assertNull(result)
    }

    @Test
    fun `getByCloudId returns entity when cloud id matches`() = runTest {
        dao.upsert(entity("uri://1", cloudFileId = "cloud-abc"))

        val result = dao.getByCloudId("cloud-abc")

        assertNotNull(result)
        assertEquals("uri://1", result?.localUri)
    }

    @Test
    fun `getByCloudId returns null when no match`() = runTest {
        dao.upsert(entity("uri://1", cloudFileId = "cloud-abc"))

        assertNull(dao.getByCloudId("cloud-xyz"))
    }

    @Test
    fun `getSyncedBefore returns only SYNCED items backed up before threshold`() = runTest {
        dao.upsertAll(listOf(
            entity("uri://synced-old", status = SyncStatus.SYNCED, backedUpAtMs = 100L),
            entity("uri://synced-recent", status = SyncStatus.SYNCED, backedUpAtMs = 900L),
            entity("uri://local-only", status = SyncStatus.LOCAL_ONLY, backedUpAtMs = 50L),
        ))

        val result = dao.getSyncedBefore("user1", 500L)

        assertEquals(1, result.size)
        assertEquals("uri://synced-old", result.first().localUri)
    }

    @Test
    fun `LOCAL_ONLY items are excluded from getSyncedBefore`() = runTest {
        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY, backedUpAtMs = 1L))

        assertTrue(dao.getSyncedBefore("user1", Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `getSyncedBefore filters by userId`() = runTest {
        // Both rows are reclaimable on status, stamp and cutoff; only the account differs, so the
        // other account's row must not surface as a free-up-space candidate.
        dao.upsertAll(listOf(
            entity("uri://mine", "user1", status = SyncStatus.SYNCED, backedUpAtMs = 100L),
            entity("uri://theirs", "user2", status = SyncStatus.SYNCED, backedUpAtMs = 100L),
        ))

        val result = dao.getSyncedBefore("user1", 500L)

        assertEquals(1, result.size)
        assertEquals("uri://mine", result.first().localUri)
        assertEquals(listOf("uri://theirs"), dao.getSyncedBefore("user2", 500L).map { it.localUri })
    }

    @Test
    fun `updateStatus changes the status of an existing entity`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))

        dao.updateStatus("uri://1", SyncStatus.SYNCED)

        assertEquals(SyncStatus.SYNCED, dao.getByUri("uri://1")?.status)
    }

    @Test
    fun `delete removes the entity`() = runTest {
        dao.upsert(entity("uri://1"))

        dao.delete("uri://1")

        assertNull(dao.getByUri("uri://1"))
        assertTrue(dao.observeAll("user1").first().isEmpty())
    }
}
