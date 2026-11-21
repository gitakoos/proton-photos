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

package eu.akoos.photos.data.repository.drive

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.PhotoLocationResolver
import eu.akoos.photos.domain.entity.CloudPhoto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A downloaded or pinned file fills in a missing map place, but never one taken off on purpose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordFileFixTest {

    private val userId = UserId("u")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val listing = mockk<PhotoListingDao>()
    private val locations = mockk<PhotoLocationDao>(relaxed = true)
    private val resolver = mockk<PhotoLocationResolver>()

    private val photo = CloudPhoto(
        linkId = "l1", shareId = "s", volumeId = "v", captureTime = 1L, displayName = "a.jpg",
        mimeType = "image/jpeg", sizeBytes = 1L, thumbnailUrl = null, revisionId = "r1",
    )

    private val row = PhotoListingEntity(
        linkId = "l1", shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = "a.jpg", mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r1", encXAttr = "armored",
    )

    private fun service() = PhotoDownloadService(
        apiProvider = mockk(relaxed = true),
        cryptoHelper = mockk(relaxed = true),
        cryptoServiceClient = mockk(relaxed = true),
        shareService = mockk(relaxed = true),
        linkDetailHelpers = mockk(relaxed = true),
        cdnBlockFetcher = mockk(relaxed = true),
        albumCryptoChain = mockk(relaxed = true),
        sharedAlbumKeyStore = mockk(relaxed = true),
        photoListingDao = listing,
        sdkRouting = mockk(relaxed = true),
        sdkPhotoDownloadOps = mockk(relaxed = true),
        photoLocationDao = locations,
        photoLocationResolver = resolver,
        context = context,
    )

    private fun jpegWithPlace(): String {
        val file = File.createTempFile("fix_", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply { setLatLong(38.9067, 1.4206); saveAttributes() }
        return Uri.fromFile(file).toString()
    }

    @Test
    fun `a place removed from an image's XAttr is not brought back from its file`() = runTest {
        coEvery { locations.getById("u", "l1") } returns null
        coEvery { listing.getByLinkId("l1") } returns row
        coEvery { resolver.decryptXAttr(userId, row, "armored") } returns """{"Camera":{"Orientation":1}}"""

        service().recordFileFix(userId, photo, jpegWithPlace())

        coVerify(exactly = 0) { locations.upsert(any()) }
    }

    @Test
    fun `an image whose XAttr was written without reading its EXIF takes its place from its file`() = runTest {
        coEvery { locations.getById("u", "l1") } returns null
        coEvery { listing.getByLinkId("l1") } returns row
        coEvery { resolver.decryptXAttr(userId, row, "armored") } returns """{"Common":{"Size":10}}"""

        service().recordFileFix(userId, photo, jpegWithPlace())

        coVerify { locations.upsert(match { it.single().id == "l1" }) }
    }
}
