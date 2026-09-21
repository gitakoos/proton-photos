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
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the delete has to do for a place edit to reach the map: the row a photo's fix lives in is both
 * what the map plots and what makes the backfill skip that file, so the two effects that matter are
 * that the deleted photo stops being plotted AND stops being skipped, while everything else is left
 * exactly as it stood. The account scope is checked too: the table is keyed by id alone, so an
 * unscoped delete would reach across accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLocationDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoLocationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoLocationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun fix(id: String, userId: String = USER, lat: Double = 47.4979, lng: Double = 19.0402) =
        PhotoLocationEntity(id = id, userId = userId, latitude = lat, longitude = lng)

    @Test
    fun `a deleted fix stops being plotted and stops being skipped`() = runTest {
        dao.upsert(listOf(fix(URI_A), fix(URI_B)))

        dao.deleteByIds(USER, listOf(URI_A))

        assertNull("nothing reads the old coordinates back", dao.getById(USER, URI_A))
        assertEquals("the map plots only what is left", listOf(URI_B), dao.observeForUser(USER).first().map { it.id })
        assertEquals("the backfill sees the file as unlocated again", listOf(URI_B), dao.idsForUser(USER))
    }

    @Test
    fun `the photos not named keep their fix`() = runTest {
        dao.upsert(listOf(fix(URI_A), fix(URI_B), fix(URI_C)))

        dao.deleteByIds(USER, listOf(URI_B))

        assertEquals(setOf(URI_A, URI_C), dao.idsForUser(USER).toSet())
        assertEquals(47.4979, dao.getById(USER, URI_A)!!.latitude, 0.0)
    }

    @Test
    fun `a batch drops every id it names in one statement`() = runTest {
        dao.upsert(listOf(fix(URI_A), fix(URI_B), fix(URI_C)))

        dao.deleteByIds(USER, listOf(URI_A, URI_C))

        assertEquals(listOf(URI_B), dao.idsForUser(USER))
    }

    @Test
    fun `a delete from another account leaves the row standing`() = runTest {
        // id is this table's primary key on its own, so a row belongs to whichever account wrote it
        // last. The user scope is what keeps one account's edit off a row that is not its own.
        dao.upsert(listOf(fix(URI_A)))

        dao.deleteByIds(OTHER_USER, listOf(URI_A))

        assertEquals(listOf(URI_A), dao.idsForUser(USER))
    }

    @Test
    fun `an id with no row deletes nothing`() = runTest {
        dao.upsert(listOf(fix(URI_A)))

        dao.deleteByIds(USER, listOf(URI_B))

        assertEquals(listOf(URI_A), dao.idsForUser(USER))
    }

    @Test
    fun `a re-derived fix lands on the same id after the delete`() = runTest {
        // The whole point of the invalidation: the next read stores the file's new coordinates.
        dao.upsert(listOf(fix(URI_A)))
        dao.deleteByIds(USER, listOf(URI_A))

        dao.upsert(listOf(fix(URI_A, lat = 51.5072, lng = -0.1276)))

        val stored = dao.getById(USER, URI_A)!!
        assertEquals(51.5072, stored.latitude, 0.0)
        assertEquals(-0.1276, stored.longitude, 0.0)
        assertEquals(1, dao.idsForUser(USER).size)
    }

    @Test
    fun `a select-all sized batch clears through the host variable cap`() = runTest {
        // More ids than one statement may bind, which is why the caller slices the list.
        val ids = List(1_200) { "$URI_A$it" }
        dao.upsert(ids.map { fix(it) })

        ids.forEachSqlChunk { dao.deleteByIds(USER, it) }

        assertEquals(emptyList<String>(), dao.idsForUser(USER))
    }

    private companion object {
        const val USER = "user-1"
        const val OTHER_USER = "user-2"
        const val URI_A = "content://media/external/images/media/1000012591"
        const val URI_B = "content://media/external/images/media/1000012592"
        const val URI_C = "content://media/external/images/media/1000012593"
    }
}
