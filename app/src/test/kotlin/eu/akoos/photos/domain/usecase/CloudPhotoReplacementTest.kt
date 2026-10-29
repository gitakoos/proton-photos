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
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The replacement compression in place runs on. The upload-before-trash and album guards are pinned through
 * [WriteCloudPhotoMetadataUseCaseTest]; these pin what compression adds: a declined or failed preparation
 * changes nothing on Drive, the downloaded original is never written to, and no temp file is left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudPhotoReplacementTest {

    private val userId = UserId("u")
    private lateinit var context: Context
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var replacement: CloudPhotoReplacement
    private val workDir get() = File(context.cacheDir, CloudPhotoReplacement.WORK_DIR)

    private val photo = CloudPhoto(
        linkId = "orig",
        shareId = "share",
        volumeId = "vol1",
        captureTime = 1_600_000_000L,
        displayName = "IMG_1.png",
        mimeType = "image/png",
        sizeBytes = 4096L,
        thumbnailUrl = null,
        revisionId = "rev",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cloud = FakeDrivePhotoRepository()
        cloud.add(photo)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        cloud.downloadBytes = ByteArray(4096) { it.toByte() }
        replacement = CloudPhotoReplacement(
            context, cloud, CarryOverToReplacement(cloud, noListingRow(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
        )
        workDir.deleteRecursively()
    }

    @Test
    fun `a declined preparation uploads nothing, trashes nothing and leaves no file`() = runTest {
        val result = replacement.replace(userId, photo, { null })

        assertEquals(CloudPhotoReplacement.Result.Skipped, result)
        assertTrue(cloud.uploadedItems.isEmpty())
        assertFalse(cloud.callLog.contains("DELETE"))
        assertTrue(cloud.photos.value.any { it.linkId == "orig" })
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a preparation that fails stops before the upload`() = runTest {
        val result = replacement.replace(userId, photo, { error("encoder broke") })

        assertTrue(result is CloudPhotoReplacement.Result.Failed)
        assertTrue(cloud.uploadedItems.isEmpty())
        assertTrue(cloud.photos.value.any { it.linkId == "orig" })
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a smaller copy replaces the original, which is trashed only after the upload and album`() = runTest {
        var seenOriginal: ByteArray? = null
        val result = replacement.replace(userId, photo, { working ->
            seenOriginal = working.readBytes()
            val copy = File(working.parentFile, "small.jpg").apply { writeBytes(ByteArray(100)) }
            CloudPhotoReplacement.Prepared(
                file = copy,
                item = LocalMediaItem(
                    uri = Uri.fromFile(copy).toString(),
                    dateTaken = photo.captureTimeMs,
                    displayName = "IMG_1.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = copy.length(),
                    bucketName = null,
                ),
                xAttr = UploadXAttrMetadata(),
            )
        })

        assertTrue(result is CloudPhotoReplacement.Result.Success)
        // The working copy held the original's bytes untouched.
        assertArrayEquals(ByteArray(4096) { it.toByte() }, seenOriginal)
        assertEquals("IMG_1.jpg", cloud.uploadedItems.single().displayName)
        val newLink = cloud.uploadedLinkIds.single()
        assertEquals(listOf(newLink), cloud.albumMembers("albumA"))
        assertTrue(cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"))
        assertTrue(cloud.photos.value.none { it.linkId == "orig" })
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a missed album keeps the original even when the copy is up`() = runTest {
        cloud.failAddPhotosOnce = true
        val result = replacement.replace(userId, photo, { working ->
            CloudPhotoReplacement.Prepared(
                file = working,
                item = LocalMediaItem(Uri.fromFile(working).toString(), photo.captureTimeMs, "IMG_1.png", "image/png", working.length(), null),
                xAttr = UploadXAttrMetadata(),
            )
        })

        assertTrue(result is CloudPhotoReplacement.Result.Failed)
        assertFalse(cloud.callLog.contains("DELETE"))
        assertTrue(cloud.photos.value.any { it.linkId == "orig" })
    }

    @Test
    fun `only Drive-only photos and videos are queued for compression`() {
        val cloudPhoto = GalleryItem.CloudOnly(photo)
        val cloudVideo = GalleryItem.CloudOnly(photo.copy(linkId = "vid", mimeType = "video/mp4"))
        val cloudDoc = GalleryItem.CloudOnly(photo.copy(linkId = "doc", mimeType = "application/pdf"))
        val onPhone = GalleryItem.LocalOnly(LocalMediaItem("content://x/1", 1L, "a.jpg", "image/jpeg", 10L, null))

        assertEquals(listOf("orig", "vid"), compressibleLinkIds(listOf(cloudPhoto, cloudVideo, cloudDoc, onPhone, cloudPhoto)))
    }
}
