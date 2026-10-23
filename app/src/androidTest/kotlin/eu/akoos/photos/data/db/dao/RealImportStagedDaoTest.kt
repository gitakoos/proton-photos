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

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import review's Live Photo queries on the device's own SQLite, which is a different build from the
 * one the JVM suite runs on: large exclusions, the still a pending video keeps, and the counts the review
 * and the resume read.
 */
@RunWith(AndroidJUnit4::class)
class RealImportStagedDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ImportStagedDao

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.importStagedDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(name: String, liveOf: String? = null, uploaded: Boolean = false, linkId: String? = null) =
        ImportStagedEntity(
            zipId = ZIP, entryName = name, sizeBytes = 1, stagedAt = 1L,
            liveOf = liveOf, uploaded = uploaded, linkId = linkId,
        )

    @Test
    fun excludingSixteenThousandStillsTakesTheirVideosAlong() = runBlocking {
        val stills = (0 until 16_400).map { "Photos/IMG_$it.heic" }
        dao.upsert(stills.map { row(it) } + stills.map { row(it.replace(".heic", ".mov"), liveOf = it) } + row("Photos/kept.jpg"))

        dao.setExcluded(ZIP, stills, true)
        assertEquals(listOf("Photos/kept.jpg"), dao.pendingUpload(ZIP).map { it.entryName })

        dao.setExcluded(ZIP, stills, false)
        assertEquals(32_801, dao.pendingUpload(ZIP).size)
    }

    @Test
    fun anUploadedStillStaysWhileItsVideoIsPendingAndGoesOnceItIsNot() = runBlocking {
        dao.upsert(
            listOf(
                row("A/live.heic", uploaded = true, linkId = "still-link"),
                row("A/live.mov", liveOf = "A/live.heic"),
                row("A/plain.jpg", uploaded = true, linkId = "plain-link"),
            ),
        )

        dao.clearResolved(ZIP)
        assertEquals(setOf("A/live.heic", "A/live.mov"), dao.allForZip(ZIP).map { it.entryName }.toSet())
        assertEquals(1, dao.observeIncludedCount(ZIP).first())
        assertTrue(ZIP in dao.zipsWithPending())

        dao.markUploadedWithLink(ZIP, "A/live.mov", "video-link")
        dao.clearResolved(ZIP)
        assertTrue(dao.allForZip(ZIP).isEmpty())
    }

    @Test
    fun aVideoWhoseStillRowIsGoneIsListedAndCounted() = runBlocking {
        dao.upsert(listOf(row("A/orphan.mov", liveOf = "A/gone.heic"), row("B/live.heic"), row("B/live.mov", liveOf = "B/live.heic")))

        val listed = dao.observeForZip(ZIP).first().map { it.entryName }.toSet()
        assertEquals(setOf("A/orphan.mov", "B/live.heic"), listed)
        assertEquals(2, dao.observeIncludedCount(ZIP).first())
    }

    @Test
    fun anExcludedVideoDoesNotKeepItsStill() = runBlocking {
        dao.upsert(listOf(row("A/live.heic", uploaded = true, linkId = "l"), row("A/live.mov", liveOf = "A/live.heic")))
        dao.setExcludedByName(ZIP, listOf("A/live.mov"), true)

        dao.clearResolved(ZIP)
        assertTrue(dao.allForZip(ZIP).isEmpty())
    }

    private companion object {
        const val ZIP = "content://test/zip"
    }
}
