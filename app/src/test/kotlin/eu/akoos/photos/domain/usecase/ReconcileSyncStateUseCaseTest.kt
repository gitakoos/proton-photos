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
        // Compression off in the base fixture; a boolean key must be stubbed explicitly because the
        // relaxed mock otherwise hands back a raw Object that fails the Boolean cast.
        every { mockPrefs[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
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
        queued: Boolean = false,
        queueSource: String? = null,
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
            queued = queued,
            queueSource = queueSource,
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

        // The demote runs through the id-CAS guard now, keyed on the cloud id the snapshot saw.
        coVerify { syncStateRepo.demoteToLocalIfCloudIdMatches("uri://1", "old-link-id") }
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
        // and there's no name/date fallback because the cloud photo HAS a hash. So: LOCAL_ONLY. The row
        // already existed in the snapshot, so its LOCAL_ONLY write goes through the clobber-guard.
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
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY },
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
    fun `name and date re-pairs an unclaimed hashed cloud twin but keeps it out of free-up-space`() = runTest {
        // A downloaded file rewrites its own bytes (date / GPS / mvhd), so it can no longer hash-match
        // its cloud copy, yet it shares the twin's name and EXACT capture second. When that twin is
        // unclaimed (e.g. a reinstall wiped the SyncState), reconcile re-pairs it to SYNCED so it does
        // not re-upload a duplicate. The match is NOT content-certain, so backedUpAtMs stays null and
        // Free-up-space never deletes the local on this looser proof.
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
                match { states ->
                    states.any {
                        it.localUri == "uri://1" && it.status == SyncStatus.SYNCED &&
                            it.cloudFileId == "link-hashed" && it.backedUpAtMs == null
                    }
                },
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

    @Test
    fun `name and date DOES pair a hashed cloud photo when compress-on-upload is on`() = runTest {
        // Twin of the strip case for the other byte-rewriting toggle. compress-on-upload re-encodes
        // the bytes, so the local original can't hash-match its compressed cloud copy. With
        // COMPRESS_ON_UPLOAD on and STRIP_ON_UPLOAD off, the name/date match is trusted again even
        // though the cloud photo carries a ContentHash. Expect SYNCED. Regressing this half of the
        // gate would make a compressed upload fail to re-pair its cloud copy after a reinstall and
        // trigger a duplicate re-upload.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "compressed.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-compress", name = "compressed.jpg", size = 2L, captureTime = 2L, contentHash = "COMPRESSEDHASH")
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-compress" }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date does NOT re-pair a hashed cloud twin already claimed by another local`() = runTest {
        // The re-pair only ever rescues an UNCLAIMED twin. Here the same hashed cloud photo is already
        // paired to a different local (uri://other), so a second same-named + same-second local must
        // NOT steal it: without this guard a recurring camera name (IMG_0001.jpg across devices) could
        // be marked backed up when it isn't. Expect the second local stays LOCAL_ONLY and backs itself up.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-hashed", name = "IMG_0001.jpg", size = 4096L, captureTime = 2L, contentHash = "HASHED")
        val claimedByOther = syncState("uri://other", cloudId = "link-hashed", status = SyncStatus.SYNCED)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(claimedByOther))
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
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

    // ─── queue switch (piece 3) ───────────────────────────────────────────────

    @Test
    fun `an out-of-scope queued LOCAL_ONLY row is de-queued not deleted`() = runTest {
        // The user unchecked the folder, so this LOCAL_ONLY row is no longer in scope (its bucket is
        // not "Camera"). Under the queue switch reconcile clears its queued flag (LOCAL_ONLY-guarded)
        // instead of deleting the row, so the file survives as local-not-backed-up but leaves the
        // pending set.
        val orphan = LocalMediaItem(
            uri = "content://media/external/images/media/7",
            dateTaken = 1000L,
            displayName = "old.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT in the selected "Camera" folder
        )
        val orphanSync = syncState(orphan.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(orphan))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(orphanSync))
        coEvery { syncStateRepo.getByUri(any()) } returns orphanSync

        useCase(userId).toList()

        coVerify { syncStateRepo.clearQueued(orphan.uri) }
        coVerify(exactly = 0) { syncStateRepo.deleteLocalOnlyByUris(any()) }
    }

    @Test
    fun `a fresh in-scope LOCAL_ONLY row is stamped queued AUTO_FOLDER`() = runTest {
        // A brand-new unmatched in-scope local becomes LOCAL_ONLY and must be queued=AUTO_FOLDER so
        // the queue-gated upload selector picks it up.
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(localItem("uri://fresh")))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.markQueued(
                "uri://fresh",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }

    @Test
    fun `an already-LOCAL_ONLY MANUAL row is not relabelled AUTO_FOLDER`() = runTest {
        // A row the user manually queued must keep its MANUAL source across reconcile: the fresh
        // AUTO_FOLDER stamp only touches rows whose source is null or AUTO_FOLDER.
        val local = localItem("uri://manual")
        val manualSync = syncState("uri://manual", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(manualSync))
        coEvery { syncStateRepo.getByUri(any()) } returns manualSync

        useCase(userId).toList()

        coVerify(exactly = 0) {
            syncStateRepo.markQueued(
                "uri://manual",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }

    // ─── explicit-intent queue robustness (piece 5a) ──────────────────────────

    @Test
    fun `an out-of-scope MANUAL queued row is NOT de-queued`() = runTest {
        // The user manually asked to back this up from a folder that is not in the backup selection.
        // Scope cleanup only de-queues AUTO_FOLDER / null-source rows; an explicit MANUAL intent must
        // upload regardless of folder scope, so its queued flag must survive.
        val outOfScope = LocalMediaItem(
            uri = "content://media/external/images/media/11",
            dateTaken = 1000L,
            displayName = "manual-out.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val manualSync = syncState(outOfScope.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(outOfScope))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(manualSync))
        coEvery { syncStateRepo.getByUri(any()) } returns manualSync

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.clearQueued(outOfScope.uri) }
    }

    @Test
    fun `an out-of-scope null-source queued row IS de-queued`() = runTest {
        // A legacy queued row with no recorded source, out of scope. Null source means an ordinary
        // folder backup (or a pre-source row), so scope cleanup clears its queued flag.
        val outOfScope = LocalMediaItem(
            uri = "content://media/external/images/media/12",
            dateTaken = 1000L,
            displayName = "null-out.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val nullSourceSync = syncState(outOfScope.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = null)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(outOfScope))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(nullSourceSync))
        coEvery { syncStateRepo.getByUri(any()) } returns nullSourceSync

        useCase(userId).toList()

        coVerify { syncStateRepo.clearQueued(outOfScope.uri) }
    }

    @Test
    fun `a stranded MANUAL LOCAL_ONLY row is re-queued under its original source`() = runTest {
        // A manual "back up now" row lost its queued flag but kept queueSource=MANUAL and has no cloud
        // copy, so it would otherwise never retry. Recovery re-queues it under MANUAL. Out of the folder
        // scope on purpose so the AUTO_FOLDER stamp path can't be what re-queues it.
        val stranded = LocalMediaItem(
            uri = "content://media/external/images/media/21",
            dateTaken = 1000L,
            displayName = "stranded.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val strandedSync = syncState(stranded.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(stranded))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(strandedSync))
        coEvery { syncStateRepo.getByUri(any()) } returns strandedSync

        useCase(userId).toList()

        coVerify {
            syncStateRepo.markQueued(
                stranded.uri,
                eu.akoos.photos.domain.entity.QueueSource.MANUAL,
                any(),
            )
        }
    }

    @Test
    fun `a null-source un-queued LOCAL_ONLY row is NOT re-queued`() = runTest {
        // A row with no source and no queued flag: either a plain out-of-scope local that was never
        // queued, or a MANUAL upload the user cancelled (clearManualQueue nulls the source). Recovery
        // must leave it un-queued: no markQueued of any kind for this URI.
        val idle = LocalMediaItem(
            uri = "content://media/external/images/media/22",
            dateTaken = 1000L,
            displayName = "idle.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // out of scope so the AUTO_FOLDER stamp can't fire either
        )
        val idleSync = syncState(idle.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(idle))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(idleSync))
        coEvery { syncStateRepo.getByUri(any()) } returns idleSync

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.markQueued(eq(idle.uri), any(), any()) }
    }

    @Test
    fun `a backed-up photo whose cloud copy is deleted is NOT re-queued for upload`() = runTest {
        // Regression: a photo that was backed up (SYNCED) but still carries a leftover
        // queueSource=MANUAL, then had its cloud copy deleted by the user, must NOT be re-uploaded.
        // The cloud-absent demotion flips it to LOCAL_ONLY+cloudFileId=null AND clears the stale
        // intent via clearQueuedForSynced, so the stranded-intent recovery does not re-queue it into
        // an endless re-upload of the deletion. The local file is still on the device (cloud-absent
        // branch, not the local-gone CLOUD_ONLY branch).
        val local = LocalMediaItem(
            uri = "content://media/external/images/media/50",
            dateTaken = 1000L,
            displayName = "restored.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        // lastSyncSuccessMs = null (default) makes the grace window elapsed. queueSource=MANUAL is the
        // spent intent left over from the original backup.
        val deletedCloudSync = syncState(local.uri, cloudId = "link-del", status = SyncStatus.SYNCED,
            queued = false, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        // Cloud copy was deleted → the listing no longer contains "link-del".
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(deletedCloudSync))
        coEvery { syncStateRepo.getByUri(any()) } returns deletedCloudSync

        useCase(userId).toList()

        // The demotion clears the spent intent, and the recovery must NOT re-queue the row.
        coVerify { syncStateRepo.clearQueuedForSynced(local.uri) }
        coVerify(exactly = 0) { syncStateRepo.markQueued(eq(local.uri), any(), any()) }
    }

    // ─── reconcile-vs-upload clobber guard ────────────────────────────────────

    @Test
    fun `a snapshot LOCAL_ONLY row an upload promoted mid-pass is guarded and not re-queued`() = runTest {
        // The snapshot saw this in-scope row as LOCAL_ONLY and, with no cloud match, reconcile computes
        // LOCAL_ONLY again. But a concurrent upload finished mid-pass and flipped the DB row to
        // SYNCED+cloudFileId, so the guarded update reports 0 rows changed. Reconcile must route the
        // write through that guard (never the plain batch, which would clobber the fresh pairing) AND
        // must not re-queue the uri, since a finished upload is not queued for a duplicate.
        val racing = localItem("uri://racing")
        val snapshot = syncState("uri://racing", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(racing))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(snapshot))
        coEvery { syncStateRepo.getByUri(any()) } returns snapshot
        // The DB row is SYNCED now, so the guard no-ops (0 rows changed).
        coEvery {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(match { it.localUri == "uri://racing" }, userId)
        } returns 0

        useCase(userId).toList()

        // Routed through the guard, carrying the computed LOCAL_ONLY state ...
        coVerify {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://racing" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
        // ... never through the unguarded batch ...
        coVerify(exactly = 0) {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://racing" } },
                userId,
            )
        }
        // ... and not re-queued, because the guard reported the row already finished uploading.
        coVerify(exactly = 0) { syncStateRepo.markQueued(eq("uri://racing"), any(), any()) }
    }

    @Test
    fun `a snapshot LOCAL_ONLY row still un-synced is guarded-updated and re-queued`() = runTest {
        // Same shape, but the DB row is genuinely still LOCAL_ONLY, so the guard reports 1 row changed.
        // Reconcile still routes through the guard, and because the row was not promoted it IS stamped
        // queued=AUTO_FOLDER so the upload selector picks it up.
        val pending = localItem("uri://pending")
        val snapshot = syncState("uri://pending", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(pending))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(snapshot))
        coEvery { syncStateRepo.getByUri(any()) } returns snapshot
        coEvery {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(match { it.localUri == "uri://pending" }, userId)
        } returns 1

        useCase(userId).toList()

        coVerify {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://pending" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
        coVerify {
            syncStateRepo.markQueued(
                "uri://pending",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }
}
