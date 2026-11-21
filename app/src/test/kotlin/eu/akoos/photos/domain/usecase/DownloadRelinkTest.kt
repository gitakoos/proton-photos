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

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * A download links a gallery file to the cloud photo, as its backup, only when the file holds that
 * photo's bytes. A different photo that only shares its name and size is never linked, so free-up and
 * delete-after-backup can never take it for backed up; the photo is saved beside it instead. A copy the
 * download wrote records its size and SHA-1 as the backed-up bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRelinkTest {

    private lateinit var context: Context
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var syncRepo: SyncStateRepository
    private lateinit var useCase: DownloadPhotosUseCase
    private val userId = UserId("test-user")
    private val upserts = mutableListOf<SyncState>()
    private val originals = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Media.reset(context)
        Robolectric.setupContentProvider(Media::class.java, MediaStore.AUTHORITY)
        cloud = FakeDrivePhotoRepository()
        cloud.contentHashOf = { sha1 -> "drive:$sha1" }
        syncRepo = mockk(relaxed = true)
        coEvery { syncRepo.upsert(any(), any()) } answers { upserts += firstArg<SyncState>() }
        coEvery { syncRepo.setOriginalHash(any(), any()) } answers { originals[firstArg()] = secondArg() }
        coEvery { syncRepo.getByCloudId(any()) } returns null
        val linkDetails = mockk<eu.akoos.photos.data.repository.drive.LinkDetailHelpers>()
        coEvery { linkDetails.batchFetchLinkDetails(any(), any(), any()) } returns emptyMap()
        useCase = DownloadPhotosUseCase(
            context = context,
            cloudRepo = cloud,
            syncStateRepo = syncRepo,
            linkDetailHelpers = linkDetails,
            locationResolver = mockk { coEvery { resolve(any(), any()) } returns null },
        )
    }

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun photo(sizeBytes: Long, contentHash: String?) = CloudPhoto(
        linkId = "cloud-1", shareId = "share", volumeId = "vol", captureTime = 0L,
        displayName = "IMG_0001.png", mimeType = "image/png", sizeBytes = sizeBytes,
        thumbnailUrl = null, revisionId = "rev", contentHash = contentHash,
    )

    private val usersOwn = ByteArray(64) { 1 }
    private val cloudBytes = ByteArray(64) { 2 }

    @Test
    fun `a gallery file of the same name and size but other bytes is never linked`() = runTest {
        val own = Media.add("IMG_0001.png", usersOwn)
        cloud.downloadBytes = cloudBytes

        val progress = useCase.downloadGalleryItems(
            userId, listOf(GalleryItem.CloudOnly(photo(64L, "drive:${sha1(cloudBytes)}"))),
        )

        assertEquals(1, progress.done)
        assertEquals(listOf("cloud-1"), cloud.downloadedLinkIds)
        assertTrue("the user's own file is never linked", upserts.none { it.localUri == own.toString() })
        assertTrue(own.toString() !in originals)
        assertArrayEquals(usersOwn, Media.bytes(own))
        val saved = upserts.single()
        assertTrue("saved beside it", saved.localUri != own.toString())
        assertArrayEquals(cloudBytes, Media.bytes(Uri.parse(saved.localUri)))
    }

    @Test
    fun `the save-time check with the real bytes keeps another photo of the same name and size apart`() = runTest {
        // No Drive size or hash in the listing: only the save-time check runs.
        val own = Media.add("IMG_0001.png", usersOwn)
        cloud.downloadBytes = cloudBytes

        useCase.downloadGalleryItems(userId, listOf(GalleryItem.CloudOnly(photo(0L, null))))

        assertTrue(upserts.none { it.localUri == own.toString() })
        assertEquals(2, Media.rows.size)
    }

    @Test
    fun `a gallery file holding the photo's own bytes is linked without a download`() = runTest {
        val existing = Media.add("IMG_0001.png", cloudBytes)

        val progress = useCase.downloadGalleryItems(
            userId, listOf(GalleryItem.CloudOnly(photo(64L, "drive:${sha1(cloudBytes)}"))),
        )

        assertEquals(1, progress.skipped)
        assertTrue(cloud.downloadedLinkIds.isEmpty())
        val linked = upserts.single()
        assertEquals(existing.toString(), linked.localUri)
        assertEquals(SyncStatus.SYNCED, linked.status)
        assertEquals(sha1(cloudBytes), originals[existing.toString()])
    }

    @Test
    fun `a file of the bytes a download is about to save is reused, not saved twice`() = runTest {
        val earlier = Media.add("IMG_0001.png", cloudBytes)
        cloud.downloadBytes = cloudBytes
        cloud.contentHashOf = { null }

        useCase.downloadGalleryItems(userId, listOf(GalleryItem.CloudOnly(photo(64L, null))))

        assertEquals(1, Media.rows.size)
        assertEquals(earlier.toString(), upserts.single().localUri)
        assertEquals(sha1(cloudBytes), originals[earlier.toString()])
    }

    @Test
    fun `a photo already linked on the device is left as it is`() = runTest {
        val existing = Media.add("IMG_0001.png", cloudBytes)
        coEvery { syncRepo.getByCloudId("cloud-1") } returns SyncState(
            localUri = existing.toString(), cloudFileId = "cloud-1", localHash = "", cloudHash = null,
            status = SyncStatus.SYNCED, lastSyncAttemptMs = 1L, lastSyncSuccessMs = 1L, backedUpAtMs = 1L, sizeBytes = 64L,
        )

        val progress = useCase.downloadCloudPhotos(userId, listOf(photo(64L, null)), folderName = "")

        assertEquals(1, progress.skipped)
        assertTrue("no fresh backup stamp for whatever the file holds now", upserts.isEmpty())
        coVerify(exactly = 0) { syncRepo.setOriginalHash(any(), any()) }
    }

    @Test
    fun `a device file linked to the photo by name alone never stops the photo being saved`() = runTest {
        // Same name and capture second, other bytes: shown as one item, but no proof the photo is here.
        val own = Media.add("IMG_0001.png", usersOwn)
        cloud.downloadBytes = cloudBytes
        coEvery { syncRepo.getByCloudId("cloud-1") } returns SyncState(
            localUri = own.toString(), cloudFileId = "cloud-1", localHash = "", cloudHash = null,
            status = SyncStatus.SYNCED, lastSyncAttemptMs = 1L, lastSyncSuccessMs = 1L, backedUpAtMs = null,
            sizeBytes = 64L, linkBasis = LinkBasis.NAME,
        )

        val progress = useCase.downloadCloudPhotos(userId, listOf(photo(64L, "drive:${sha1(cloudBytes)}")), folderName = "")

        assertEquals(1, progress.done)
        assertEquals(listOf("cloud-1"), cloud.downloadedLinkIds)
        assertArrayEquals(usersOwn, Media.bytes(own))
    }

    @Test
    fun `a download records the size and SHA-1 of the copy it wrote`() = runTest {
        val written = ByteArray(80) { 3 }
        cloud.downloadBytes = written

        useCase.downloadCloudPhotos(userId, listOf(photo(64L, null)), folderName = "")

        val row = upserts.single()
        assertEquals(80L, row.sizeBytes)
        assertEquals("", row.localHash)
        assertTrue(row.backedUpAtMs != null)
        assertEquals(sha1(written), originals[row.localUri])
    }

    /** A gallery that matches the duplicate check by name and size and serves real file bytes. */
    class Media : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val id = uri.lastPathSegment?.toLongOrNull()
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val cursor = MatrixCursor(cols)
            val matches = if (id != null) {
                rows.filterKeys { it == id }
            } else {
                rows.filter { (rowId, name) ->
                    name == args?.getOrNull(0) && file(rowId).length().toString() == args.getOrNull(1)
                }
            }
            for ((rowId, _) in matches) {
                cursor.addRow(
                    cols.map<String, Any> { col ->
                        when (col) {
                            MediaStore.MediaColumns._ID -> rowId
                            MediaStore.MediaColumns.RELATIVE_PATH -> "DCIM/Camera/"
                            MediaStore.MediaColumns.SIZE -> file(rowId).length()
                            else -> 0L
                        }
                    }.toTypedArray(),
                )
            }
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId++
            rows[id] = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            return ContentUris.withAppendedId(uri, id)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = file(uri.lastPathSegment!!.toLong())
            return if (mode.contains("w")) {
                ParcelFileDescriptor.open(
                    f,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            } else {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 1

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            rows.remove(uri.lastPathSegment?.toLongOrNull() ?: return 0)
            return 1
        }

        override fun getType(uri: Uri): String? = null

        companion object {
            val rows = mutableMapOf<Long, String>()
            private var nextId = 100L
            private lateinit var dir: File

            fun reset(context: Context) {
                rows.clear()
                nextId = 100L
                dir = File(context.cacheDir, "relink-media").also { it.deleteRecursively(); it.mkdirs() }
            }

            fun file(id: Long) = File(dir, "media_$id")

            fun add(name: String, bytes: ByteArray): Uri {
                val id = nextId++
                rows[id] = name
                file(id).writeBytes(bytes)
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }

            fun bytes(uri: Uri): ByteArray? = uri.lastPathSegment?.toLongOrNull()?.let { file(it) }
                ?.takeIf { it.exists() }?.readBytes()
        }
    }
}
