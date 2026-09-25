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

package eu.akoos.photos.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.SyncStateEntity
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins what an album's "photos uploading" line counts: the queued pairs whose device file the upload
 * pass will still upload or join, for the signed-in account only. A pair nothing will ever finish
 * must not keep the line up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadAlbumTargetDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var targets: UploadAlbumTargetDao
    private lateinit var sync: SyncStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        targets = db.uploadAlbumTargetDao()
        sync = db.syncStateDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun row(
        uri: String,
        status: SyncStatus,
        cloudFileId: String? = null,
        queued: Boolean = false,
        userId: String = USER,
    ) {
        sync.upsert(
            SyncStateEntity(
                localUri = uri,
                userId = userId,
                cloudFileId = cloudFileId,
                localHash = "",
                cloudHash = null,
                status = status,
                lastSyncAttemptMs = 0L,
                lastSyncSuccessMs = null,
                backedUpAtMs = null,
                sizeBytes = 1024L,
            ),
        )
        if (queued) sync.markQueued(uri, QueueSource.ALBUM_ADD, 1L)
    }

    private suspend fun pending(album: String = ALBUM): Int = targets.observePendingCount(album, USER).first()

    @Test
    fun `counts photos queued, uploading and uploaded but not yet joined`() = runTest {
        row("uri://queued", SyncStatus.LOCAL_ONLY, queued = true)
        row("uri://uploading", SyncStatus.UPLOADING)
        row("uri://uploaded", SyncStatus.SYNCED, cloudFileId = "cloud-1")
        listOf("uri://queued", "uri://uploading", "uri://uploaded").forEach { targets.insertIgnore(it, ALBUM) }

        assertEquals(3, pending())
    }

    @Test
    fun `leaves out pairs nothing will finish and those of another album or account`() = runTest {
        row("uri://vaulted", SyncStatus.HIDDEN, cloudFileId = "cloud-2")
        row("uri://dequeued", SyncStatus.LOCAL_ONLY, queued = false)
        row("uri://other-account", SyncStatus.LOCAL_ONLY, queued = true, userId = "user-2")
        row("uri://other-album", SyncStatus.LOCAL_ONLY, queued = true)
        targets.insertIgnore("uri://vaulted", ALBUM)
        targets.insertIgnore("uri://dequeued", ALBUM)
        targets.insertIgnore("uri://other-account", ALBUM)
        // A pair whose device file and row are both gone.
        targets.insertIgnore("uri://no-row", ALBUM)
        targets.insertIgnore("uri://other-album", "album-2")

        assertEquals(0, pending())
        assertEquals(1, pending("album-2"))
    }

    @Test
    fun `counts one fewer once a joined pair is dropped`() = runTest {
        row("uri://a", SyncStatus.SYNCED, cloudFileId = "cloud-a")
        row("uri://b", SyncStatus.LOCAL_ONLY, queued = true)
        targets.insertIgnore("uri://a", ALBUM)
        targets.insertIgnore("uri://b", ALBUM)
        assertEquals(2, pending())

        targets.deleteTarget("uri://a", ALBUM)

        assertEquals(1, pending())
    }

    private companion object {
        const val USER = "user-1"
        const val ALBUM = "album-1"
    }
}
