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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetGalleryItemsUseCaseTest {

    private lateinit var useCase: GetGalleryItemsUseCase

    @Before
    fun setUp() {
        // merge() is pure (it never touches the repos or scope for the inputs used here), so plain
        // mocks and a throwaway scope suffice. The tests call merge() directly rather than the flow
        // pipeline, which runs on Dispatchers.Default + sample() and cannot be driven by virtual time.
        useCase = GetGalleryItemsUseCase(
            mockk<LocalMediaRepository>(),
            mockk<DrivePhotoRepository>(),
            mockk<SyncStateSnapshot>(),
            CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
        )
    }

    private fun localItem(uri: String, dateTaken: Long = 1000L) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = "photo_$uri.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    private fun cloudPhoto(linkId: String, captureTime: Long = 1L) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTime,
        displayName = "photo_$linkId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun syncState(uri: String, cloudId: String?, status: SyncStatus = SyncStatus.SYNCED) =
        SyncState(
            localUri = uri,
            cloudFileId = cloudId,
            localHash = "",
            cloudHash = null,
            status = status,
            lastSyncAttemptMs = 0L,
            lastSyncSuccessMs = null,
            backedUpAtMs = null,
            sizeBytes = 1024L,
        )

    @Test
    fun `local only items when no cloud photos exist`() {
        val local = listOf(localItem("uri://1"), localItem("uri://2"))

        val result = useCase.merge(local, emptyList(), emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.LocalOnly })
    }

    @Test
    fun `cloud only items when no local media exists`() {
        val cloud = listOf(cloudPhoto("link1"), cloudPhoto("link2"))

        val result = useCase.merge(emptyList(), cloud, emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `synced item when local matches cloud via cloudFileId`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))

        val result = useCase.merge(local, cloud, states)

        assertEquals(1, result.size)
        val item = result.first()
        assertTrue("Expected Synced, got $item", item is GalleryItem.Synced)
        assertEquals("link1", (item as GalleryItem.Synced).cloud.linkId)
        assertEquals("uri://1", item.local.uri)
    }

    @Test
    fun `mixed items — synced, local only, and cloud only`() {
        val local = listOf(localItem("uri://synced"), localItem("uri://local"))
        val cloud = listOf(cloudPhoto("link-synced"), cloudPhoto("link-cloud-only"))
        val states = listOf(syncState("uri://synced", cloudId = "link-synced"))

        val result = useCase.merge(local, cloud, states)

        assertEquals(3, result.size)
        assertTrue(result.any { it is GalleryItem.Synced })
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `result is sorted by captureTime descending`() {
        val local = listOf(
            localItem("uri://old", dateTaken = 1_000L),
            localItem("uri://new", dateTaken = 9_000L),
        )

        val result = useCase.merge(local, emptyList(), emptyList())

        assertEquals("uri://new", (result[0] as GalleryItem.LocalOnly).local.uri)
        assertEquals("uri://old", (result[1] as GalleryItem.LocalOnly).local.uri)
    }

    @Test
    fun `a link the cloud listing carries twice becomes one CloudOnly item`() {
        // The grid keys a cloud item by its link and throws on a repeat.
        val cloud = listOf(cloudPhoto("link1", captureTime = 5L), cloudPhoto("link2"), cloudPhoto("link1", captureTime = 4L))

        val result = useCase.merge(emptyList(), cloud, emptyList())

        assertEquals(listOf("link1", "link2"), result.map { it.stableId })
        assertEquals(5L, (result.first() as GalleryItem.CloudOnly).cloud.captureTime)
    }

    @Test
    fun `cloud photo already matched is not duplicated as CloudOnly`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))

        val result = useCase.merge(local, cloud, states)

        // Should be exactly 1 Synced item, NOT 1 Synced + 1 CloudOnly
        assertEquals(1, result.size)
        assertTrue(result.first() is GalleryItem.Synced)
    }

    @Test
    fun `sync state without cloudFileId keeps item as LocalOnly`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY))

        val result = useCase.merge(local, cloud, states)

        assertEquals(2, result.size)
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }

    // ─── video name-only pairing fallback ─────────────────────────────────────
    // A cloud video on the photo volume reports sizeBytes = 0, so a name+size join is dead, and an
    // edited video's device copy drifts off the cloud copy's ORIGINAL capture time, so name+second
    // misses too. merge() therefore pairs the ONE unclaimed cloud video that shares the exact name.
    // The defaults below keep the higher-priority keyed paths from firing (cloud size 0, capture
    // times differ) so these tests exercise the name-only branch specifically.

    private fun localVideo(uri: String, name: String, dateTaken: Long = 5000L, size: Long = 999L) =
        LocalMediaItem(
            uri = uri,
            dateTaken = dateTaken,
            displayName = name,
            mimeType = "video/mp4",
            sizeBytes = size,
            bucketName = "Camera",
        )

    private fun cloudVideo(linkId: String, name: String, captureTime: Long = 1L, size: Long = 0L) =
        CloudPhoto(
            linkId = linkId,
            shareId = "share1",
            volumeId = "vol1",
            captureTime = captureTime,
            displayName = name,
            mimeType = "video/mp4",
            sizeBytes = size,
            thumbnailUrl = null,
            revisionId = "rev1",
        )

    @Test
    fun `a device video pairs with a same-named cloud video by name alone`() {
        // Cloud Size is 0 so name+size is dead, and the capture times differ (local 5s, cloud 1s) so
        // name+second misses. Only the video name-only fallback can pair them into one Synced item.
        val local = listOf(localVideo("uri://v1", "VID_0001.mp4", dateTaken = 5000L))
        val cloud = listOf(cloudVideo("link-v1", "VID_0001.mp4", captureTime = 1L, size = 0L))

        val result = useCase.merge(local, cloud, emptyList())

        assertEquals(1, result.size)
        val item = result.first()
        assertTrue("Expected Synced, got $item", item is GalleryItem.Synced)
        assertEquals("link-v1", (item as GalleryItem.Synced).cloud.linkId)
        assertEquals("uri://v1", item.local.uri)
    }

    @Test
    fun `two cloud videos sharing a name do NOT pair the device video`() {
        // Ambiguous: singleOrNull refuses to guess which cloud twin the device video belongs to, so
        // it stays LocalOnly and both cloud videos surface as CloudOnly instead of a wrong merge.
        val local = listOf(localVideo("uri://v1", "VID_0001.mp4"))
        val cloud = listOf(
            cloudVideo("link-a", "VID_0001.mp4"),
            cloudVideo("link-b", "VID_0001.mp4"),
        )

        val result = useCase.merge(local, cloud, emptyList())

        assertEquals(3, result.size)
        assertTrue("ambiguous name must not pair", result.none { it is GalleryItem.Synced })
        assertEquals(1, result.count { it is GalleryItem.LocalOnly })
        assertEquals(2, result.count { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `a device photo with a lost date pairs its same-named cloud photo and shows the cloud date`() {
        // A re-downloaded PNG can land with no reliable DATE_TAKEN (reads as the download day), and cloud
        // Size is 0, so name+second and name+size both miss after a reinstall. The name-only fallback
        // pairs the ONE unclaimed cloud photo of that name, and the Synced item reports the CLOUD capture
        // time, so the gallery shows the right date instead of today.
        val local = listOf(
            LocalMediaItem(
                uri = "uri://p1",
                dateTaken = 1_783_900_000_000L, // the download day, far off the cloud second
                displayName = "Oops!.png",
                mimeType = "image/png",
                sizeBytes = 4096L,
                bucketName = "Camera",
            ),
        )
        val cloud = listOf(
            CloudPhoto(
                linkId = "link-p1",
                shareId = "share1",
                volumeId = "vol1",
                captureTime = 1_783_507_135L,
                displayName = "Oops!.png",
                mimeType = "image/png",
                sizeBytes = 0L,
                thumbnailUrl = null,
                revisionId = "rev1",
            ),
        )

        val result = useCase.merge(local, cloud, emptyList())

        assertEquals(1, result.size)
        val item = result.first()
        assertTrue("Expected Synced, got $item", item is GalleryItem.Synced)
        item as GalleryItem.Synced
        assertEquals("link-p1", item.cloud.linkId)
        assertEquals("the paired photo must show the cloud capture time", 1_783_507_135_000L, item.captureTimeMs)
    }

    @Test
    fun `two cloud photos sharing a name do NOT pair the device photo`() {
        // Same singleOrNull guard as the video path: a recurring name (IMG_0001.jpg across devices) is
        // ambiguous, so the device photo stays LocalOnly rather than merging a wrong pair.
        val local = listOf(
            LocalMediaItem(
                uri = "uri://p1",
                dateTaken = 1_783_900_000_000L,
                displayName = "IMG_0001.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 4096L,
                bucketName = "Camera",
            ),
        )
        val cloud = listOf(
            CloudPhoto(linkId = "link-a", shareId = "share1", volumeId = "vol1", captureTime = 1_783_507_135L,
                displayName = "IMG_0001.jpg", mimeType = "image/jpeg", sizeBytes = 0L, thumbnailUrl = null, revisionId = "rev1"),
            CloudPhoto(linkId = "link-b", shareId = "share1", volumeId = "vol1", captureTime = 1_783_507_200L,
                displayName = "IMG_0001.jpg", mimeType = "image/jpeg", sizeBytes = 0L, thumbnailUrl = null, revisionId = "rev1"),
        )

        val result = useCase.merge(local, cloud, emptyList())

        assertTrue("ambiguous name must not pair", result.none { it is GalleryItem.Synced })
        assertEquals(1, result.count { it is GalleryItem.LocalOnly })
        assertEquals(2, result.count { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `a cloud video already claimed is not reused by a second same-named video`() {
        // Two device videos share one cloud twin's name. The first claims it via the name-only
        // fallback; the usedCloudIds guard then refuses to hand the SAME cloud video to the second,
        // which stays LocalOnly rather than producing a duplicate Synced pair.
        val local = listOf(
            localVideo("uri://first", "VID_0001.mp4"),
            localVideo("uri://second", "VID_0001.mp4"),
        )
        val cloud = listOf(cloudVideo("link-v1", "VID_0001.mp4"))

        val result = useCase.merge(local, cloud, emptyList())

        assertEquals(2, result.size)
        assertEquals(1, result.count { it is GalleryItem.Synced })
        assertEquals(1, result.count { it is GalleryItem.LocalOnly })
        assertTrue("claimed cloud video must not resurface as CloudOnly", result.none { it is GalleryItem.CloudOnly })
        val synced = result.filterIsInstance<GalleryItem.Synced>().single()
        assertEquals("link-v1", synced.cloud.linkId)
        assertEquals("uri://first", synced.local.uri)
    }

    @Test
    fun `merge drops a cloud-only photo that belongs to a hidden album`() {
        val cloud = listOf(cloudPhoto("hidden-1"), cloudPhoto("visible-1"))

        val result = useCase.merge(emptyList(), cloud, emptyList(), hiddenLinkIds = setOf("hidden-1"))

        assertEquals(1, result.size)
        assertEquals("visible-1", result.filterIsInstance<GalleryItem.CloudOnly>().single().cloud.linkId)
    }

    @Test
    fun `merge drops a synced photo whose cloud copy is in a hidden album`() {
        // A device photo paired with a cloud copy in a hidden album must vanish from the shared list
        // (hidden everywhere at once); its device file is left untouched on disk.
        val local = listOf(localItem("uri://s1"))
        val cloud = listOf(cloudPhoto("hidden-s1"))
        val states = listOf(syncState("uri://s1", "hidden-s1"))

        val result = useCase.merge(local, cloud, states, hiddenLinkIds = setOf("hidden-s1"))

        assertTrue("a synced photo in a hidden album must be dropped", result.isEmpty())
    }

    @Test
    fun `merge keeps a device-only photo even when hidden albums exist`() {
        // An album hide only removes items with a cloud linkId; a LocalOnly device photo has none,
        // so it is never hidden by an album (device-only photos use the separate hidden vault).
        val local = listOf(localItem("uri://local-only"))

        val result = useCase.merge(local, emptyList(), emptyList(), hiddenLinkIds = setOf("anything"))

        assertEquals(1, result.size)
        assertTrue(result.single() is GalleryItem.LocalOnly)
    }

    @Test
    fun `merge drops the cloud twin of a photo vaulted into the Hidden area`() {
        // A photo vaulted into the Hidden area keeps its Drive copy but loses its MediaStore file, so
        // the merge classifies the twin as CloudOnly. The HIDDEN row's cloudFileId is exactly that
        // twin's cloud.linkId, so promoting the HIDDEN signal to a filter drops it. No explicit
        // hiddenLinkIds argument is needed, the syncState alone carries the signal.
        val cloud = listOf(cloudPhoto("vaulted-1"), cloudPhoto("visible-1"))
        val states = listOf(syncState("uri://gone", cloudId = "vaulted-1", status = SyncStatus.HIDDEN))

        val result = useCase.merge(emptyList(), cloud, states)

        assertEquals(1, result.size)
        assertEquals("visible-1", result.filterIsInstance<GalleryItem.CloudOnly>().single().cloud.linkId)
    }

    @Test
    fun `isInHiddenAlbum matches cloud and synced items but never local-only`() {
        val hidden = setOf("h1")
        assertTrue(useCase.isInHiddenAlbum(GalleryItem.CloudOnly(cloudPhoto("h1")), hidden))
        assertTrue(useCase.isInHiddenAlbum(GalleryItem.Synced(cloudPhoto("h1"), localItem("uri://x")), hidden))
        assertFalse(useCase.isInHiddenAlbum(GalleryItem.CloudOnly(cloudPhoto("other")), hidden))
        assertFalse(useCase.isInHiddenAlbum(GalleryItem.LocalOnly(localItem("uri://y")), hidden))
        assertFalse("an empty hidden set hides nothing", useCase.isInHiddenAlbum(GalleryItem.CloudOnly(cloudPhoto("h1")), emptySet()))
    }

    // ─── session-free local-only feed ─────────────────────────────────────────
    // invokeLocalOnly() backs the no-account (local-only) mode: it maps the device's own media into
    // GalleryItem.LocalOnly with no Proton session, so it must reach none of the userId-gated or
    // session-scoped sources and yield exactly what invoke()'s LocalOnly rows would be.

    @Test
    fun `invokeLocalOnly emits only LocalOnly items and touches no session-scoped source`() = runBlocking {
        val local = listOf(localItem("uri://old", dateTaken = 1_000L), localItem("uri://new", dateTaken = 9_000L))
        val localRepo = mockk<LocalMediaRepository>()
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        val cloudRepo = mockk<DrivePhotoRepository>()
        val syncSnapshot = mockk<SyncStateSnapshot>()
        val localOnlyUseCase = GetGalleryItemsUseCase(
            localRepo,
            cloudRepo,
            syncSnapshot,
            CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
        )

        val result = localOnlyUseCase.invokeLocalOnly().first()

        assertEquals(2, result.size)
        assertTrue("every item must be LocalOnly", result.all { it is GalleryItem.LocalOnly })
        // Same capture-time-descending order invoke() applies, so a no-account user sees the identical
        // arrangement a signed-in user's local-only items get.
        assertEquals(
            listOf("uri://new", "uri://old"),
            result.filterIsInstance<GalleryItem.LocalOnly>().map { it.local.uri },
        )
        verify(exactly = 0) { cloudRepo.observeCloudPhotos(any()) }
        verify(exactly = 0) { syncSnapshot.observe(any()) }
        verify(exactly = 0) { cloudRepo.observeHiddenAlbumMemberLinkIds() }
    }

    // ─── quick start ──────────────────────────────────────────────────────────

    private fun quickStartUseCase(newest: suspend () -> List<LocalMediaItem>): GetGalleryItemsUseCase {
        val localRepo = mockk<LocalMediaRepository>()
        coEvery { localRepo.newestMedia(any()) } coAnswers { newest() }
        return GetGalleryItemsUseCase(
            localRepo,
            mockk<DrivePhotoRepository>(),
            mockk<SyncStateSnapshot>(),
            CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
        )
    }

    @Test
    fun `quick start puts the newest photos ahead of the full feed`() = runBlocking {
        val newest = listOf(localItem("uri://new", dateTaken = 9_000L))
        val fullList = listOf(
            GalleryItem.LocalOnly(localItem("uri://new", dateTaken = 9_000L)),
            GalleryItem.LocalOnly(localItem("uri://old", dateTaken = 1_000L)),
        )
        val full = flow {
            delay(300)
            emit(fullList)
        }

        val emitted = quickStartUseCase { newest }.withQuickStart(null, full).toList()

        assertEquals(2, emitted.size)
        assertEquals(listOf("uri://new"), emitted[0].filterIsInstance<GalleryItem.LocalOnly>().map { it.local.uri })
        assertEquals(fullList, emitted[1])
    }

    @Test
    fun `quick start never lands after the full feed`() = runBlocking {
        val fullList = listOf(GalleryItem.LocalOnly(localItem("uri://old", dateTaken = 1_000L)))

        val emitted = quickStartUseCase {
            delay(300)
            listOf(localItem("uri://new", dateTaken = 9_000L))
        }.withQuickStart(null, flowOf(fullList)).toList()

        assertEquals(listOf(fullList), emitted)
    }

    @Test
    fun `quick start with nothing to read leaves just the full feed`() = runBlocking {
        val fullList = listOf(GalleryItem.LocalOnly(localItem("uri://a")))

        val emitted = quickStartUseCase { emptyList() }.withQuickStart(null, flowOf(fullList)).toList()

        assertEquals(listOf(fullList), emitted)
    }

    // ─── lean sync rows ───────────────────────────────────────────────────────
    // The signed-in feed merges against the shared lean copy of the sync rows. It must give exactly
    // the list the full rows give: the same pairs through every pairing path, the same badges, the
    // same hidden photos gone, in the same order. The fixture reaches every branch the rows steer.

    /** The row as the lean query returns it: the five projected columns and nothing else. */
    private fun SyncState.lite() = SyncStateLite(
        localUri = localUri,
        cloudFileId = cloudFileId,
        localHash = localHash,
        status = status,
        queued = queued,
    )

    private fun fullRow(
        uri: String,
        cloudId: String?,
        status: SyncStatus,
        localHash: String = "",
        queued: Boolean = false,
    ) = SyncState(
        localUri = uri,
        cloudFileId = cloudId,
        localHash = localHash,
        // Columns the projection leaves out, set to values that would show if the merge read them.
        cloudHash = "cloud-hash-$uri",
        status = status,
        lastSyncAttemptMs = 11L,
        lastSyncSuccessMs = 12L,
        backedUpAtMs = 13L,
        sizeBytes = 4_096L,
        queued = queued,
        queueSource = if (queued) "AUTO_FOLDER" else null,
    )

    private fun device(uri: String, name: String, dateTakenMs: Long, mime: String = "image/jpeg", size: Long = 2_048L) =
        LocalMediaItem(uri = uri, dateTaken = dateTakenMs, displayName = name, mimeType = mime, sizeBytes = size, bucketName = "Camera")

    private fun drive(linkId: String, name: String, captureSec: Long, mime: String = "image/jpeg", size: Long = 0L, contentHash: String? = null) =
        CloudPhoto(
            linkId = linkId,
            shareId = "share1",
            volumeId = "vol1",
            captureTime = captureSec,
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            thumbnailUrl = null,
            revisionId = "rev1",
            contentHash = contentHash,
        )

    private val fixtureDevice = listOf(
        device("uri://paired", "IMG_1.jpg", 1_700_000_100_000L),
        device("uri://stale-id", "DSC_2.jpg", 1_650_000_000_000L),
        device("uri://hash", "DSC_3.jpg", 1_650_000_001_000L),
        device("uri://name-date", "IMG_4.jpg", 1_700_000_400_500L),
        device("uri://video", "VID_5.mp4", 1_700_000_500_000L, mime = "video/mp4", size = 5_000_000L),
        device("uri://name-only", "Oops!.png", 1_783_900_000_000L, mime = "image/png"),
        device("uri://claimed-twice", "DSC_7.jpg", 1_650_000_002_000L),
        device("uri://local-only", "DSC_8.jpg", 1_650_000_003_000L),
        device("uri://album-hidden-pair", "IMG_10.jpg", 1_700_001_000_000L),
        device("uri://hash-miss", "DSC_11.jpg", 1_650_000_004_000L),
    )

    private val fixtureDrive = listOf(
        drive("c-paired", "IMG_1.jpg", 1_700_000_100L),
        drive("c-by-hash", "renamed_2.jpg", 1_700_000_200L, contentHash = "hmac-sha-2"),
        drive("c-by-hash-only", "renamed_3.jpg", 1_700_000_300L, contentHash = "hmac-sha-3"),
        drive("c-by-name-date", "IMG_4.jpg", 1_700_000_400L),
        drive("c-video", "VID_5.mp4", 1_600_000_000L, mime = "video/mp4", size = 5_000_000L),
        drive("c-name-only", "Oops!.png", 1_700_000_600L, mime = "image/png"),
        drive("c-cloud-only", "IMG_7.jpg", 1_700_000_700L),
        drive("c-vaulted", "IMG_8.jpg", 1_700_000_800L),
        drive("c-album-hidden", "IMG_9.jpg", 1_700_000_900L),
        drive("c-album-hidden-pair", "IMG_10.jpg", 1_700_001_000L),
        drive("c-same-time-b", "IMG_12.jpg", 1_700_001_100L),
        drive("c-same-time-a", "IMG_11.jpg", 1_700_001_100L),
    )

    private val fixtureRows = listOf(
        // Paired by the stored cloud id.
        fullRow("uri://paired", "c-paired", SyncStatus.SYNCED),
        // A stored cloud id Drive no longer lists falls through to the stored content hash.
        fullRow("uri://stale-id", "c-gone", SyncStatus.SYNCED, localHash = "sha-2"),
        fullRow("uri://hash", null, SyncStatus.LOCAL_ONLY, localHash = "sha-3"),
        // A row with neither id nor hash leaves the pairing to name and capture second.
        fullRow("uri://name-date", null, SyncStatus.LOCAL_ONLY),
        // Its cloud id is already claimed by the row above, so it must not pair a second time.
        fullRow("uri://claimed-twice", "c-paired", SyncStatus.SYNCED),
        fullRow("uri://local-only", null, SyncStatus.LOCAL_ONLY, queued = true),
        fullRow("uri://album-hidden-pair", "c-album-hidden-pair", SyncStatus.SYNCED),
        fullRow("uri://hash-miss", null, SyncStatus.LOCAL_ONLY, localHash = "sha-unknown"),
        // Vaulted photos: the one with a cloud id drops its Drive copy, the other drops nothing.
        fullRow("uri://vaulted", "c-vaulted", SyncStatus.HIDDEN),
        fullRow("uri://vaulted-unpaired", null, SyncStatus.HIDDEN),
        // A freed-up device file: its row names a device uri that is gone, so it steers nothing.
        fullRow("uri://freed", "c-cloud-only", SyncStatus.CLOUD_ONLY),
    )

    private val fixtureAlbumHidden = setOf("c-album-hidden", "c-album-hidden-pair")

    /** A use case whose Drive stand-in answers the content-hash conversion, which the hash path needs. */
    private fun hashingUseCase(): GetGalleryItemsUseCase {
        val cloudRepo = mockk<DrivePhotoRepository>()
        every { cloudRepo.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }
        return GetGalleryItemsUseCase(
            mockk<LocalMediaRepository>(),
            cloudRepo,
            mockk<SyncStateSnapshot>(),
            CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
        )
    }

    @Test
    fun `the lean sync rows merge into exactly the list the full rows give`() {
        val merger = hashingUseCase()
        val lean = SyncStateSnapshot.Rows(fixtureRows.map { it.lite() })

        val fromFull = merger.merge(fixtureDevice, fixtureDrive, fixtureRows, hiddenLinkIds = fixtureAlbumHidden)
        val fromLean = merger.merge(fixtureDevice, fixtureDrive, lean, hiddenLinkIds = fixtureAlbumHidden)

        assertEquals("same items, same order", fromFull, fromLean)
        assertEquals(
            "every pairing path pairs the same device file with the same Drive copy",
            mapOf(
                "uri://paired" to "c-paired",
                "uri://stale-id" to "c-by-hash",
                "uri://hash" to "c-by-hash-only",
                "uri://name-date" to "c-by-name-date",
                "uri://video" to "c-video",
                "uri://name-only" to "c-name-only",
            ),
            fromLean.filterIsInstance<GalleryItem.Synced>().associate { it.local.uri to it.cloud.linkId },
        )
        assertEquals(
            setOf("uri://claimed-twice", "uri://local-only", "uri://hash-miss"),
            fromLean.filterIsInstance<GalleryItem.LocalOnly>().map { it.local.uri }.toSet(),
        )
        assertEquals(
            "the vaulted twin and both album-hidden photos are gone",
            listOf("c-same-time-a", "c-same-time-b", "c-cloud-only"),
            fromLean.filterIsInstance<GalleryItem.CloudOnly>().map { it.cloud.linkId },
        )
    }

    @Test
    fun `the lean sync rows merge like the full rows with no album hidden`() {
        val merger = hashingUseCase()
        val lean = SyncStateSnapshot.Rows(fixtureRows.map { it.lite() })

        val fromFull = merger.merge(fixtureDevice, fixtureDrive, fixtureRows)
        val fromLean = merger.merge(fixtureDevice, fixtureDrive, lean)

        assertEquals(fromFull, fromLean)
        assertTrue(
            "the album-hidden pair shows when no album is hidden",
            fromLean.any { it is GalleryItem.Synced && it.cloud.linkId == "c-album-hidden-pair" },
        )
        assertTrue(
            "the vaulted twin stays out whatever the album hides",
            fromLean.none { it is GalleryItem.CloudOnly && it.cloud.linkId == "c-vaulted" },
        )
    }

    @Test
    fun `empty lean sync rows merge like no rows at all`() {
        val merger = hashingUseCase()

        assertEquals(
            merger.merge(fixtureDevice, fixtureDrive, emptyList()),
            merger.merge(fixtureDevice, fixtureDrive, SyncStateSnapshot.Rows(emptyList())),
        )
    }
}
