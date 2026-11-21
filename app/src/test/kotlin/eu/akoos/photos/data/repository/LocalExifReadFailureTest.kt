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

package eu.akoos.photos.data.repository

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.LocalExifCheckDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.LocalExifCheckEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.DeviceHealthPolicy
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A device photo whose file cannot be read leaves no check behind, so the next walk reads it again; a photo
 * that was read and simply carries no GPS is still recorded as having none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalExifReadFailureTest {

    private lateinit var app: Application
    private val written = mutableListOf<LocalExifCheckEntity>()
    private lateinit var media: LocalMediaRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        media = mockk(relaxed = true)
    }

    private fun scheduler(): LocalExifBackfillScheduler {
        val locations = mockk<PhotoLocationDao>(relaxed = true)
        coEvery { locations.idsForUser(any()) } returns emptyList()
        val checks = mockk<LocalExifCheckDao>(relaxed = true)
        coEvery { checks.all() } returns emptyList()
        val batch = slot<List<LocalExifCheckEntity>>()
        coEvery { checks.upsert(capture(batch)) } answers { written += batch.captured }
        return LocalExifBackfillScheduler(app, media, locations, checks, mockk<DeviceHealthPolicy>(relaxed = true))
    }

    private fun photo(file: File) = LocalMediaItem(
        uri = Uri.fromFile(file).toString(),
        dateTaken = 1_700_000_000_000L,
        displayName = file.name,
        mimeType = "image/jpeg",
        sizeBytes = 1L,
        bucketName = null,
        dateModified = 1_700_000_000L,
    )

    @Test
    fun `a photo whose file cannot be opened is left for a later walk`() = runTest {
        val missing = File(app.cacheDir, "gone.jpg")
        coEvery { media.freshMedia() } returns listOf(photo(missing))

        scheduler().backfillAll(null)

        assertTrue(written.isEmpty())
    }

    @Test
    fun `a photo that was read and carries no GPS is recorded as having none`() = runTest {
        val plain = File.createTempFile("plain_", ".jpg", app.cacheDir)
        plain.outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        coEvery { media.freshMedia() } returns listOf(photo(plain))

        scheduler().backfillAll(null)

        assertEquals(listOf(true), written.map { it.gpsNone })
    }
}
