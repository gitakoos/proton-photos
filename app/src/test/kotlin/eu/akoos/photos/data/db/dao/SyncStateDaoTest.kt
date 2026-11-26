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
    fun `a Drive copy trashed here marks its device photo removed from Drive`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))
        dao.markQueued("uri://1", "MANUAL", 1L)

        dao.demoteSyncedByCloudIds(listOf("cloud-1"))

        val row = dao.getByUri("uri://1")!!
        assertEquals(SyncStatus.LOCAL_ONLY, row.status)
        assertEquals(null, row.cloudFileId)
        assertEquals("REMOVED_FROM_DRIVE", row.queueSource)
        assertEquals(false, row.queued)
    }

    @Test
    fun `an unuploadable device file leaves the queue with the moment it was set aside`() = runTest {
        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://local", "AUTO_FOLDER", 1L)
        dao.upsert(entity("uri://synced", status = SyncStatus.SYNCED, cloudFileId = "c"))

        assertEquals(1, dao.markUnuploadable("uri://local", 5_000L))
        assertEquals(0, dao.markUnuploadable("uri://synced", 5_000L))

        val row = dao.getByUri("uri://local")!!
        assertEquals("UNUPLOADABLE", row.queueSource)
        assertEquals(false, row.queued)
        assertEquals(5_000L, row.queuedAt)
        assertEquals(null, dao.getByUri("uri://synced")?.queueSource)
    }

    @Test
    fun `a reconcile write over a file set aside as unuploadable keeps it set aside`() = runTest {
        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY))
        dao.markUnuploadable("uri://local", 5_000L)

        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY))

        val row = dao.getByUri("uri://local")!!
        assertEquals("UNUPLOADABLE", row.queueSource)
        assertEquals(false, row.queued)
        assertEquals(5_000L, row.queuedAt)
    }

    @Test
    fun `only a device-only row is marked removed from Drive`() = runTest {
        dao.upsert(entity("uri://local", status = SyncStatus.LOCAL_ONLY))
        dao.upsert(entity("uri://synced", status = SyncStatus.SYNCED, cloudFileId = "c"))

        dao.markRemovedFromDrive("uri://local")
        dao.markRemovedFromDrive("uri://synced")

        assertEquals("REMOVED_FROM_DRIVE", dao.getByUri("uri://local")?.queueSource)
        assertEquals(null, dao.getByUri("uri://synced")?.queueSource)
    }

    @Test
    fun `a batch status update moves exactly the listed rows`() = runTest {
        dao.upsertAll(listOf(entity("uri://1", status = SyncStatus.SYNCED), entity("uri://2", status = SyncStatus.SYNCED), entity("uri://3", status = SyncStatus.SYNCED)))

        val changed = dao.updateStatusByLocalUris(listOf("uri://1", "uri://3", "uri://missing"), SyncStatus.CLOUD_ONLY)

        assertEquals(2, changed)
        assertEquals(SyncStatus.CLOUD_ONLY, dao.getByUri("uri://1")?.status)
        assertEquals(SyncStatus.SYNCED, dao.getByUri("uri://2")?.status)
        assertEquals(SyncStatus.CLOUD_ONLY, dao.getByUri("uri://3")?.status)
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

    // ── claimForUpload: the one gate between a row and a second copy on Drive ─────────────────────

    /** The exact string forms the repository binds, since status is persisted as the enum's name. */
    private suspend fun claim(uri: String): Int =
        dao.claimForUpload(uri, SyncStatus.UPLOADING.name, SyncStatus.LOCAL_ONLY.name)

    @Test
    fun `a queued local-only row is claimed once and reads as UPLOADING`() = runTest {
        dao.upsert(entity("uri://pending", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://pending", source = "AUTO_FOLDER", at = 10L)

        assertEquals(1, claim("uri://pending"))
        assertEquals(SyncStatus.UPLOADING, dao.getByUri("uri://pending")?.status)
    }

    @Test
    fun `a vaulted photo's row can never be claimed for upload`() = runTest {
        // A backed-up photo moved into the hidden vault: its bytes live in app-private storage, its
        // Drive copy is untouched, and claiming the row would upload that same photo a second time.
        dao.upsert(entity("uri://vaulted", status = SyncStatus.HIDDEN, cloudFileId = "cloud-1"))
        dao.markQueued("uri://vaulted", source = "MANUAL", at = 10L)

        assertEquals(0, claim("uri://vaulted"))
        assertEquals(SyncStatus.HIDDEN, dao.getByUri("uri://vaulted")?.status)
    }

    @Test
    fun `a row already claimed by another pass is not claimed again`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))

        assertEquals(1, claim("uri://1"))
        assertEquals(0, claim("uri://1"))
        assertEquals(SyncStatus.UPLOADING, dao.getByUri("uri://1")?.status)
    }

    @Test
    fun `a backed-up row is not claimed, whatever its queue flag says`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))
        dao.markQueued("uri://1", source = "MANUAL", at = 10L)

        assertEquals(0, claim("uri://1"))
        assertEquals(SyncStatus.SYNCED, dao.getByUri("uri://1")?.status)
    }

    @Test
    fun `a claim names one row and leaves every other alone`() = runTest {
        dao.upsertAll(listOf(
            entity("uri://1", status = SyncStatus.LOCAL_ONLY),
            entity("uri://2", status = SyncStatus.LOCAL_ONLY),
        ))

        assertEquals(1, claim("uri://1"))

        assertEquals(SyncStatus.LOCAL_ONLY, dao.getByUri("uri://2")?.status)
    }

    // ── clearQueuedForSynced: what a reveal drops off the row it re-pointed ───────────────────────

    @Test
    fun `re-pointing a pairing clears the whole upload intent on the restored file`() = runTest {
        // A reveal upserts the row onto the restored uri as SYNCED; an upsert-created row arrives
        // queued for the upload the pairing has just made unnecessary.
        dao.upsert(entity("content://media/91", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))
        dao.markQueued("content://media/91", source = "AUTO_FOLDER", at = 10L)

        dao.clearQueuedForSynced("content://media/91")

        val row = dao.getByUri("content://media/91")
        assertNotNull(row)
        assertEquals(false, row!!.queued)
        assertNull(row.queueSource)
        assertNull(row.queuedAt)
    }

    @Test
    fun `the LOCAL_ONLY-guarded clear cannot do a reveal's job`() = runTest {
        // Why the reveal reaches for the unguarded clear: the guarded one refuses a SYNCED row, so it
        // would leave the restored file queued and a second upload of it would follow.
        dao.upsert(entity("content://media/91", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))
        dao.markQueued("content://media/91", source = "AUTO_FOLDER", at = 10L)

        dao.clearQueued("content://media/91")

        assertTrue(dao.getByUri("content://media/91")!!.queued)
    }

    @Test
    fun `a demoted row carries no stale intent that would re-queue it`() = runTest {
        // Clearing the source as well is what keeps a row whose cloud copy is later removed from
        // looking like a stranded manual upload.
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))
        dao.markQueued("uri://1", source = "MANUAL", at = 10L)
        dao.clearQueuedForSynced("uri://1")

        dao.updateStatus("uri://1", SyncStatus.LOCAL_ONLY)

        assertNull(dao.getQueueSource("uri://1"))
    }

    // ── a link reconcile made by name ──

    @Test
    fun `a name link is marked proven only while its row still links that photo at SYNCED`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "c1").copy(linkBasis = 1))
        dao.upsert(entity("uri://2", status = SyncStatus.SYNCED, cloudFileId = "c2").copy(linkBasis = 1))
        dao.upsert(entity("uri://3", status = SyncStatus.LOCAL_ONLY, cloudFileId = "c3").copy(linkBasis = 1))
        dao.upsert(entity("uri://4", status = SyncStatus.SYNCED, cloudFileId = "c4", backedUpAtMs = 7L).copy(linkBasis = 1))

        assertEquals(1, dao.markLinkProven("user1", "uri://1", "c1", "drive-1", "sha-1", now = 100L))
        assertEquals("another photo", 0, dao.markLinkProven("user1", "uri://2", "c-other", "d", "s", now = 100L))
        assertEquals("not SYNCED", 0, dao.markLinkProven("user1", "uri://3", "c3", "d", "s", now = 100L))
        assertEquals("another account", 0, dao.markLinkProven("user2", "uri://1", "c1", "d", "s", now = 100L))
        assertEquals(1, dao.markLinkProven("user1", "uri://4", "c4", "drive-4", "sha-4", now = 100L))
        assertEquals("already proven", 0, dao.markLinkProven("user1", "uri://1", "c1", "d", "s", now = 200L))

        val one = dao.getByUri("uri://1")!!
        assertEquals(0, one.linkBasis)
        assertEquals("drive-1", one.cloudHash)
        assertEquals("sha-1", one.originalHash)
        assertEquals(100L, one.backedUpAtMs)
        assertEquals(1, dao.getByUri("uri://2")!!.linkBasis)
        assertEquals("a backup stamp already there stays", 7L, dao.getByUri("uri://4")!!.backedUpAtMs)
    }

    // ── linkIfUnpaired: the display pair repair only links a row that pairs nothing ──

    private fun proofLink(uri: String, linkId: String) = eu.akoos.photos.domain.entity.SyncState(
        localUri = uri, cloudFileId = linkId, localHash = "", cloudHash = "drive-hash", status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 50L, lastSyncSuccessMs = 50L, backedUpAtMs = 50L, sizeBytes = 2048L, linkBasis = 0,
    )

    private val user1 = me.proton.core.domain.entity.UserId("user1")

    @Test
    fun `an unpaired idle row an update left behind is linked`() = runTest {
        val repo = eu.akoos.photos.data.repository.SyncStateRepositoryImpl(dao)
        dao.upsert(entity("uri://old").copy(localHash = "stale", queueSource = "CANCELLED"))

        assertTrue(repo.linkIfUnpaired(proofLink("uri://old", "link-1"), user1))

        val row = dao.getByUri("uri://old")!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("link-1", row.cloudFileId)
        assertEquals("drive-hash", row.cloudHash)
        assertEquals(50L, row.backedUpAtMs)
        assertEquals(0, row.linkBasis)
        assertEquals(2048L, row.sizeBytes)
        assertEquals("the queue marks stay", "CANCELLED", row.queueSource)
    }

    @Test
    fun `a file with no row is linked with a new one`() = runTest {
        val repo = eu.akoos.photos.data.repository.SyncStateRepositoryImpl(dao)

        assertTrue(repo.linkIfUnpaired(proofLink("uri://new", "link-1"), user1))

        assertEquals("link-1", dao.getByUri("uri://new")!!.cloudFileId)
    }

    @Test
    fun `a paired, claimed, queued, vaulted or foreign row is never linked`() = runTest {
        val repo = eu.akoos.photos.data.repository.SyncStateRepositoryImpl(dao)
        dao.upsert(entity("uri://proven", status = SyncStatus.SYNCED, cloudFileId = "c-proven"))
        dao.upsert(entity("uri://kept", status = SyncStatus.LOCAL_ONLY, cloudFileId = "c-kept"))
        dao.upsert(entity("uri://claimed", status = SyncStatus.UPLOADING))
        dao.upsert(entity("uri://queued"))
        dao.markQueued("uri://queued", "AUTO_FOLDER", 1L)
        dao.upsert(entity("uri://vault", status = SyncStatus.HIDDEN))
        dao.upsert(entity("uri://foreign", userId = "user2"))

        for (uri in listOf("uri://proven", "uri://kept", "uri://claimed", "uri://queued", "uri://vault", "uri://foreign")) {
            assertEquals(uri, false, repo.linkIfUnpaired(proofLink(uri, "link-new"), user1))
        }

        assertEquals("c-proven", dao.getByUri("uri://proven")!!.cloudFileId)
        assertEquals("c-kept", dao.getByUri("uri://kept")!!.cloudFileId)
        assertEquals(SyncStatus.UPLOADING, dao.getByUri("uri://claimed")!!.status)
        assertEquals(SyncStatus.LOCAL_ONLY, dao.getByUri("uri://queued")!!.status)
        assertEquals(SyncStatus.HIDDEN, dao.getByUri("uri://vault")!!.status)
        assertNull(dao.getByUri("uri://foreign")!!.cloudFileId)
    }

    @Test
    fun `the synced pairs of a cloud id list every device file that holds it`() = runTest {
        dao.upsert(entity("uri://a", status = SyncStatus.SYNCED, cloudFileId = "c1"))
        dao.upsert(entity("uri://b", status = SyncStatus.SYNCED, cloudFileId = "c1"))
        dao.upsert(entity("uri://c", status = SyncStatus.CLOUD_ONLY, cloudFileId = "c1"))
        dao.upsert(entity("uri://d", userId = "user2", status = SyncStatus.SYNCED, cloudFileId = "c1"))
        dao.upsert(entity("uri://e", status = SyncStatus.SYNCED, cloudFileId = "c2"))

        val pairs = dao.syncedPairsForCloudIds("user1", listOf("c1"))

        assertEquals(setOf("uri://a", "uri://b"), pairs.map { it.localUri }.toSet())
    }

    // ── updateDomainColumnsIfNotSyncedWithCloud: reconcile cannot clobber a just-promoted upload ──

    /** The guarded domain write reconcile uses for a demotion: same columns as updateDomainColumns,
     *  but it skips a row an upload just promoted to SYNCED + cloudFileId. */
    private suspend fun guardedUpdate(
        uri: String,
        status: SyncStatus,
        cloudFileId: String?,
        localHash: String = "",
    ): Int = dao.updateDomainColumnsIfNotSyncedWithCloud(
        localUri = uri,
        userId = "user1",
        cloudFileId = cloudFileId,
        localHash = localHash,
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = null,
        sizeBytes = 1024L,
        linkBasis = 0,
    )

    @Test
    fun `the guarded write leaves a row an upload just promoted to SYNCED with a cloud id alone`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "cloud-1"))

        // Reconcile derived LOCAL_ONLY from a stale snapshot; the guard must refuse the clobber.
        val changed = guardedUpdate("uri://1", status = SyncStatus.LOCAL_ONLY, cloudFileId = null)

        assertEquals(0, changed)
        val row = dao.getByUri("uri://1")
        assertEquals(SyncStatus.SYNCED, row?.status)
        assertEquals("cloud-1", row?.cloudFileId)
    }

    @Test
    fun `the guarded write still rewrites a genuinely local-only row`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.LOCAL_ONLY))

        val changed = guardedUpdate("uri://1", status = SyncStatus.LOCAL_ONLY, cloudFileId = null, localHash = "fresh")

        assertEquals(1, changed)
        assertEquals("fresh", dao.getByUri("uri://1")?.localHash)
    }

    // ── demoteToLocalIfCloudIdMatches: demote the vanished twin, skip an upload's re-promotion ────

    @Test
    fun `an expected-id demote drops the pairing when the cloud id still matches the snapshot`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "A"))

        val changed = dao.demoteToLocalIfCloudIdMatches("uri://1", "A")

        assertEquals(1, changed)
        val row = dao.getByUri("uri://1")
        assertEquals(SyncStatus.LOCAL_ONLY, row?.status)
        assertNull(row?.cloudFileId)
    }

    @Test
    fun `an expected-id demote skips a row an upload re-promoted under a different cloud id`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "B"))

        val changed = dao.demoteToLocalIfCloudIdMatches("uri://1", "A")

        assertEquals(0, changed)
        val row = dao.getByUri("uri://1")
        assertEquals(SyncStatus.SYNCED, row?.status)
        assertEquals("B", row?.cloudFileId)
    }

    // ── getAllLite: the lean columns of one account's rows ──────────────────────────────────────

    @Test
    fun `the lean read returns the projected columns of the account's rows only`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "c-1").copy(localHash = "sha-1"))
        dao.upsert(entity("uri://2", status = SyncStatus.LOCAL_ONLY))
        dao.markQueued("uri://2", source = "MANUAL", at = 1L)
        dao.upsert(entity("uri://3", status = SyncStatus.HIDDEN, cloudFileId = "c-3"))
        dao.upsert(entity("uri://4", userId = "user2", status = SyncStatus.SYNCED, cloudFileId = "c-4"))

        val rows = dao.getAllLite("user1").sortedBy { it.localUri }

        assertEquals(
            listOf(
                SyncStateLite("uri://1", "c-1", "sha-1", SyncStatus.SYNCED, queued = false),
                SyncStateLite("uri://2", null, "", SyncStatus.LOCAL_ONLY, queued = true),
                SyncStateLite("uri://3", "c-3", "", SyncStatus.HIDDEN, queued = false),
            ),
            rows,
        )
    }

    @Test
    fun `the lean read carries the same pairing, status and queue flag as the full rows`() = runTest {
        dao.upsert(entity("uri://1", status = SyncStatus.SYNCED, cloudFileId = "c-1", backedUpAtMs = 5L))
        dao.upsert(entity("uri://2", status = SyncStatus.LOCAL_ONLY).copy(localHash = "sha-2"))
        dao.markQueued("uri://2", source = "AUTO_FOLDER", at = 2L)

        val full = dao.observeAll("user1").first()
            .map { SyncStateLite(it.localUri, it.cloudFileId, it.localHash, it.status, it.queued) }
            .sortedBy { it.localUri }

        assertEquals(full, dao.getAllLite("user1").sortedBy { it.localUri })
    }

    @Test
    fun `the lean read lists the rows in the order the full read does`() = runTest {
        for ((i, uri) in listOf("uri://c", "uri://a", "uri://d", "uri://b").withIndex()) {
            val status = if (i % 2 == 0) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY
            dao.upsert(entity(uri, status = status, cloudFileId = "c-$i"))
            dao.upsert(entity("$uri-other", userId = "user2"))
        }
        // A row rewritten in place keeps its position.
        dao.upsert(entity("uri://c", status = SyncStatus.HIDDEN, cloudFileId = "c-0"))

        assertEquals(
            dao.observeAll("user1").first().map { it.localUri },
            dao.getAllLite("user1").map { it.localUri },
        )
    }

    // ── countPendingUploads: the photos waiting to back up ─────────────────────────────────────

    @Test
    fun `the pending count counts what a pass over the full rows counts`() = runTest {
        dao.upsert(entity("uri://idle"))
        for (uri in listOf("uri://queued-1", "uri://queued-2")) {
            dao.upsert(entity(uri))
            dao.markQueued(uri, source = "MANUAL", at = 1L)
        }
        dao.upsert(entity("uri://claimed", status = SyncStatus.UPLOADING))
        dao.markQueued("uri://claimed", source = "AUTO_FOLDER", at = 2L)
        dao.upsert(entity("uri://done", status = SyncStatus.SYNCED, cloudFileId = "c-done"))
        dao.markQueued("uri://done", source = "ALBUM_ADD", at = 3L)
        dao.upsert(entity("uri://other-account", userId = "user2"))
        dao.markQueued("uri://other-account", source = "MANUAL", at = 4L)

        val fullRowCount = dao.observeAll("user1").first()
            .count { it.status == SyncStatus.LOCAL_ONLY && it.queued }

        assertEquals(2, fullRowCount)
        assertEquals(fullRowCount, dao.countPendingUploads("user1").first())
    }

    @Test
    fun `seeding and queueing many rows at once leaves the same rows as doing it one by one`() = runTest {
        val uris = (1..1200).map { "uri://bulk-$it" }
        dao.upsert(entity("uri://bulk-7", status = SyncStatus.LOCAL_ONLY).let { it.copy(sizeBytes = 99L) })

        uris.chunked(500).forEach { slice ->
            dao.seedAndMarkQueued(slice.map { entity(it) }, source = "MANUAL", at = 5L)
        }

        assertEquals(1200, dao.countPendingUploads("user1").first())
        val all = dao.getByUris(listOf("uri://bulk-7", "uri://bulk-1200", "uri://nothing"))
        assertEquals(2, all.size)
        assertTrue(all.all { it.queued && it.queueSource == "MANUAL" && it.queuedAt == 5L })
        assertEquals(SyncStatus.LOCAL_ONLY, all.first { it.localUri == "uri://bulk-7" }.status)
    }

    @Test
    fun `seeding leaves a row that started uploading or reached Drive since it was read`() = runTest {
        dao.upsert(entity("uri://claimed", status = SyncStatus.UPLOADING))
        dao.upsert(entity("uri://done", status = SyncStatus.SYNCED, cloudFileId = "c-done"))
        dao.upsert(entity("uri://hidden", status = SyncStatus.HIDDEN))

        dao.seedAndMarkQueued(
            listOf("uri://claimed", "uri://done", "uri://hidden", "uri://new").map { entity(it) },
            source = "MANUAL",
            at = 7L,
        )

        val rows = dao.getByUris(listOf("uri://claimed", "uri://done", "uri://hidden", "uri://new"))
            .associateBy { it.localUri }
        assertEquals(SyncStatus.UPLOADING, rows.getValue("uri://claimed").status)
        assertEquals(SyncStatus.SYNCED, rows.getValue("uri://done").status)
        assertEquals("c-done", rows.getValue("uri://done").cloudFileId)
        assertEquals(SyncStatus.HIDDEN, rows.getValue("uri://hidden").status)
        assertTrue(listOf("uri://claimed", "uri://done", "uri://hidden").none { rows.getValue(it).queued })
        assertTrue(rows.getValue("uri://new").queued)
    }

    @Test
    fun `clearAlbumAddIntent drops only a pending album-add upload`() = runTest {
        dao.upsert(entity("uri://album"))
        dao.markQueued("uri://album", source = "ALBUM_ADD", at = 5L)
        dao.upsert(entity("uri://manual"))
        dao.markQueued("uri://manual", source = "MANUAL", at = 5L)
        dao.upsert(entity("uri://uploading", status = SyncStatus.UPLOADING))
        dao.markQueued("uri://uploading", source = "ALBUM_ADD", at = 5L)

        assertEquals(1, dao.clearAlbumAddIntent("uri://album"))
        assertEquals(0, dao.clearAlbumAddIntent("uri://manual"))
        assertEquals(0, dao.clearAlbumAddIntent("uri://uploading"))

        val album = dao.getByUri("uri://album")!!
        assertEquals(false, album.queued)
        assertNull(album.queueSource)
        assertNull(album.queuedAt)
        assertEquals("MANUAL", dao.getByUri("uri://manual")?.queueSource)
        assertTrue(dao.getByUri("uri://manual")!!.queued)
        assertTrue(dao.getByUri("uri://uploading")!!.queued)
    }
    @Test
    fun `a cancelled manual upload whose claim a process death stranded is not queued after the restart`() = runTest {
        dao.upsert(entity("uri://cancelled", status = SyncStatus.UPLOADING))
        dao.markQueued("uri://cancelled", source = "MANUAL", at = 5L)
        dao.upsert(entity("uri://auto", status = SyncStatus.UPLOADING))
        dao.markQueued("uri://auto", source = "AUTO_FOLDER", at = 5L)

        // The user cancels while both are claimed; only the manual one is marked.
        assertEquals(1, dao.cancelManualFor("uri://cancelled"))
        assertEquals(0, dao.cancelManualFor("uri://auto"))
        // The process dies here; the next start recovers the stranded claims.
        dao.resetStaleUploadingClaims(SyncStatus.UPLOADING.name, SyncStatus.LOCAL_ONLY.name)

        val cancelled = dao.getByUri("uri://cancelled")!!
        assertEquals(SyncStatus.LOCAL_ONLY, cancelled.status)
        assertEquals(false, cancelled.queued)
        assertEquals("CANCELLED", cancelled.queueSource)
        val auto = dao.getByUri("uri://auto")!!
        assertTrue("an automatic backup keeps its queued intent", auto.queued)
    }

    // ─── getQueuedOrIn (the upload pass's candidate read) ─────────────────────

    private suspend fun seedCandidates() {
        for (n in 1..8) dao.upsert(entity("uri://$n"))
        dao.upsert(entity("uri://other", userId = "user2"))
        dao.markQueued("uri://2", "AUTO_FOLDER", 1L)
        dao.markQueued("uri://5", "MANUAL", 1L)
        dao.markQueued("uri://other", "MANUAL", 1L)
    }

    @Test
    fun `getQueuedOrIn returns the queued rows and the listed rows of the user in table order`() = runTest {
        seedCandidates()

        val rows = dao.getQueuedOrIn("user1", listOf("uri://7", "uri://3", "uri://missing"))

        assertEquals(listOf("uri://2", "uri://3", "uri://5", "uri://7"), rows.map { it.localUri })
        val expected = dao.observeAll("user1").first()
            .filter { it.queued || it.localUri in setOf("uri://7", "uri://3") }
        assertEquals(expected, rows)
    }

    @Test
    fun `getQueuedOrIn with no listed uris returns only the queued rows`() = runTest {
        seedCandidates()
        assertEquals(listOf("uri://2", "uri://5"), dao.getQueuedOrIn("user1", emptyList()).map { it.localUri })
    }

    @Test
    fun `the repository candidate read matches the full read on both sides of the bind limit`() = runTest {
        seedCandidates()
        val repo = eu.akoos.photos.data.repository.SyncStateRepositoryImpl(dao)
        val user = me.proton.core.domain.entity.UserId("user1")
        val full = dao.observeAll("user1").first()

        val few = setOf("uri://4")
        assertEquals(
            full.filter { it.queued || it.localUri in few }.map { it.localUri },
            repo.getQueuedOrIn(user, few).map { it.localUri },
        )
        // Past one statement's bind limit the repository falls back to the full read, filtered.
        val many = (1..1200).map { "uri://none$it" }.toSet() + "uri://6"
        assertEquals(
            full.filter { it.queued || it.localUri in many }.map { it.localUri },
            repo.getQueuedOrIn(user, many).map { it.localUri },
        )
    }

    private fun backedUp(uri: String, localHash: String = "up", cloudFileId: String? = "cloud-1") =
        entity(uri, status = SyncStatus.SYNCED, cloudFileId = cloudFileId, backedUpAtMs = 5L).copy(localHash = localHash)

    @Test
    fun `a recorded original hash is read back and a new row starts without one`() = runTest {
        dao.upsert(backedUp("uri://1"))
        assertNull(dao.getByUri("uri://1")!!.originalHash)

        dao.setOriginalHash("uri://1", "orig")

        assertEquals("orig", dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `a domain row written back never writes an original hash it carries`() = runTest {
        val repo = eu.akoos.photos.data.repository.SyncStateRepositoryImpl(dao)
        val user = me.proton.core.domain.entity.UserId("user1")
        // A copy of a row read before, carrying a hash that belonged to an older backup.
        repo.upsert(backedUp("uri://1").toDomain().copy(originalHash = "stale"), user)

        assertNull(dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `the original hash survives a write that keeps the row's hash and pairing`() = runTest {
        dao.upsert(backedUp("uri://1"))
        dao.setOriginalHash("uri://1", "orig")

        // A reconcile pass rewrites the same pairing with a fresh attempt time and no original hash of its own.
        dao.upsert(backedUp("uri://1").copy(lastSyncAttemptMs = 99L))
        assertEquals("orig", dao.getByUri("uri://1")!!.originalHash)

        assertEquals(1, dao.updateDomainColumnsIfNotSyncedWithCloudAll(listOf(backedUp("uri://1"))).size)
        assertEquals("orig", dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `a new hash or a new pairing clears the original hash`() = runTest {
        dao.upsert(backedUp("uri://1"))
        dao.setOriginalHash("uri://1", "orig")
        dao.upsert(backedUp("uri://1", localHash = "edited"))
        assertNull("a new device hash", dao.getByUri("uri://1")!!.originalHash)

        dao.setOriginalHash("uri://1", "orig")
        dao.upsert(backedUp("uri://1", localHash = "edited", cloudFileId = "cloud-2"))
        assertNull("a new Drive pairing", dao.getByUri("uri://1")!!.originalHash)

        dao.setOriginalHash("uri://1", "orig")
        dao.upsert(backedUp("uri://1", localHash = "edited", cloudFileId = null).copy(status = SyncStatus.LOCAL_ONLY))
        assertNull("the pairing taken off", dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `the guarded update clears the original hash of a row it rewrites with a new hash`() = runTest {
        dao.upsert(backedUp("uri://1", cloudFileId = null).copy(status = SyncStatus.LOCAL_ONLY))
        dao.setOriginalHash("uri://1", "orig")

        val untouched = dao.updateDomainColumnsIfNotSyncedWithCloudAll(
            listOf(backedUp("uri://1", localHash = "edited", cloudFileId = null).copy(status = SyncStatus.LOCAL_ONLY)),
        )

        assertTrue(untouched.isEmpty())
        assertNull(dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `a demotion off Drive clears the original hash`() = runTest {
        dao.upsert(backedUp("uri://1", cloudFileId = "cloud-1"))
        dao.upsert(backedUp("uri://2", cloudFileId = "cloud-2"))
        dao.setOriginalHash("uri://1", "orig-1")
        dao.setOriginalHash("uri://2", "orig-2")

        dao.demoteSyncedByCloudIds(listOf("cloud-1"))
        assertEquals(1, dao.demoteToLocalIfCloudIdMatches("uri://2", "cloud-2"))

        assertNull(dao.getByUri("uri://1")!!.originalHash)
        assertNull(dao.getByUri("uri://2")!!.originalHash)
    }

    @Test
    fun `clearing the original hash takes it off`() = runTest {
        dao.upsert(backedUp("uri://1"))
        dao.setOriginalHash("uri://1", "orig")

        dao.setOriginalHash("uri://1", null)

        assertNull(dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `a rewrite this app made is recorded only on a backed-up row paired to Drive`() = runTest {
        dao.upsert(backedUp("uri://synced"))
        dao.upsert(backedUp("uri://local", cloudFileId = null).copy(status = SyncStatus.LOCAL_ONLY))
        dao.upsert(entity("uri://paired-only", status = SyncStatus.SYNCED, cloudFileId = "cloud-2", backedUpAtMs = null))

        dao.recordOwnDeviceWrite("uri://synced", "up", "written")
        dao.recordOwnDeviceWrite("uri://local", "up", "written")
        dao.recordOwnDeviceWrite("uri://paired-only", "", "written")

        assertEquals("written", dao.getByUri("uri://synced")!!.originalHash)
        assertNull(dao.getByUri("uri://local")!!.originalHash)
        assertNull(dao.getByUri("uri://paired-only")!!.originalHash)
    }

    @Test
    fun `a recorded own write survives a reconcile pass that keeps the row's hash and pairing`() = runTest {
        dao.upsert(backedUp("uri://1"))
        dao.recordOwnDeviceWrite("uri://1", "up", "written")

        dao.upsert(backedUp("uri://1").copy(sizeBytes = 2048L))

        assertEquals("written", dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `a rewrite of a file edited since its backup is not recorded`() = runTest {
        dao.upsert(backedUp("uri://1"))
        dao.setOriginalHash("uri://1", "orig")

        dao.recordOwnDeviceWrite("uri://1", "edited-elsewhere", "written")

        assertEquals("orig", dao.getByUri("uri://1")!!.originalHash)
    }

    @Test
    fun `an own rewrite over a row that recorded no device bytes lands only while the row is as it was read`() = runTest {
        dao.upsert(backedUp("uri://1", localHash = "uploaded"))
        dao.upsert(backedUp("uri://2", localHash = "uploaded"))
        dao.upsert(backedUp("uri://3", localHash = "uploaded"))
        dao.setOriginalHash("uri://3", "orig")

        dao.recordOwnDeviceWriteOverRow("uri://1", "written", "uploaded", 5L)
        dao.recordOwnDeviceWriteOverRow("uri://2", "written", "uploaded", 6L)
        dao.recordOwnDeviceWriteOverRow("uri://3", "written", "uploaded", 5L)

        assertEquals("written", dao.getByUri("uri://1")!!.originalHash)
        assertNull("backed up again since it was read", dao.getByUri("uri://2")!!.originalHash)
        assertEquals("a recorded hash decides alone", "orig", dao.getByUri("uri://3")!!.originalHash)
    }

    @Test
    fun `a second own rewrite chains on the bytes the first one recorded`() = runTest {
        dao.upsert(backedUp("uri://1", localHash = "UP"))

        dao.recordOwnDeviceWrite("uri://1", "up", "first")
        dao.recordOwnDeviceWrite("uri://1", "FIRST", "second")

        assertEquals("second", dao.getByUri("uri://1")!!.originalHash)
    }
}
