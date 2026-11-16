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
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Just the location table, so these tests need nothing else from the app's schema. */
@Database(entities = [PhotoLocationEntity::class], version = 1, exportSchema = false)
abstract class PhotoLocationTestDatabase : RoomDatabase() {
    abstract fun photoLocationDao(): PhotoLocationDao
}

/** A sign-out takes the leaving account's photo locations and nobody else's, the guest's included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLocationSignOutTest {

    private lateinit var db: PhotoLocationTestDatabase
    private lateinit var dao: PhotoLocationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotoLocationTestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoLocationDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `only the leaving account's fixes go`() = runTest {
        dao.upsert(
            listOf(
                PhotoLocationEntity("link-1", "user-a", 47.5, 19.0),
                PhotoLocationEntity("content://media/1", "user-a", 47.6, 19.1),
                PhotoLocationEntity("link-2", "user-b", 48.0, 20.0),
                PhotoLocationEntity("content://media/2", PhotoLocationEntity.LOCAL_USER, 46.0, 18.0),
            ),
        )

        dao.deleteForUser("user-a")

        assertTrue(dao.idsForUser("user-a").isEmpty())
        assertEquals(listOf("link-2"), dao.idsForUser("user-b"))
        assertEquals(listOf("content://media/2"), dao.idsForUser(PhotoLocationEntity.LOCAL_USER))
    }
}
