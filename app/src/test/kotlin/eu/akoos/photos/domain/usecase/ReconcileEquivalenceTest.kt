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
import eu.akoos.photos.domain.repository.SyncStateRepository
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
                    "demote ", "clearQueuedForSynced ", "|CLOUD_ONLY|", "guardedUpdate ", "markQueued ",
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

    /** Runs both passes on [fixture] and fails on the first difference. Returns the reference run. */
    private suspend fun assertSamePass(fixture: Fixture): Outcome {
        val reference = runPass(fixture, production = false)
        val production = runPass(fixture, production = true)
        assertEquals("${fixture.name}: repository calls", reference.calls, production.calls)
        assertEquals("${fixture.name}: sync_state after the pass", reference.table, production.table)
        assertEquals("${fixture.name}: files hashed", reference.sha1Reads, production.sha1Reads)
        assertEquals("${fixture.name}: total", reference.total, production.total)
        assertEquals("${fixture.name}: pairing settled", reference.settled, production.settled)
        return reference
    }

    private suspend fun runPass(fixture: Fixture, production: Boolean): Outcome {
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

        val progress = if (production) {
            ReconcileSyncStateUseCase(device, cloud, recording, db.photoListingDao(), context, passScope)(userId).toList()
        } else {
            ReconcileReferencePass(device, cloud, recording, context)(userId).toList()
        }

        val table = (db.syncStateDao().observeAll(userId.id).first() + db.syncStateDao().observeAll(otherUser).first())
            .map { describe(it, since) }
            .sorted()
        val settled = context.settingsDataStore.data.first()[SettingsKeys.pairingSettledKey(userId.id)]
        return Outcome(recording.calls.toList(), table, device.sha1Reads.toList(), progress.last().total, settled)
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
    private fun randomFixture(seed: Int): Fixture {
        val random = Random(seed)
        val now = System.currentTimeMillis()
        val names = List(6) { "IMG_$it.jpg" }
        val seconds = listOf(0L, BASE_SEC, BASE_SEC + 1, BASE_SEC + 2)
        val sizes = listOf(0L, 10L, 20L)
        val hashes = listOf(null, "", "hmac-a", "hmac-b", "hmac-c")
        val buckets = listOf("Camera", "Camera", "WhatsApp", "Downloads", null)
        val sources = listOf(null, QueueSource.AUTO_FOLDER, QueueSource.MANUAL, QueueSource.ALBUM_ADD, QueueSource.EDITOR)
        val successes = listOf(null, now - 60 * 60_000L, now - 60_000L)

        val device = List(random.nextInt(40, 90)) { i ->
            deviceFile("content://r/$i", names.random(random), buckets.random(random), seconds.random(random), sizes.random(random))
        }
        val listing = List(random.nextInt(30, 80)) { j ->
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
        val uris = device.map { it.uri } + List(15) { "content://gone/$it" }
        val rows = uris.filter { random.nextInt(3) != 0 }.map { uri ->
            row(
                uri = uri,
                status = SyncStatus.entries.random(random),
                cloudId = cloudIds.random(random),
                localHash = listOf("", "", "a", "b", "c").random(random),
                backedUpAtMs = listOf(null, 12_345L).random(random),
                lastSuccess = successes.random(random),
                queued = random.nextBoolean(),
                source = sources.random(random),
            )
        }
        val sha1 = device.filter { random.nextInt(4) != 0 }.associate { it.uri to listOf("a", "b", "c", "z").random(random) }
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
        val calls = mutableListOf<String>()

        private fun describe(state: SyncState): String = listOf(
            state.localUri, state.cloudFileId, state.localHash, state.cloudHash, state.status,
            stamp(state.lastSyncAttemptMs), stamp(state.lastSyncSuccessMs), stamp(state.backedUpAtMs),
            state.sizeBytes, state.queued, state.queueSource,
        ).joinToString("|")

        override fun observeAll(userId: UserId): Flow<List<SyncState>> {
            calls += "observeAll ${userId.id}"
            return real.observeAll(userId)
        }

        override suspend fun upsert(state: SyncState, userId: UserId) {
            calls += "upsert ${userId.id} ${describe(state)}"
            real.upsert(state, userId)
        }

        override suspend fun upsertAll(states: List<SyncState>, userId: UserId) {
            calls += "upsertAll ${userId.id} ${states.map { describe(it) }}"
            real.upsertAll(states, userId)
        }

        override suspend fun updateDomainColumnsIfNotSyncedWithCloud(state: SyncState, userId: UserId): Int =
            real.updateDomainColumnsIfNotSyncedWithCloud(state, userId)
                .also { calls += "guardedUpdate ${userId.id} ${describe(state)} changed=$it" }

        override suspend fun demoteToLocalIfCloudIdMatches(localUri: String, expectedCloudId: String): Int =
            real.demoteToLocalIfCloudIdMatches(localUri, expectedCloudId)
                .also { calls += "demote $localUri $expectedCloudId changed=$it" }

        override suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus) {
            calls += "updateStatus $localUri $newStatus"
            real.updateStatusAndDeleteLocal(localUri, newStatus)
        }

        override suspend fun getByUri(localUri: String): SyncState? {
            calls += "getByUri $localUri"
            return real.getByUri(localUri)
        }

        override suspend fun claimForUpload(localUri: String): Int =
            real.claimForUpload(localUri).also { calls += "claimForUpload $localUri changed=$it" }

        override suspend fun resetStaleUploadingClaims() {
            calls += "resetStaleUploadingClaims"
            real.resetStaleUploadingClaims()
        }

        override suspend fun clearHiddenForCloudId(cloudFileId: String) {
            calls += "clearHidden $cloudFileId"
            real.clearHiddenForCloudId(cloudFileId)
        }

        override suspend fun deleteLocalOnlyByUris(localUris: List<String>) {
            calls += "deleteLocalOnly $localUris"
            real.deleteLocalOnlyByUris(localUris)
        }

        override suspend fun delete(localUri: String) {
            calls += "delete $localUri"
            real.delete(localUri)
        }

        override suspend fun markQueued(localUri: String, source: String, at: Long) {
            calls += "markQueued $localUri $source ${stamp(at)}"
            real.markQueued(localUri, source, at)
        }

        override suspend fun clearQueued(localUri: String) {
            calls += "clearQueued $localUri"
            real.clearQueued(localUri)
        }

        override suspend fun clearQueuedForSynced(localUri: String) {
            calls += "clearQueuedForSynced $localUri"
            real.clearQueuedForSynced(localUri)
        }

        override suspend fun clearManualQueue(): Int =
            real.clearManualQueue().also { calls += "clearManualQueue changed=$it" }

        // The batched writes are logged one entry per row, as the per-row calls the reference pass makes,
        // so the logs still line up row by row; the tables compared after the pass check what they wrote.
        override suspend fun updateDomainColumnsIfNotSyncedWithCloudAll(states: List<SyncState>, userId: UserId): Set<String> =
            real.updateDomainColumnsIfNotSyncedWithCloudAll(states, userId).also { untouched ->
                for (state in states) {
                    calls += "guardedUpdate ${userId.id} ${describe(state)} changed=${if (state.localUri in untouched) 0 else 1}"
                }
            }

        override suspend fun markQueuedAll(localUris: List<String>, source: String, at: Long) {
            for (uri in localUris) calls += "markQueued $uri $source ${stamp(at)}"
            real.markQueuedAll(localUris, source, at)
        }

        override suspend fun clearQueuedForSyncedAll(localUris: List<String>) {
            for (uri in localUris) calls += "clearQueuedForSynced $uri"
            real.clearQueuedForSyncedAll(localUris)
        }

        // A read of the rows the queue clean-up filters to, where the reference reads the whole table and
        // filters it the same way; logged as that read so the two logs line up.
        override suspend fun getLocalOnly(userId: UserId): List<SyncState> {
            calls += "observeAll ${userId.id}"
            return real.getLocalOnly(userId)
        }
    }

    private companion object {
        /** A capture second in the range a camera writes, so every name+date key is a real one. */
        const val BASE_SEC = 1_600_000_000L
    }
}
