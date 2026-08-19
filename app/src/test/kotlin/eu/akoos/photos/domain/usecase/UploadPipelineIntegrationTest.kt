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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.data.repository.drive.CloudTrashOutcome
import eu.akoos.photos.data.repository.drive.UploadPhase
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.NetworkObserver
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
import java.io.File

/**
 * End-to-end state-machine test for the upload pipeline. It runs the REAL upload use-cases
 * ([UploadPendingUseCase], [ReconcileSyncStateUseCase], [ForceUploadLocalUrisUseCase]) against a
 * REAL in-memory [AppDatabase] (so [SyncStateDao] + [UploadAlbumTargetDao] behave exactly as in
 * production) plus in-memory FAKE cloud + local repos. No device, no network, no Proton account.
 *
 * The scenarios pin the sync_state transitions that were recently buggy: a cancelled or
 * cloud-deleted upload must NOT loop back and re-upload, an album-add must not lose its membership
 * on a transient failure, and an out-of-scope AUTO_FOLDER row is de-queued while an explicit MANUAL
 * row survives. Each test asserts BOTH the persisted DB rows and the fake-cloud contents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadPipelineIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var syncDao: SyncStateDao
    private lateinit var targetDao: UploadAlbumTargetDao
    private lateinit var syncRepo: SyncStateRepositoryImpl
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var local: FakeLocalMediaRepository
    private lateinit var networkObserver: NetworkObserver
    private lateinit var context: Context

    private lateinit var uploadUseCase: UploadPendingUseCase
    private lateinit var reconcileUseCase: ReconcileSyncStateUseCase
    private lateinit var forceUploadUseCase: ForceUploadLocalUrisUseCase

    private val userId = UserId("test-user")
    private val volumeId = "vol1"

    // A real, unconfined app-scope for the use-cases' @AppScope launches (requestStop's de-queue,
    // the NonCancellable SYNCED write). Unconfined so those launches run inline under runTest.
    private val appScope = CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // WorkManager is initialised so ForceUploadLocalUrisUseCase.run()'s trailing
        // SyncWorker.runNow(context) can enqueue without throwing. A synchronous executor keeps it
        // deterministic; the enqueued worker carries a CONNECTED network constraint that is never
        // satisfied in this JVM, so its body never runs and cannot perturb the DB we assert on.
        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder()
                    .setExecutor { it.run() }
                    .setTaskExecutor { it.run() }
                    .build(),
            )
        }

        // Start each test from an empty settings DataStore so gate keys never leak in from a
        // prior test (the delegate caches one instance per file name across the JVM).
        runCatching {
            kotlinx.coroutines.runBlocking { context.settingsDataStore.edit { it.clear() } }
        }

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        syncDao = db.syncStateDao()
        targetDao = db.uploadAlbumTargetDao()
        syncRepo = SyncStateRepositoryImpl(syncDao)

        cloud = FakeDrivePhotoRepository()
        local = FakeLocalMediaRepository()

        networkObserver = mockk(relaxed = true)
        coEvery { networkObserver.currentlyOnWifi() } returns true

        uploadUseCase = UploadPendingUseCase(
            syncStateRepo = syncRepo,
            localRepo = local,
            cloudRepo = cloud,
            pendingDeleteNotif = mockk(relaxed = true),
            networkObserver = networkObserver,
            transferCenter = mockk<TransferCenter>(relaxed = true),
            uploadAlbumTargetDao = targetDao,
            pendingMetadataEditDao = db.pendingMetadataEditDao(),
            photoLocationDao = db.photoLocationDao(),
            context = context,
            appScope = appScope,
        )
        reconcileUseCase = ReconcileSyncStateUseCase(local, cloud, syncRepo, context)
        forceUploadUseCase = ForceUploadLocalUrisUseCase(context, syncRepo, cloud, targetDao)
    }

    @After
    fun tearDown() {
        // Cancel the app-scope BEFORE closing the DB so a still-running @AppScope launch (e.g.
        // requestStop's de-queue) cannot resume against a closed database and throw an uncaught
        // exception that the runner then attributes to the next test.
        appScope.cancel()
        db.close()
        // Wipe the per-app DataStore so seeded gate keys never leak between tests (the
        // `by preferencesDataStore` instance is a process-wide singleton keyed by name).
        runCatching {
            kotlinx.coroutines.runBlocking { context.settingsDataStore.edit { it.clear() } }
        }
    }

    // ── DataStore gate seeding ──────────────────────────────────────────────────────
    //
    // The bulk (AUTO_FOLDER) upload path is guarded by several DataStore keys. Seed them so a
    // folder-selected backup is allowed to drain. MANUAL / ALBUM_ADD rows bypass these gates via the
    // explicit-action path, so those scenarios need far less seeding.
    private suspend fun seedBulkUploadGates(folder: String = "Camera") {
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.SYNC_WIFI_ONLY] = false
            p[SettingsKeys.BACKUP_EVERYTHING] = false
            p[SettingsKeys.SYNC_FOLDER_NAMES] = setOf(folder)
            // Listing walked end-to-end at least once + a post-completion reconcile has paired,
            // so the anti-duplicate defer opens for the bulk drain.
            p[SettingsKeys.photoListingEverCompleteKey(userId.id, volumeId)] = true
            p[SettingsKeys.pairingSettledKey(userId.id)] = true
        }
    }

    private fun localItem(
        uri: String,
        bucket: String? = "Camera",
        dateTakenMs: Long = 1_700_000_000_000L,
    ): LocalMediaItem {
        // Give the real content resolver readable bytes for this URI so the upload path's computeSha1
        // resolves a real, non-null content hash and the file uploads. Without registered content,
        // openInputStream returns null, which now (correctly) marks the source unreadable and skips
        // the upload as a hard failure, so the pipeline scenarios need a stream behind each URI.
        registerContent(uri)
        return LocalMediaItem(
            uri = uri,
            dateTaken = dateTakenMs,
            displayName = uri.substringAfterLast('/') + ".jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            bucketName = bucket,
            width = 100,
            height = 100,
        )
    }

    /** Register a small readable byte stream for [uri] on the (real Robolectric) content resolver so
     *  the upload path can hash its content. Each URI is hashed once per scenario. */
    private fun registerContent(uri: String) {
        val resolver = org.robolectric.Shadows.shadowOf(context.contentResolver)
        resolver.registerInputStream(
            android.net.Uri.parse(uri),
            java.io.ByteArrayInputStream(uri.toByteArray()),
        )
    }

    private suspend fun row(uri: String): SyncState? = syncRepo.getByUri(uri)

    // ── Scenario 1: manual upload ───────────────────────────────────────────────────

    @Test
    fun `manual upload backs the file up and marks the row SYNCED with its queue cleared`() = runTest {
        val uri = "content://media/1"
        local.add(localItem(uri))
        // MANUAL bypasses the folder/listing gates, so no bulk seeding is required here.
        forceUploadUseCase.forceUpload(userId, listOf(uri))

        // Queued MANUAL, still LOCAL_ONLY, no cloud copy yet.
        row(uri)!!.let {
            assertEquals(SyncStatus.LOCAL_ONLY, it.status)
            assertTrue("row should be queued after forceUpload", it.queued)
            assertEquals(QueueSource.MANUAL, it.queueSource)
        }

        uploadUseCase(userId)

        // The fake cloud now holds it, and the row is SYNCED with the returned cloudFileId.
        assertEquals(1, cloud.photos.value.size)
        val cloudId = cloud.photos.value.single().linkId
        row(uri)!!.let {
            assertEquals(SyncStatus.SYNCED, it.status)
            assertEquals(cloudId, it.cloudFileId)
            // RULE 1: a SYNCED row's queue intent is fully cleared.
            assertEquals(false, it.queued)
            assertNull(it.queueSource)
        }
    }

    // ── Scenario 2: the re-upload loop (key regression) ─────────────────────────────

    @Test
    fun `a cloud-deleted photo demotes to LOCAL_ONLY and is NOT re-uploaded`() = runTest {
        // Open the bulk-upload gates but select a DIFFERENT folder than the file lives in. That way
        // the ONLY thing that can stop the final pass from re-uploading is the row's un-queued state,
        // not a wifi / listing / no-folders early-return — so this really pins the re-upload loop.
        seedBulkUploadGates(folder = "OtherFolder")
        val uri = "content://media/2"
        local.add(localItem(uri, bucket = "Camera"))
        forceUploadUseCase.forceUpload(userId, listOf(uri))
        uploadUseCase(userId)

        val cloudId = cloud.photos.value.single().linkId
        assertEquals(SyncStatus.SYNCED, row(uri)!!.status)

        // Simulate the cloud copy being deleted elsewhere: drop it from the listing and age the
        // row's success stamp past the 15-min absence grace so reconcile treats the absence as real.
        cloud.removeByLinkId(cloudId)
        val aged = row(uri)!!.copy(
            lastSyncSuccessMs = System.currentTimeMillis() - (16 * 60 * 1000L),
        )
        syncRepo.upsert(aged, userId)

        // Reconcile must demote the orphaned SYNCED row to LOCAL_ONLY and clear its spent intent.
        // The file's folder is not selected, so reconcile does not re-stamp it AUTO_FOLDER.
        reconcileUseCase(userId).collectToEnd()
        row(uri)!!.let {
            assertEquals(SyncStatus.LOCAL_ONLY, it.status)
            assertNull(it.cloudFileId)
            assertEquals("a demoted deletion must not stay queued", false, it.queued)
            assertNull(it.queueSource)
        }

        // The next upload pass must NOT resurrect it: the row is LOCAL_ONLY but un-queued, and the
        // gates are open, so the un-queued state is the sole reason it stays off the cloud.
        uploadUseCase(userId)
        assertTrue("a deleted-from-cloud photo must not re-upload", cloud.photos.value.isEmpty())
        row(uri)!!.let {
            assertEquals(SyncStatus.LOCAL_ONLY, it.status)
            assertEquals(false, it.queued)
        }
    }

    // ── Scenario 3: cancel ──────────────────────────────────────────────────────────

    @Test
    fun `requestStop de-queues not-yet-started manual uploads so they do not run`() = runTest {
        // Gates open so the following pass is not blocked by wifi / listing / no-folders — the only
        // reason the cancelled items stay off the cloud is that requestStop de-queued them.
        seedBulkUploadGates(folder = "Camera")
        val uris = listOf("content://media/c1", "content://media/c2", "content://media/c3")
        uris.forEach { local.add(localItem(it, bucket = "Camera")) }
        forceUploadUseCase.forceUpload(userId, uris)
        uris.forEach { assertEquals(QueueSource.MANUAL, row(it)!!.queueSource) }

        // User taps cancel before the batch runs. requestStop clears the MANUAL queue intent on the
        // app-scope; the clear is a suspend Room query that does NOT complete inline even under
        // Unconfined, so wait for the launched work to land before asserting the de-queued state.
        uploadUseCase.requestStop()
        appScope.coroutineContext.job.children.toList().joinAll()

        uris.forEach {
            row(it)!!.let { r ->
                assertEquals(SyncStatus.LOCAL_ONLY, r.status)
                assertEquals("cancelled manual upload must be de-queued", false, r.queued)
                assertNull(r.queueSource)
            }
        }

        // A following pass finds nothing queued, so nothing uploads.
        uploadUseCase(userId)
        assertTrue("cancelled manual uploads must not reach the cloud", cloud.photos.value.isEmpty())
    }

    // ── Scenario 4: album-add ───────────────────────────────────────────────────────

    @Test
    fun `an album-add uploads the photo, joins the album, and clears the target row`() = runTest {
        val uri = "content://media/a1"
        val albumLinkId = "album-xyz"
        local.add(localItem(uri))

        forceUploadUseCase.queueForAlbum(userId, albumLinkId, listOf(uri))
        // The target row and the ALBUM_ADD intent are recorded.
        assertEquals(listOf(albumLinkId), targetDao.getTargetsFor(uri))
        assertEquals(QueueSource.ALBUM_ADD, row(uri)!!.queueSource)

        uploadUseCase(userId)

        val cloudId = cloud.photos.value.single().linkId
        // The album now contains the freshly uploaded photo.
        assertEquals(listOf(cloudId), cloud.albumMembers(albumLinkId))
        assertEquals(SyncStatus.SYNCED, row(uri)!!.status)
        // The target row is removed once the add succeeds.
        assertTrue("target must be cleared after a successful add", targetDao.getTargetsFor(uri).isEmpty())
    }

    // ── Scenario 5: album-add, add fails then retries ───────────────────────────────

    @Test
    fun `a failed album-add keeps the target row so membership is retried and eventually lands`() = runTest {
        val uri = "content://media/a2"
        val albumLinkId = "album-flaky"
        local.add(localItem(uri))

        // The first addPhotosToAlbum call throws; the upload itself still succeeds.
        cloud.failAddPhotosOnce = true
        forceUploadUseCase.queueForAlbum(userId, albumLinkId, listOf(uri))
        uploadUseCase(userId)

        // Photo uploaded + SYNCED, but the album membership did NOT land, so the target survives.
        assertEquals(1, cloud.photos.value.size)
        assertEquals(SyncStatus.SYNCED, row(uri)!!.status)
        assertTrue("membership must not be lost on a transient add failure", cloud.albumMembers(albumLinkId).isEmpty())
        assertEquals(
            "a failed add must leave the target queued for the next drain",
            listOf(albumLinkId),
            targetDao.getTargetsFor(uri),
        )

        // The fake now succeeds; the next pass drains the surviving target and joins the album.
        uploadUseCase(userId)
        val cloudId = cloud.photos.value.single().linkId
        assertEquals(listOf(cloudId), cloud.albumMembers(albumLinkId))
        assertTrue("target must be cleared after the retry lands", targetDao.getTargetsFor(uri).isEmpty())
    }

    // ── Scenario 6: out-of-scope de-queue vs explicit ───────────────────────────────

    @Test
    fun `reconcile de-queues an out-of-scope AUTO_FOLDER row but leaves a MANUAL row queued`() = runTest {
        // Only "Camera" is selected for backup. The AUTO_FOLDER row lives in an unselected folder,
        // the MANUAL row is an explicit user action out of scope.
        seedBulkUploadGates(folder = "Camera")
        val autoUri = "content://media/auto"
        val manualUri = "content://media/manual"
        // Neither file is in a selected folder, so reconcile will not re-add them to the in-scope set.
        local.add(localItem(autoUri, bucket = "Downloads"))
        local.add(localItem(manualUri, bucket = "Downloads"))

        // Seed both as queued LOCAL_ONLY rows with their respective sources.
        syncRepo.upsert(localOnlyRow(autoUri), userId)
        syncRepo.markQueued(autoUri, QueueSource.AUTO_FOLDER, System.currentTimeMillis())
        syncRepo.upsert(localOnlyRow(manualUri), userId)
        syncRepo.markQueued(manualUri, QueueSource.MANUAL, System.currentTimeMillis())

        reconcileUseCase(userId).collectToEnd()

        // The AUTO_FOLDER row's folder is not selected, so its queued intent is cleared.
        row(autoUri)!!.let {
            assertEquals(SyncStatus.LOCAL_ONLY, it.status)
            assertEquals("out-of-scope AUTO_FOLDER must be de-queued", false, it.queued)
        }
        // The explicit MANUAL intent survives scope cleanup so the user's action still runs.
        row(manualUri)!!.let {
            assertEquals(SyncStatus.LOCAL_ONLY, it.status)
            assertTrue("an explicit MANUAL upload must stay queued", it.queued)
            assertEquals(QueueSource.MANUAL, it.queueSource)
        }
    }

    private fun localOnlyRow(uri: String) = SyncState(
        localUri = uri,
        cloudFileId = null,
        localHash = "",
        cloudHash = null,
        status = SyncStatus.LOCAL_ONLY,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = null,
        sizeBytes = 1024L,
    )
}

/** Drain a SyncProgress flow to completion so the reconcile body runs fully. */
private suspend fun Flow<*>.collectToEnd() {
    last()
}

// ── Fakes ───────────────────────────────────────────────────────────────────────────

/**
 * In-memory [LocalMediaRepository]. [observeLocalMedia] + [queryByUri] are real over the [items]
 * list (the upload pipeline reads bucket names + the per-URI item through these); every other
 * member is a no-op.
 */
private class FakeLocalMediaRepository : LocalMediaRepository {
    private val items = MutableStateFlow<List<LocalMediaItem>>(emptyList())

    fun add(item: LocalMediaItem) {
        items.value = items.value + item
    }

    override fun observeLocalMedia(): Flow<List<LocalMediaItem>> = items.asStateFlow()
    override suspend fun queryByUri(uri: String): LocalMediaItem? = items.value.firstOrNull { it.uri == uri }
    override suspend fun queryByBucket(bucketName: String): List<LocalMediaItem> =
        items.value.filter { it.bucketName == bucketName }
    override suspend fun sha1(uri: String): String? = null

    override fun observeTrashedMedia(): Flow<List<LocalMediaItem>> = MutableStateFlow(emptyList())
    override fun hasMediaPermission(): Flow<Boolean> = MutableStateFlow(true)
    override fun notifyMediaChanged() {}
}
