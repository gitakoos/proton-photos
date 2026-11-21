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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

/** A walk parked on the health gate goes on once the user opens the map, whose own walk finds its files reserved. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalExifUserRequestTest {

    private lateinit var app: Application
    private val written = mutableListOf<LocalExifCheckEntity>()
    private lateinit var media: LocalMediaRepository
    private val health = mockk<DeviceHealthPolicy>(relaxed = true)

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
        return LocalExifBackfillScheduler(app, media, locations, checks, health)
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
    fun `a walk waiting on the health gate goes on when the user asks for one`() = runTest {
        val plain = File.createTempFile("plain_", ".jpg", app.cacheDir)
        plain.outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        coEvery { media.freshMedia() } returns listOf(photo(plain))
        coEvery { health.awaitBackgroundWorkAllowed() } coAnswers { awaitCancellation() }
        val walk = scheduler()

        launch { walk.backfillAll(null) }
        runCurrent()
        assertTrue(written.isEmpty())

        walk.backfillAll(null, respectHealthGate = false)
        advanceUntilIdle()

        assertEquals(1, written.size)
    }
}
