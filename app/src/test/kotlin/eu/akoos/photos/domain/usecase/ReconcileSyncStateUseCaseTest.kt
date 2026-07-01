package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReconcileSyncStateUseCaseTest {

    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var context: Context
    private lateinit var useCase: ReconcileSyncStateUseCase
    // Hoisted so individual tests can override a single pref (e.g. STRIP_ON_UPLOAD) after setUp.
    private lateinit var mockPrefsRef: Preferences
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        localRepo = mockk()
        cloudRepo = mockk(relaxed = true)
        syncStateRepo = mockk(relaxed = true)

        // Mock DataStore extension on Context
        val mockPrefs = mockk<Preferences>(relaxed = true)
        mockPrefsRef = mockPrefs
        val mockDataStore = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        context = mockk()
        every { context.settingsDataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")  // back up Camera folder
        every { mockPrefs[SettingsKeys.BACKUP_EVERYTHING] } returns false
        every { mockPrefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] } returns emptySet()
        every { mockPrefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.PENDING_ALBUM_ADDS] } returns emptySet()
        // No ever-complete flag → the content-hash recompute path is skipped; the name/size and
        // cloud-linkId matchers still run, which is what these tests exercise.
        every { mockPrefs.asMap() } returns emptyMap()

        useCase = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, context)
    }

    private fun localItem(uri: String, name: String = "photo.jpg", size: Long = 1024L) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    private fun cloudPhoto(
        linkId: String,
        name: String = "photo.jpg",
        size: Long = 1024L,
        captureTime: Long = 1L,
        contentHash: String? = null,
    ) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTime,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        thumbnailUrl = null,
        revisionId = "rev1",
        contentHash = contentHash,
    )

    private fun syncState(
        uri: String,
        cloudId: String?,
        status: SyncStatus = SyncStatus.SYNCED,
        localHash: String = "",
    ) =
        SyncState(
            localUri = uri,
            cloudFileId = cloudId,
            localHash = localHash,
            cloudHash = null,
            status = status,
            lastSyncAttemptMs = 0L,
            lastSyncSuccessMs = null,
            backedUpAtMs = null,
            sizeBytes = 1024L,
        )

    @Test
    fun `local items without matching cloud get LOCAL_ONLY status`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(localItem("uri://1")))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
                userId,
            )
        }
    }

    @Test
    fun `local item matching cloud by name+size gets SYNCED status`() = runTest {
        val local = localItem("uri://1", name = "vacation.jpg", size = 2048L)
        val cloud = cloudPhoto("link1", name = "vacation.jpg", size = 2048L)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri("uri://1") } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link1" }
                },
                userId,
            )
        }
    }

    @Test
    fun `previously synced item no longer in cloud is marked CLOUD_ONLY`() = runTest {
        val local = localItem("uri://1")
        val existingSync = syncState("uri://1", cloudId = "old-link-id", status = SyncStatus.SYNCED)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        // Cloud no longer has "old-link-id"
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existingSync))
        coEvery { syncStateRepo.getByUri("uri://1") } returns existingSync

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
    }

    @Test
    fun `SYNCED state for file outside backup selection is NOT demoted to CLOUD_ONLY`() = runTest {
        // Regression: photos downloaded via main gallery land in Pictures/Proton Photos/, which
        // typically isn't in the backup-folder selection (users don't want their downloads
        // loop-uploaded). The file IS still on the device though, so reconcile must NOT demote
        // the SYNCED state — otherwise album views (which only check SyncState, no contentHash
        // fallback) lose the green "downloaded" indicator.
        val downloadedFile = LocalMediaItem(
            uri = "content://media/external/images/media/42",
            dateTaken = 1000L,
            displayName = "downloaded.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Proton Photos", // NOT in selected folders (only "Camera" is)
        )
        val cloud = cloudPhoto("link1", name = "downloaded.jpg", size = 4096L)
        val existingSync = syncState(downloadedFile.uri, cloudId = "link1", status = SyncStatus.SYNCED)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(downloadedFile))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existingSync))
        coEvery { syncStateRepo.getByUri(any()) } returns existingSync

        useCase(userId).toList()

        // The SYNCED state must remain SYNCED — verify no demote-to-CLOUD_ONLY upsert happens.
        coVerify(exactly = 0) {
            syncStateRepo.upsert(
                match { it.localUri == downloadedFile.uri && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
    }

    @Test
    fun `SYNCED state IS demoted to CLOUD_ONLY when local file actually gone from device`() = runTest {
        // Sanity: the demote path still fires when the file truly disappeared (user deleted from
        // gallery, "Free up space" ran, etc.). Pairs with the previous test.
        val ghostSync = syncState("content://media/external/images/media/99", cloudId = "linkGhost",
            status = SyncStatus.SYNCED)
        val cloud = cloudPhoto("linkGhost", name = "ghost.jpg")
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList()) // no local files at all
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(ghostSync))
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == ghostSync.localUri && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
    }

    @Test
    fun `reconcile emits progress events and finishes with running=false`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val progress = useCase(userId).toList()

        assertTrue("Should start with running=true", progress.first().running)
        assertFalse("Should end with running=false", progress.last().running)
    }

    @Test
    fun `reconcile reads cloud state from DB without triggering a network refresh`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        useCase(userId).toList()

        // ReconcileSyncStateUseCase only reads from DB via observeCloudPhotos —
        // network refresh (incremental or full) is the caller's responsibility.
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotosIncremental(userId) }
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotos(userId) }
    }

    // ─── matcher priority: byId ───────────────────────────────────────────────

    @Test
    fun `existing cloudFileId pairs the row to SYNCED by id regardless of hash or name`() = runTest {
        // The existing SyncState already knows the cloud linkId. byId is the top-priority matcher,
        // so the row stays SYNCED even though the cloud photo's name/size differ from the local file
        // and no content hash is involved.
        val local = localItem("uri://1", name = "local-name.jpg", size = 111L)
        val cloud = cloudPhoto("link-known", name = "totally-different.jpg", size = 999L)
        val existing = syncState("uri://1", cloudId = "link-known", status = SyncStatus.SYNCED)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-known" }
                },
                userId,
            )
        }
    }

    // ─── matcher priority: byContentHash ──────────────────────────────────────

    @Test
    fun `identical bytes pair by content hash even when the cloud copy was renamed`() = runTest {
        // No cloudFileId on the row, but the stored localHash + a cloud photo carrying the matching
        // ContentHash pairs them by bytes — the authoritative matcher. The cloud name differs (a
        // renamed cloud copy), proving the pairing is hash-driven, not name-driven.
        val local = localItem("uri://1", name = "IMG_local.jpg", size = 2048L)
        val cloud = cloudPhoto("link-hash", name = "renamed-on-cloud.jpg", size = 2048L, contentHash = "CLOUDHMAC")
        val existing = syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY, localHash = "deadbeefsha1")
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing
        // The local SHA-1 maps to the cloud HMAC ContentHash via the repo helper.
        every { cloudRepo.cloudContentHash("deadbeefsha1") } returns "CLOUDHMAC"

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-hash" }
                },
                userId,
            )
        }
    }

    @Test
    fun `a stored hash that maps to no cloud ContentHash stays LOCAL_ONLY`() = runTest {
        // The row has a stored hash but the cloud photo's ContentHash doesn't match (different bytes),
        // and there's no name/date fallback because the cloud photo HAS a hash. So: LOCAL_ONLY.
        val local = localItem("uri://1", name = "shared.jpg", size = 2048L)
        val cloud = cloudPhoto("link-other", name = "shared.jpg", size = 2048L, contentHash = "OTHERHMAC", captureTime = 50L)
        val existing = syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY, localHash = "localsha1")
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing
        every { cloudRepo.cloudContentHash("localsha1") } returns "MINE-NOT-THEIRS"

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
                userId,
            )
        }
    }

    // ─── matcher priority: byNameAndDate gating ───────────────────────────────

    @Test
    fun `name and date pairs when the cloud photo has no content hash`() = runTest {
        // No cloudFileId, no stored hash, but displayName + captureTime line up and the cloud photo
        // carries NO ContentHash → the name/date fallback is trusted and the row is SYNCED.
        // local.dateTaken=2000ms → 2s; cloud.captureTime must equal 2.
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "vacation.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-nd", name = "vacation.jpg", size = 4096L, captureTime = 2L, contentHash = null)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-nd" }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date does NOT pair when the cloud photo has a content hash and nothing was stripped`() = runTest {
        // Same name + date, but the cloud photo HAS a ContentHash and strip-on-upload is OFF. The
        // bytes would have to hash-match; a name collision alone must not mark a different file as
        // backed up (Free-up-space could then delete a never-uploaded local). Expect LOCAL_ONLY.
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-hashed", name = "IMG_0001.jpg", size = 4096L, captureTime = 2L, contentHash = "HASHED")
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
                userId,
            )
        }
    }

    @Test
    fun `name and date DOES pair a hashed cloud photo when strip-on-upload is on`() = runTest {
        // strip-on-upload rewrites the bytes, so the local original can't hash-match its stripped
        // cloud copy. With STRIP_ON_UPLOAD on, the name/date match is trusted again even though the
        // cloud photo carries a ContentHash. Expect SYNCED.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "stripped.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-strip", name = "stripped.jpg", size = 1L, captureTime = 2L, contentHash = "STRIPPEDHASH")
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-strip" }
                },
                userId,
            )
        }
    }

    // ─── UPLOADING rows are skipped ───────────────────────────────────────────

    @Test
    fun `an UPLOADING row is left untouched by reconcile`() = runTest {
        // The editor owns an UPLOADING row until its cloud-fanout finishes; reconcile must not write
        // a new SyncState for that URI (which would race the editor and duplicate the Drive entry).
        val local = localItem("uri://uploading")
        val cloud = cloudPhoto("link-x", name = "photo.jpg")
        val existing = syncState("uri://uploading", cloudId = null, status = SyncStatus.UPLOADING)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing

        useCase(userId).toList()

        // The URI must NOT appear in the batch upsert (it was `continue`d over in the loop).
        coVerify(exactly = 0) {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://uploading" } },
                userId,
            )
        }
    }
}
