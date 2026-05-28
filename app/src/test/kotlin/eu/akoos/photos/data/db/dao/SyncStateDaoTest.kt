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

        val result = dao.getSyncedBefore(500L)

        assertEquals(1, result.size)
        assertEquals("uri://synced-old", result.first().localUri)
    }

    @Test
    fun `LOCAL_ONLY items are excluded from getSyncedBefore`() = runTest {
        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY, backedUpAtMs = 1L))

        assertTrue(dao.getSyncedBefore(Long.MAX_VALUE).isEmpty())
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
