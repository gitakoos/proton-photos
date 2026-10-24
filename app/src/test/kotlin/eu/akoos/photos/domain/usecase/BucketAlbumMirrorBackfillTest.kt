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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.db.entity.UploadAlbumTargetEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Turning a folder's album on joins the photos it already backed up. */
class BucketAlbumMirrorBackfillTest {

    private val userId = UserId("u")
    private lateinit var cloud: DrivePhotoRepository
    private lateinit var targets: UploadAlbumTargetDao
    private lateinit var mirror: BucketAlbumMirror
    private val transforms = mutableListOf<suspend (Preferences) -> Preferences>()

    @Before
    fun setUp() {
        cloud = mockk(relaxed = true)
        targets = mockk(relaxed = true)
        val context = mockk<Context>()
        val dataStore = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        every { context.settingsDataStore } returns dataStore
        every { dataStore.data } returns flowOf(emptyPreferences())
        coEvery { dataStore.updateData(capture(transforms)) } returns emptyPreferences()
        mirror = BucketAlbumMirror(cloud, context)
    }

    @After
    fun tearDown() {
        unmockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
    }

    private fun album(linkId: String, name: String) =
        Album(linkId = linkId, name = name, photoCount = 0, coverLinkId = null, lastActivityTimeMs = null)

    private fun result(succeeded: List<String>) =
        DrivePhotoRepository.AddPhotosToAlbumResult(succeeded, emptyList())

    private suspend fun storedMap(): Set<String>? {
        var prefs: Preferences = mutablePreferencesOf(SettingsKeys.ALBUM_BUCKET_MAP to setOf("Other=keep"))
        for (t in transforms) prefs = t(prefs)
        return prefs[SettingsKeys.ALBUM_BUCKET_MAP]
    }

    @Test
    fun `a folder with no album gets one, its photos join and only the joined pairs are dropped`() = runTest {
        coEvery { cloud.loadAlbums(userId) } returns listOf(album("a0", "Kutya"))
        coEvery { cloud.createDriveAlbum(userId, "Trip") } returns album("new", "Trip")
        coEvery { cloud.addPhotosToAlbum(userId, "new", listOf("c1", "c2", "c3")) } returns result(listOf("c1", "c3"))
        val queued = slot<List<UploadAlbumTargetEntity>>()
        coEvery { targets.insertIgnoreAll(capture(queued)) } returns Unit

        val n = mirror.backfill(userId, "Trip", linkedMapOf("u1" to "c1", "u2" to "c2", "u3" to "c3"), targets)

        assertEquals(3, n)
        assertEquals(setOf("u1", "u2", "u3"), queued.captured.map { it.localUri }.toSet())
        assertTrue(queued.captured.all { it.albumLinkId == "new" })
        coVerify(exactly = 1) { targets.deleteTarget("u1", "new") }
        coVerify(exactly = 1) { targets.deleteTarget("u3", "new") }
        coVerify(exactly = 0) { targets.deleteTarget("u2", any()) }
        assertEquals(setOf("Other=keep", "Trip=new"), storedMap())
    }

    @Test
    fun `an existing album of the same name is joined, whatever its letter case`() = runTest {
        coEvery { cloud.loadAlbums(userId) } returns listOf(album("a1", "trip"))
        coEvery { cloud.addPhotosToAlbum(userId, "a1", listOf("c1")) } returns result(listOf("c1"))

        mirror.backfill(userId, "Trip", mapOf("u1" to "c1"), targets)

        coVerify(exactly = 0) { cloud.createDriveAlbum(any(), any()) }
        coVerify(exactly = 1) { targets.deleteTarget("u1", "a1") }
    }

    @Test
    fun `a folder with nothing on Drive does nothing`() = runTest {
        assertEquals(0, mirror.backfill(userId, "Trip", emptyMap(), targets))
        coVerify(exactly = 0) { cloud.loadAlbums(any()) }
        coVerify(exactly = 0) { targets.insertIgnoreAll(any()) }
    }

    @Test
    fun `an album list that cannot be read creates no album and queues nothing`() = runTest {
        coEvery { cloud.loadAlbums(userId) } throws RuntimeException("offline")
        try {
            mirror.backfill(userId, "Trip", mapOf("u1" to "c1"), targets)
            fail("expected the failure to surface")
        } catch (e: RuntimeException) {
            assertEquals("offline", e.message)
        }
        coVerify(exactly = 0) { cloud.createDriveAlbum(any(), any()) }
        coVerify(exactly = 0) { targets.insertIgnoreAll(any()) }
    }

    @Test
    fun `an add that fails keeps every pair for the next upload pass`() = runTest {
        coEvery { cloud.loadAlbums(userId) } returns listOf(album("a1", "Trip"))
        coEvery { cloud.addPhotosToAlbum(userId, "a1", any()) } throws RuntimeException("503")

        assertEquals(2, mirror.backfill(userId, "Trip", mapOf("u1" to "c1", "u2" to "c2"), targets))

        coVerify(exactly = 1) { targets.insertIgnoreAll(any()) }
        coVerify(exactly = 0) { targets.deleteTarget(any(), any()) }
    }
}
