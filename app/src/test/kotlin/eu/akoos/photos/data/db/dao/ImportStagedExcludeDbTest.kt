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
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Excluding a large selection from an import review: the names are bound in slices, so a selection of any
 * size stays under the SQL host-variable limit, and a Live Photo's video follows its still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportStagedExcludeDbTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ImportStagedDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.importStagedDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(name: String, liveOf: String? = null) =
        ImportStagedEntity(zipId = ZIP, entryName = name, sizeBytes = 1, stagedAt = 1L, liveOf = liveOf)

    @Test
    fun `excluding 1200 stills excludes their videos too, and restoring brings all back`() = runBlocking {
        val stills = (0 until 1200).map { "Photos/IMG_$it.jpg" }
        dao.upsert(stills.map { row(it) } + stills.map { row(it.replace(".jpg", ".mp4"), liveOf = it) })

        dao.setExcluded(ZIP, stills, true)
        assertTrue(dao.pendingUpload(ZIP).isEmpty())

        dao.setExcluded(ZIP, stills, false)
        assertEquals(2400, dao.pendingUpload(ZIP).size)
    }

    @Test
    fun `a selection past every SQLite variable limit still applies`() = runBlocking {
        val names = (0 until 16_400).map { "Photos/IMG_$it.jpg" }
        dao.upsert(names.map { row(it) } + row("Photos/kept.jpg"))

        dao.setExcluded(ZIP, names, true)

        assertEquals(listOf("Photos/kept.jpg"), dao.pendingUpload(ZIP).map { it.entryName })
    }

    private companion object {
        const val ZIP = "zip-1"
    }
}
