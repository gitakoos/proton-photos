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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.domain.entity.Album
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The album cache every "Add to album" picker reads ([CloudAlbumDao.observeOwned]) follows an album created,
 * renamed or deleted in the app at once, without waiting for the Albums tab to be refreshed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatedAlbumCacheTest {

    private lateinit var db: TestDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun pickerNames(): List<String> = db.cloudAlbumDao().observeOwned().first().map { it.name }

    @Test
    fun `a created album is in the picker's list at once and listed first`() = runTest {
        db.cloudAlbumDao().upsertAll(
            listOf(CloudAlbumEntity(linkId = "old", name = "Trip", photoCount = 3, lastActivityTimeMs = 1_000L)),
        )
        val created = Album(linkId = "new", name = "Holiday", photoCount = 0, coverLinkId = null, lastActivityTimeMs = null)

        db.cloudAlbumDao().upsertAll(listOf(createdAlbumRow(created, nowMs = 5_000L)))

        assertEquals(listOf("Holiday", "Trip"), pickerNames())
    }

    @Test
    fun `a created album keeps an activity time it already carries`() {
        val created = Album(linkId = "new", name = "Holiday", photoCount = 0, coverLinkId = null, lastActivityTimeMs = 42L)

        assertEquals(42L, createdAlbumRow(created, nowMs = 5_000L).lastActivityTimeMs)
    }

    @Test
    fun `a renamed or deleted album changes the picker's list at once`() = runTest {
        db.cloudAlbumDao().upsertAll(
            listOf(
                CloudAlbumEntity(linkId = "a", name = "Trip", photoCount = 1, lastActivityTimeMs = 2_000L),
                CloudAlbumEntity(linkId = "b", name = "Pets", photoCount = 1, lastActivityTimeMs = 1_000L),
            ),
        )

        db.cloudAlbumDao().rename("a", "Road trip")
        assertEquals(listOf("Road trip", "Pets"), pickerNames())

        AlbumCacheCleanup(db.photoListingDao(), db.cloudAlbumDao(), db.albumPhotoMembershipDao())
            .dropCachedAlbum(UserId("user1"), "b")
        assertEquals(listOf("Road trip"), pickerNames())
    }
}
