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

package eu.akoos.photos.presentation.gallery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gallery wakes the upload worker after a refresh only when a photo is queued to upload. The worker
 * shows its "Checking for new photos" notification as it starts, so a wake with nothing queued flashed it
 * each time the gallery was entered again. Queued means the same set the badge and the upload processor
 * read: a LOCAL_ONLY row with an upload intent, for this account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GallerySyncWakeTest {

    private val user = UserId("user1")
    private lateinit var db: AppDatabase
    private lateinit var repo: SyncStateRepositoryImpl
    private var wakes = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SyncStateRepositoryImpl(db.syncStateDao())
        wakes = 0
    }

    @After
    fun tearDown() = db.close()

    private suspend fun row(uri: String, status: SyncStatus, owner: UserId = user) = repo.upsert(
        SyncState(
            localUri = uri,
            cloudFileId = if (status == SyncStatus.SYNCED) "cloud-$uri" else null,
            localHash = "hash-$uri",
            cloudHash = null,
            status = status,
            lastSyncAttemptMs = 0L,
            lastSyncSuccessMs = null,
            backedUpAtMs = null,
            sizeBytes = 1_024L,
        ),
        owner,
    )

    private suspend fun wake() = wakeSyncIfUploadQueued(repo, user) { wakes++ }

    @Test
    fun `nothing queued leaves the worker asleep`() = runTest {
        assertFalse(wake())
        assertEquals(0, wakes)
    }

    @Test
    fun `a device photo with no upload intent does not wake it`() = runTest {
        row("content://1", SyncStatus.LOCAL_ONLY)
        row("content://2", SyncStatus.SYNCED)
        repo.markQueued("content://2", QueueSource.MANUAL, 1L)

        assertFalse(wake())
        assertEquals(0, wakes)
    }

    @Test
    fun `a queued photo of another account does not wake it`() = runTest {
        row("content://other", SyncStatus.LOCAL_ONLY, owner = UserId("user2"))
        repo.markQueued("content://other", QueueSource.MANUAL, 1L)

        assertFalse(wake())
    }

    @Test
    fun `a queued device photo wakes it once`() = runTest {
        row("content://1", SyncStatus.LOCAL_ONLY)
        repo.markQueued("content://1", QueueSource.AUTO_FOLDER, 1L)

        assertTrue(wake())
        assertEquals(1, wakes)
    }
}
