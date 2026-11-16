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
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DeviceMediaScan
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStatePage
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.SQL_CHUNK_SIZE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Holds [ReconcileSyncStateUseCase] to [ReconcileReferencePass], the same pass reading its inputs
 * whole. Each fixture is seeded into two fresh databases, one per pass, and the two runs must make
 * the same repository calls with the same arguments in the same order (the table reads included, so
 * each re-read sees the same writes before it), read the same files, report the same totals, and
 * leave identical sync_state tables. Free-up-space deletes device copies on what this table records,
 * so any drift here is a data-loss risk, not a cosmetic one.
 *
 * Timestamps a pass stamps differ between two runs by nature; each is compared as "now" when it is
 * no older than the fixture, while every timestamp the fixture carries is compared as is.
 *
 * The pass reads the table a page or a uri lookup at a time where the reference reads it whole. Every
 * read is logged as the whole-table read it stands for, and a run of reads with no write between them
 * counts as one, so the two logs line up read for read. Each fixture runs the pass at several page
 * sizes, small ones included, so every page boundary falls somewhere in the fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcileEquivalenceTest {

    private lateinit var context: Context
    private val userId = UserId("user1")
    private val otherUser = "user2"
    private val volumeId = "vol1"
    private val databases = mutableListOf<AppDatabase>()
    private val passScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        passScope.cancel()
        databases.forEach { it.close() }
        // The settings store is one per process, so nothing seeded here may reach the next test.
        runCatching { runBlocking { context.settingsDataStore.edit { it.clear() } } }
    }

    private data class Settings(
        val folders: Set<String>?,
        val backupEverything: Boolean = false,
        val excluded: Set<String> = emptySet(),
        val listingComplete: Boolean = true,
        val strip: Boolean = false,
        val compress: Boolean = false,
    )

    /** One scenario: what the device holds, the listing rows, the sync_state rows and the settings.
     *  [scanComplete] is whether the device scan saw the whole device. */
    private class Fixture(
        val name: String,
        val since: Long,
        val device: List<LocalMediaItem>,
        val listing: List<PhotoListingEntity>,
        val rows: List<SyncStateEntity>,
        val sha1: Map<String, String>,
        val settings: Settings,
        val scanComplete: Boolean = true,
    ) {
        fun withIncompleteScan() = Fixture("$name, incomplete scan", since, device, listing, rows, sha1, settings, false)
    }

    private class Outcome(
        val calls: List<String>,
        val table: List<String>,
        val sha1Reads: List<String>,
        val total: Int,
        val settled: Boolean?,
        val reads: List<RecordingSyncStateRepository.Read>,
        val tableRows: Int,
    )

    private val variants = listOf(
        Settings(folders = setOf("Camera")),
        Settings(folders = setOf("Camera"), listingComplete = false),
        Settings(folders = setOf("Camera"), strip = true),
        Settings(folders = setOf("Camera"), compress = true),
        Settings(folders = setOf("Camera", "WhatsApp")),
        Settings(folders = null, backupEverything = true, excluded = setOf("WhatsApp")),
        Settings(folders = null, backupEverything = true),
        Settings(folders = null),
        Settings(folders = emptySet()),
    )

    @Test
    fun `every pairing rule decides and writes the same under every setting`() = runTest(timeout = 120.seconds) {
        for ((i, settings) in variants.withIndex()) {
            val fixture = rulesFixture("rules, settings #$i", settings)
            val reference = assertSamePass(fixture)
            if (i == 0) {
                // The fixture must reach every write the pass can make, or agreeing proves little.
                val calls = reference.calls.joinToString("\n")
                for (expected in listOf(
                    "demote ", "markRemovedFromDrive ", "|CLOUD_ONLY|", "guardedUpdate ", "markQueued ",
                    " ALBUM_ADD now", " AUTO_FOLDER now", "deleteLocalOnly ", "clearQueued content",
                )) {
                    assertTrue("the rules fixture never reached '$expected'", expected in calls)
                }
                assertTrue("the rules fixture must recompute a hash", reference.sha1Reads.isNotEmpty())
                assertTrue(
                    "an edited backed-up file keeps its pairing and loses its stamp",
                    reference.table.any { it.startsWith("content://m/28|${userId.id}|L28|s28-edited|") && "|SYNCED|" in it && it.contains("|null|2000|") },
                )
                assertTrue(
                    "the same bytes at a new size keep their stamp",
                    reference.table.any { it.startsWith("content://m/29|${userId.id}|L29|s29|") && it.contains("|12345|2000|") },
                )
            }
        }
    }

    @Test
    fun `random libraries full of shared names, hashes and times decide and write the same`() = runTest(timeout = 240.seconds) {
        for (seed in 1..12) assertSamePass(randomFixture(seed))
    }

    @Test
    fun `libraries past one page and past the host-variable limit decide and write the same`() = runTest(timeout = 600.seconds) {
        // Device files, SYNCED rows and LOCAL_ONLY rows each run past a page of 500 (and past 999 in the
        // large one), some an exact multiple of the page, so lookups and page walks cross every boundary.
        for ((seed, files) in listOf(21 to 700, 22 to 1000, 23 to 1500, 24 to 2600)) {
            val fixture = randomFixture(seed, files)
            val reference = assertSamePass(fixture, pageSizes = listOf(SQL_CHUNK_SIZE))
            assertTrue("${fixture.name}: the fixture must hold more than a page of rows", fixture.rows.size > SQL_CHUNK_SIZE)
            if (files >= 1500) {
                assertTrue("${fixture.name}: the fixture must pass the host-variable limit", fixture.rows.size > 999)
            }
            if (files >= 2600) {
                for (status in listOf(SyncStatus.SYNCED, SyncStatus.LOCAL_ONLY)) {
                    val count = fixture.rows.count { it.userId == userId.id && it.status == status }
                    assertTrue("${fixture.name}: $count $status rows, the walk must cross a page", count > SQL_CHUNK_SIZE)
                }
            }
            // The reference holds the whole table in one read; the pass holds a page at most.
            assertTrue(
                "${fixture.name}: the reference read must hold the whole account",
                reference.reads.maxOf { it.rows } >= fixture.rows.count { it.userId == userId.id },
            )
        }
    }

    @Test
    fun `a row another account holds on a device file counts as no row`() = runTest(timeout = 60.seconds) {
        val now = System.currentTimeMillis()
        val device = listOf(
            deviceFile("content://f/1", "IMG_F1.jpg"),
            deviceFile("content://f/2", "IMG_F2.jpg"),
            deviceFile("content://f/3", "IMG_F3.jpg"),
        )
        val listing = listOf(listed("LF2", "IMG_F2.jpg"))
        val rows = listOf(
            row("content://f/1", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.MANUAL, user = otherUser),
            row("content://f/2", SyncStatus.SYNCED, cloudId = "LF2", user = otherUser),
            row("content://f/3", SyncStatus.LOCAL_ONLY, user = userId.id),
        )
        val reference = assertSamePass(Fixture("foreign rows", now, device, listing, rows, emptyMap(), variants.first()))
        // The foreign rows take the plain upsert, never the guarded update an own row would get.
        assertTrue(reference.calls.none { it.startsWith("guardedUpdate ${userId.id} content://f/1|") })
        assertTrue(reference.calls.any { it.startsWith("guardedUpdate ${userId.id} content://f/3|") })
    }

    @Test
    fun `an incomplete scan demotes nothing and drops no queued row under any setting`() = runTest(timeout = 120.seconds) {
        // The rules fixture's vanished files (m/99 SYNCED, m/98 and m/97 queued) are demoted and dropped
        // on a complete scan; a scan that missed part of the device must leave every one of them.
        for ((i, settings) in variants.withIndex()) {
            val reference = assertSamePass(rulesFixture("rules, settings #$i", settings).withIncompleteScan())
            val calls = reference.calls.joinToString("\n")
            assertTrue("settings #$i: no row may be demoted to CLOUD_ONLY", "|CLOUD_ONLY|" !in calls)
            assertTrue("settings #$i: no queued row may be dropped", "deleteLocalOnly " !in calls)
        }
    }

    @Test
    fun `rows written after the scan began are left while older vanished rows are still handled`() = runTest(timeout = 60.seconds) {
        val reference = assertSamePass(rulesFixture("rules, mid-pass rows", variants.first()))
        val calls = reference.calls

        // Older rows whose files are gone: demoted and dropped as before.
        assertTrue(calls.any { it.startsWith("upsert ${userId.id} content://m/99|") && "|CLOUD_ONLY|" in it })
        val dropped = calls.single { it.startsWith("deleteLocalOnly ") }
        assertTrue("content://m/98" in dropped && "content://m/97" in dropped)

        // A download recorded and a "back up now" queued while the pass ran: left for the next pass.
        assertTrue(calls.none { it.startsWith("upsert ${userId.id} content://m/92|") })
        assertTrue("content://m/91" !in dropped)
        assertTrue(reference.table.any { it.startsWith("content://m/92|") && "|SYNCED|" in it })
        assertTrue(reference.table.any { it.startsWith("content://m/91|") && "|LOCAL_ONLY|" in it })
    }

    /** Runs the reference on [fixture], then the pass at each of [pageSizes], and fails on the first
     *  difference. Returns the reference run. */
    private suspend fun assertSamePass(fixture: Fixture, pageSizes: List<Int> = PAGE_SIZES): Outcome {
        val reference = runPass(fixture, pageRows = null)
        for (pageRows in pageSizes) {
            val production = runPass(fixture, pageRows)
            val name = "${fixture.name}, pages of $pageRows"
            assertEquals("$name: repository calls", reference.calls, production.calls)
            assertEquals("$name: sync_state after the pass", reference.table, production.table)
            assertEquals("$name: files hashed", reference.sha1Reads, production.sha1Reads)
            assertEquals("$name: total", reference.total, production.total)
            assertEquals("$name: pairing settled", reference.settled, production.settled)
            assertBoundedReads(name, production, pageRows)
        }
        return reference
    }

    /** The pass never reads the whole table, and no read returns more than one page of rows. */
    private fun assertBoundedReads(name: String, run: Outcome, pageRows: Int) {
        val whole = run.reads.filter { it.method == "observeAll" }
        assertTrue("$name: the pass read the whole table: $whole", whole.isEmpty())
        val largest = run.reads.maxOfOrNull { it.rows } ?: 0
        assertTrue("$name: a read returned $largest rows, more than a page of $pageRows", largest <= pageRows)
    }

    /** One pass on a fresh database seeded with [fixture]: the reference when [pageRows] is null, else the
     *  pass reading pages of [pageRows] rows. */
    private suspend fun runPass(fixture: Fixture, pageRows: Int?): Outcome {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
            .also { databases += it }
        db.photoListingDao().insertAll(fixture.listing)
        for (row in fixture.rows) db.syncStateDao().insertIgnore(row)
        seed(fixture.settings)

        val since = fixture.since
        val device = FixtureDevice(fixture.device, fixture.sha1, fixture.scanComplete)
        val recording = RecordingSyncStateRepository(SyncStateRepositoryImpl(db.syncStateDao())) { stamp(it, since) }
        val cloud = mockk<DrivePhotoRepository>()
        // The reference reads the listing the way the timeline stream serves it: that query, as domain rows.
        every { cloud.observeCloudPhotos(userId) } answers {
            db.photoListingDao().observeOwnStreamLite(userId.id).map { rows -> rows.map { it.toDomain() } }
        }
        every { cloud.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }

        val progress = if (pageRows != null) {
            ReconcileSyncStateUseCase(device, cloud, recording, db.photoListingDao(), context, passScope)
                .apply { readPageRows = pageRows }(userId).toList()
        } else {
            ReconcileReferencePass(device, cloud, recording, context)(userId).toList()
        }

        val table = (db.syncStateDao().observeAll(userId.id).first() + db.syncStateDao().observeAll(otherUser).first())
            .map { describe(it, since) }
            .sorted()
        val settled = context.settingsDataStore.data.first()[SettingsKeys.pairingSettledKey(userId.id)]
        return Outcome(
            recording.calls.toList(), table, device.sha1Reads.toList(), progress.last().total, settled,
            recording.reads.toList(), fixture.rows.size,
        )
    }

    private suspend fun seed(settings: Settings) {
        context.settingsDataStore.edit { p ->
            p.clear()
            settings.folders?.let { p[SettingsKeys.SYNC_FOLDER_NAMES] = it }
            p[SettingsKeys.BACKUP_EVERYTHING] = settings.backupEverything
            if (settings.excluded.isNotEmpty()) p[SettingsKeys.EXCLUDED_FOLDER_NAMES] = settings.excluded
            p[SettingsKeys.STRIP_ON_UPLOAD] = settings.strip
            p[SettingsKeys.COMPRESS_ON_UPLOAD] = settings.compress
            if (settings.listingComplete) p[SettingsKeys.photoListingEverCompleteKey(userId.id, volumeId)] = true
        }
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private fun deviceFile(uri: String, name: String, bucket: String? = "Camera", sec: Long = BASE_SEC, size: Long = 1000L) =
        LocalMediaItem(
            uri = uri,
            // A sub-second remainder, so the pass's ms-to-seconds step is exercised too.
            dateTaken = sec * 1000L + 250L,
            displayName = name,
            mimeType = "image/jpeg",
            sizeBytes = size,
            bucketName = bucket,
        )

    private fun listed(
        linkId: String,
        name: String,
        sec: Long = BASE_SEC,
        size: Long = 1000L,
        hash: String? = null,
        user: String = userId.id,
        albumChild: Boolean = false,
    ) = PhotoListingEntity(
        linkId = linkId,
        shareId = "share1",
        volumeId = volumeId,
        userId = user,
        captureTime = sec,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        revisionId = "rev-$linkId",
        contentHash = hash,
        tagsCsv = "1",
        isChildOfAlbum = albumChild,
    )

    private fun row(
        uri: String,
        status: SyncStatus,
        cloudId: String? = null,
        localHash: String = "",
        backedUpAtMs: Long? = null,
        lastSuccess: Long? = null,
        queued: Boolean = false,
        source: String? = null,
        user: String = userId.id,
        queuedAt: Long? = if (queued) 2_000L else null,
    ) = SyncStateEntity(
        localUri = uri,
        userId = user,
        cloudFileId = cloudId,
        localHash = localHash,
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 1_000L,
        lastSyncSuccessMs = lastSuccess,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = 1000L,
        queued = queued,
        queueSource = source,
        queuedAt = queuedAt,
    )

    /** One device file, listing row or table row per rule of the pass, plus look-alikes that must
     *  not match: album-only and other-account listing rows, shared hashes, tied capture times. */
    private fun rulesFixture(name: String, settings: Settings): Fixture {
        val now = System.currentTimeMillis()
        val longAgo = now - 60 * 60_000L
        val justNow = now - 60_000L
        // Written after either pass asks for its scan: a download recorded, a photo queued, mid-pass.
        val midPass = now + 60 * 60_000L
        val device = listOf(
            deviceFile("content://m/1", "IMG_0001.jpg"),
            deviceFile("content://m/2", "IMG_0002.jpg"),
            deviceFile("content://m/3", "IMG_0003.jpg"),
            deviceFile("content://m/4", "IMG_0004.jpg", sec = BASE_SEC + 4),
            deviceFile("content://m/5", "IMG_0005.jpg", size = 5555L),
            deviceFile("content://m/6", "IMG_0006.jpg", sec = BASE_SEC + 6),
            deviceFile("content://m/7", "IMG_0007.jpg", sec = BASE_SEC + 7),
            deviceFile("content://m/8", "IMG_0008.jpg"),
            deviceFile("content://m/9", "IMG_0009.jpg"),
            deviceFile("content://m/10", "IMG_0010.jpg"),
            deviceFile("content://m/11", "IMG_0011.jpg"),
            deviceFile("content://m/12", "IMG_0012.jpg"),
            deviceFile("content://m/13", "wa1.jpg", bucket = "WhatsApp"),
            deviceFile("content://m/14", "wa2.jpg", bucket = "WhatsApp"),
            deviceFile("content://m/15", "wa3.jpg", bucket = "WhatsApp"),
            deviceFile("content://m/16", "dl.jpg", bucket = "Downloads", sec = BASE_SEC + 16),
            deviceFile("content://m/17", "orphan.jpg", bucket = null),
            deviceFile("content://m/18", "dup.jpg"),
            deviceFile("content://m/19", "tie.jpg", sec = BASE_SEC + 19),
            deviceFile("content://m/20", "big.jpg"),
            deviceFile("content://m/21", "album-add.jpg"),
            deviceFile("content://m/22", "editor.jpg"),
            deviceFile("content://m/23", "stale-id.jpg"),
            deviceFile("content://m/24", "zero.jpg", size = 0L),
            deviceFile("content://m/25", "back.jpg"),
            deviceFile("content://m/26", "album-only.jpg"),
            deviceFile("content://m/27", "conflict.jpg", sec = BASE_SEC + 27),
            deviceFile("content://m/28", "edited.jpg", size = 2000L),
            deviceFile("content://m/29", "resized.jpg", size = 2000L),
        )
        val listing = listOf(
            listed("L1", "cloud-name-1.jpg", hash = "hmac-x1"),
            listed("L2", "renamed.jpg", hash = "hmac-s2"),
            listed("L3", "other3.jpg", hash = "hmac-s3"),
            listed("L4", "IMG_0004.jpg", sec = BASE_SEC + 4),
            listed("L5", "IMG_0005.jpg", sec = 0L, size = 5555L),
            listed("L6", "IMG_0006.jpg", sec = BASE_SEC + 6, hash = "hmac-other6"),
            listed("L7", "IMG_0007.jpg", sec = BASE_SEC + 7, hash = "hmac-other7"),
            listed("L8", "IMG_0008.jpg"),
            listed("L16", "dl.jpg", sec = BASE_SEC + 16),
            listed("Ldupa", "dup-a.jpg", sec = 300L, hash = "hmac-sdup"),
            listed("Ldupb", "dup-b.jpg", sec = 200L, hash = "hmac-sdup"),
            listed("Ltiea", "tie.jpg", sec = BASE_SEC + 19),
            listed("Ltieb", "tie.jpg", sec = BASE_SEC + 19),
            listed("Ltiec", "tie.jpg", sec = BASE_SEC + 19),
            listed("L20", "big-on-cloud.jpg"),
            listed("L24", "zero.jpg", sec = 0L, size = 0L),
            listed("L25", "back.jpg"),
            listed("L27", "conflict.jpg", sec = BASE_SEC + 27, hash = "hmac-other27"),
            listed("L28", "edited.jpg", hash = "hmac-s28"),
            listed("L29", "resized.jpg", hash = "hmac-s29"),
            listed("L99", "gone-from-device.jpg"),
            listed("L92", "fresh-download.jpg"),
            // Look-alikes the stream leaves out: an album-only row and another account's row.
            listed("Lalbum", "IMG_0004.jpg", sec = BASE_SEC + 4, hash = "hmac-s3", albumChild = true),
            listed("Lother", "IMG_0012.jpg", hash = "hmac-s2", user = otherUser),
        )
        val rows = listOf(
            row("content://m/1", SyncStatus.SYNCED, cloudId = "L1", lastSuccess = longAgo),
            row("content://m/2", SyncStatus.LOCAL_ONLY, localHash = "s2"),
            row("content://m/7", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.AUTO_FOLDER),
            row("content://m/8", SyncStatus.HIDDEN, cloudId = "L8"),
            row("content://m/9", SyncStatus.UPLOADING),
            row("content://m/10", SyncStatus.SYNCED, cloudId = "Lgone10", lastSuccess = longAgo, queued = true, source = QueueSource.MANUAL),
            row("content://m/11", SyncStatus.SYNCED, cloudId = "Lgone11", lastSuccess = justNow),
            row("content://m/12", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.MANUAL),
            row("content://m/13", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.AUTO_FOLDER),
            row("content://m/14", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.MANUAL),
            row("content://m/15", SyncStatus.LOCAL_ONLY, source = QueueSource.MANUAL),
            row("content://m/16", SyncStatus.SYNCED, cloudId = "L16", backedUpAtMs = 12_345L, lastSuccess = longAgo),
            row("content://m/18", SyncStatus.LOCAL_ONLY, localHash = "sdup"),
            row("content://m/20", SyncStatus.SYNCED, cloudId = "L20", backedUpAtMs = 12_345L, lastSuccess = longAgo),
            row("content://m/21", SyncStatus.LOCAL_ONLY, source = QueueSource.ALBUM_ADD),
            row("content://m/22", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.EDITOR),
            row("content://m/23", SyncStatus.LOCAL_ONLY, cloudId = "Lstale"),
            row("content://m/25", SyncStatus.CLOUD_ONLY, cloudId = "L25"),
            row("content://m/26", SyncStatus.SYNCED, cloudId = "Lalbum"),
            row("content://m/27", SyncStatus.CONFLICT),
            // Backed-up files whose size changed: one edited by another app, one with the same bytes.
            row("content://m/28", SyncStatus.SYNCED, cloudId = "L28", localHash = "s28", backedUpAtMs = 12_345L, lastSuccess = longAgo),
            row("content://m/29", SyncStatus.SYNCED, cloudId = "L29", localHash = "s29", backedUpAtMs = 12_345L, lastSuccess = longAgo),
            // Rows whose file is no longer on the device.
            row("content://m/99", SyncStatus.SYNCED, cloudId = "L7", lastSuccess = longAgo),
            row("content://m/98", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.AUTO_FOLDER),
            row("content://m/97", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.MANUAL),
            row("content://m/96", SyncStatus.SYNCED, cloudId = "Lgone96", queued = true, source = QueueSource.MANUAL),
            row("content://m/95", SyncStatus.LOCAL_ONLY, source = QueueSource.MANUAL),
            row("content://m/94", SyncStatus.HIDDEN, cloudId = "L94"),
            row("content://m/93", SyncStatus.SYNCED, cloudId = "L99", backedUpAtMs = 12_345L, lastSuccess = justNow),
            // Rows whose file the scan cannot list because they were written after it began.
            row("content://m/92", SyncStatus.SYNCED, cloudId = "L92", lastSuccess = midPass),
            row("content://m/91", SyncStatus.LOCAL_ONLY, queued = true, source = QueueSource.MANUAL, queuedAt = midPass),
            row("content://b/1", SyncStatus.SYNCED, cloudId = "Lb1", user = otherUser),
        )
        val sha1 = mapOf(
            "content://m/3" to "s3", "content://m/19" to "unknown", "content://m/24" to "s2",
            "content://m/28" to "s28-edited", "content://m/29" to "s29",
        )
        return Fixture(name, now, device, listing, rows, sha1, settings)
    }

    /** A library drawn from small pools of names, capture seconds, sizes and hashes, so keys collide
     *  everywhere, with every status and queue source on the table. */
    private fun randomFixture(seed: Int, files: Int? = null): Fixture {
        val random = Random(seed)
        val now = System.currentTimeMillis()
        val names = List(6) { "IMG_$it.jpg" }
        val seconds = listOf(0L, BASE_SEC, BASE_SEC + 1, BASE_SEC + 2)
        val sizes = listOf(0L, 10L, 20L)
        val hashes = listOf(null, "", "hmac-a", "hmac-b", "hmac-c")
        val buckets = listOf("Camera", "Camera", "WhatsApp", "Downloads", null)
        val sources = listOf(null, QueueSource.AUTO_FOLDER, QueueSource.MANUAL, QueueSource.ALBUM_ADD, QueueSource.EDITOR)
        val successes = listOf(null, now - 60 * 60_000L, now - 60_000L)

        val device = List(files ?: random.nextInt(40, 90)) { i ->
            deviceFile("content://r/$i", names.random(random), buckets.random(random), seconds.random(random), sizes.random(random))
        }
        val listing = List(files?.let { it * 3 / 4 } ?: random.nextInt(30, 80)) { j ->
            listed(
                linkId = "L$j",
                name = names.random(random),
                sec = seconds.random(random),
                size = sizes.random(random),
                hash = hashes.random(random),
                user = if (random.nextInt(12) == 0) otherUser else userId.id,
                albumChild = random.nextInt(10) == 0,
            )
        }
        val cloudIds: List<String?> = listing.map { it.linkId } + listOf("Lmissing1", "Lmissing2", null, null, null)
        val uris = device.map { it.uri } + List(files?.let { it / 4 } ?: 15) { "content://gone/$it" }
        // A large library is mostly SYNCED and LOCAL_ONLY rows, so each runs past a page.
        val statuses = if (files == null) {
            SyncStatus.entries
        } else {
            List(4) { SyncStatus.SYNCED } + List(4) { SyncStatus.LOCAL_ONLY } + SyncStatus.entries
        }
        val (owned, unowned) = uris.partition { random.nextInt(3) != 0 }
        // Some device files without a row of this account's carry another account's row instead.
        val foreign = unowned.filter { random.nextInt(4) == 0 }.map { uri ->
            row(uri, statuses.random(random), cloudId = cloudIds.random(random), user = otherUser)
        }
        val rows = foreign + owned.map { uri ->
            row(
                uri = uri,
                status = statuses.random(random),
                cloudId = cloudIds.random(random),
                localHash = listOf("", "", "a", "b", "c").random(random),
                backedUpAtMs = listOf(null, 12_345L).random(random),
                lastSuccess = successes.random(random),
                queued = random.nextBoolean(),
                source = sources.random(random),
            )
        }
        val sha1 = device.filter { random.nextInt(4) != 0 }.associate { it.uri to listOf("a", "b", "c", "z").random(random) }
        if (files != null) {
            // A large library always has files in scope and a whole-device scan, so every step pages.
            val inScope = listOf(variants[0], variants[6], variants[5], variants[2])
            return Fixture("random seed $seed, $files files", now, device, listing, rows, sha1, inScope[seed % inScope.size])
        }
        return Fixture(
            "random seed $seed", now, device, listing, rows, sha1, variants.random(random),
            scanComplete = random.nextInt(4) != 0,
        )
    }

    // ─── recording ───────────────────────────────────────────────────────────

    /** A timestamp as the comparison sees it: the fixture's own values as they are, a pass's stamps as
     *  "now". */
    private fun stamp(value: Long?, since: Long): String = when {
        value == null -> "null"
        value >= since -> "now"
        else -> value.toString()
    }

    private fun describe(entity: SyncStateEntity, since: Long): String = listOf(
        entity.localUri, entity.userId, entity.cloudFileId, entity.localHash, entity.cloudHash, entity.status,
        stamp(entity.lastSyncAttemptMs, since), stamp(entity.lastSyncSuccessMs, since), stamp(entity.backedUpAtMs, since),
        entity.sizeBytes, entity.queued, entity.queueSource, stamp(entity.queuedAt, since),
    ).joinToString("|")

    /** The device a fixture describes, scanned whole or not as [complete] says. Records every file
     *  whose hash the pass reads. */
    private class FixtureDevice(
        private val items: List<LocalMediaItem>,
        private val hashes: Map<String, String>,
        private val complete: Boolean,
    ) : LocalMediaRepository {
        val sha1Reads = mutableListOf<String>()

        override fun observeLocalMedia(): Flow<List<LocalMediaItem>> = flowOf(items)
        override suspend fun freshScan(): DeviceMediaScan = DeviceMediaScan(items, complete)
        override fun observeTrashedMedia(): Flow<List<LocalMediaItem>> = flowOf(emptyList())
        override fun hasMediaPermission(): Flow<Boolean> = flowOf(true)
        override suspend fun confirmedGone(uris: Collection<String>): Set<String> =
            uris.filterTo(HashSet()) { uri -> items.none { it.uri == uri } }
        override suspend fun queryByUri(uri: String): LocalMediaItem? = items.firstOrNull { it.uri == uri }
        override suspend fun queryByBucket(bucketName: String): List<LocalMediaItem> =
            items.filter { it.bucketName == bucketName }
        override suspend fun sha1(uri: String): String? {
            sha1Reads += uri
            return hashes[uri]
        }
        override fun notifyMediaChanged() {}
    }

    /** The real repository over the fixture's database, recording every call a pass makes on it with
     *  its arguments, the rows each guarded write changed included. */
    private class RecordingSyncStateRepository(
        private val real: SyncStateRepository,
        private val stamp: (Long?) -> String,
    ) : SyncStateRepository by real {
        /** One read of the table: which method, and how many rows it returned. */
        data class Read(val method: String, val rows: Int)

        private val log = mutableListOf<String>()

        /** Every call, a run of reads with no write between them logged once, as the whole-table read. */
        val calls: List<String> get() = log
        val reads = mutableListOf<Read>()

        private fun logRead(userId: UserId, method: String, rows: Int) {
            reads += Read(method, rows)
            val entry = "observeAll ${userId.id}"
            if (log.lastOrNull() != entry) log += entry
        }

        private fun describe(state: SyncState): String = listOf(
            state.localUri, state.cloudFileId, state.localHash, state.cloudHash, state.status,
            stamp(state.lastSyncAttemptMs), stamp(state.lastSyncSuccessMs), stamp(state.backedUpAtMs),
            state.sizeBytes, state.queued, state.queueSource,
        ).joinToString("|")

        override fun observeAll(userId: UserId): Flow<List<SyncState>> =
            real.observeAll(userId).map { rows -> rows.also { logRead(userId, "observeAll", it.size) } }

        override suspend fun pageByStatus(userId: UserId, status: SyncStatus, afterKey: Long, limit: Int): SyncStatePage =
            real.pageByStatus(userId, status, afterKey, limit).also { logRead(userId, "pageByStatus $status", it.rows.size) }

        override suspend fun getOwnByUris(userId: UserId, localUris: List<String>): List<SyncState> {
            assertTrue("a lookup of ${localUris.size} uris is past the host-variable limit", localUris.size <= SQL_CHUNK_SIZE)
            return real.getOwnByUris(userId, localUris).also { logRead(userId, "getOwnByUris", it.size) }
        }

        override suspend fun syncedCloudIdsAmong(userId: UserId, cloudFileIds: Collection<String>): Set<String> {
            assertTrue("a lookup of ${cloudFileIds.size} ids is past the host-variable limit", cloudFileIds.size <= SQL_CHUNK_SIZE)
            return real.syncedCloudIdsAmong(userId, cloudFileIds).also { logRead(userId, "syncedCloudIdsAmong", it.size) }
        }

        override suspend fun upsert(state: SyncState, userId: UserId) {
            log += "upsert ${userId.id} ${describe(state)}"
            real.upsert(state, userId)
        }

        override suspend fun upsertAll(states: List<SyncState>, userId: UserId) {
            log += "upsertAll ${userId.id} ${states.map { describe(it) }}"
            real.upsertAll(states, userId)
        }

        override suspend fun updateDomainColumnsIfNotSyncedWithCloud(state: SyncState, userId: UserId): Int =
            real.updateDomainColumnsIfNotSyncedWithCloud(state, userId)
                .also { log += "guardedUpdate ${userId.id} ${describe(state)} changed=$it" }

        override suspend fun demoteToLocalIfCloudIdMatches(localUri: String, expectedCloudId: String): Int =
            real.demoteToLocalIfCloudIdMatches(localUri, expectedCloudId)
                .also { log += "demote $localUri $expectedCloudId changed=$it" }

        override suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus) {
            log += "updateStatus $localUri $newStatus"
            real.updateStatusAndDeleteLocal(localUri, newStatus)
        }

        override suspend fun getByUri(localUri: String): SyncState? {
            log += "getByUri $localUri"
            return real.getByUri(localUri)
        }

        override suspend fun claimForUpload(localUri: String): Int =
            real.claimForUpload(localUri).also { log += "claimForUpload $localUri changed=$it" }

        override suspend fun resetStaleUploadingClaims() {
            log += "resetStaleUploadingClaims"
            real.resetStaleUploadingClaims()
        }

        override suspend fun clearHiddenForCloudId(cloudFileId: String) {
            log += "clearHidden $cloudFileId"
            real.clearHiddenForCloudId(cloudFileId)
        }

        override suspend fun deleteLocalOnlyByUris(localUris: List<String>) {
            log += "deleteLocalOnly $localUris"
            real.deleteLocalOnlyByUris(localUris)
        }

        override suspend fun delete(localUri: String) {
            log += "delete $localUri"
            real.delete(localUri)
        }

        override suspend fun markQueued(localUri: String, source: String, at: Long) {
            log += "markQueued $localUri $source ${stamp(at)}"
            real.markQueued(localUri, source, at)
        }

        override suspend fun clearQueued(localUri: String) {
            log += "clearQueued $localUri"
            real.clearQueued(localUri)
        }

        override suspend fun markRemovedFromDrive(localUri: String) {
            log += "markRemovedFromDrive $localUri"
            real.markRemovedFromDrive(localUri)
        }

        override suspend fun clearQueuedForSynced(localUri: String) {
            log += "clearQueuedForSynced $localUri"
            real.clearQueuedForSynced(localUri)
        }

        override suspend fun clearManualQueue(): Int =
            real.clearManualQueue().also { log += "clearManualQueue changed=$it" }

        // The batched writes are logged one entry per row, as the per-row calls the reference pass makes,
        // so the logs still line up row by row; the tables compared after the pass check what they wrote.
        override suspend fun updateDomainColumnsIfNotSyncedWithCloudAll(states: List<SyncState>, userId: UserId): Set<String> =
            real.updateDomainColumnsIfNotSyncedWithCloudAll(states, userId).also { untouched ->
                for (state in states) {
                    log += "guardedUpdate ${userId.id} ${describe(state)} changed=${if (state.localUri in untouched) 0 else 1}"
                }
            }

        override suspend fun markQueuedAll(localUris: List<String>, source: String, at: Long) {
            for (uri in localUris) log += "markQueued $uri $source ${stamp(at)}"
            real.markQueuedAll(localUris, source, at)
        }

        override suspend fun clearQueuedForSyncedAll(localUris: List<String>) {
            for (uri in localUris) log += "clearQueuedForSynced $uri"
            real.clearQueuedForSyncedAll(localUris)
        }

    }

    private companion object {
        /** The page sizes each fixture runs the pass at: one row, two, an odd few, and the real size. */
        val PAGE_SIZES = listOf(1, 2, 7, SQL_CHUNK_SIZE)

        /** A capture second in the range a camera writes, so every name+date key is a real one. */
        const val BASE_SEC = 1_600_000_000L
    }
}
