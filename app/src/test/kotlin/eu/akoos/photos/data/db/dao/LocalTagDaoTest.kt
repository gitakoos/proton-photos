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
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.util.UserPhotoTags
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one invariant `local_tag` exists to hold: a detection is the scanner's to rebuild, a category
 * the user picked is not, and the two share a row.
 *
 * Everything the scanner does to that row is reproduced here in the order it really does it, because
 * every one of those steps is a chance to lose a choice that nothing can recompute. A file replaced
 * in place is re-detected; a detector-version bump discards the lot. Both must leave the user column
 * standing, and a choice must be storable for a file the scanner has never reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTagDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: LocalTagDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.localTagDao()
    }

    @After
    fun tearDown() = db.close()

    /** What [eu.akoos.photos.data.repository.LocalTagScanScheduler] builds after detecting a file:
     *  the detection columns only, with the user column left at its default. */
    private fun detection(
        uri: String,
        dateModified: Long = 1_700L,
        sizeBytes: Long = 2_048L,
        tagsCsv: String = "1",
        scannedAt: Long = 9_000L,
    ) = LocalTagEntity(
        uri = uri,
        dateModified = dateModified,
        sizeBytes = sizeBytes,
        tagsCsv = tagsCsv,
        scannedAt = scannedAt,
    )

    private suspend fun row(uri: String): LocalTagEntity? = dao.getAll().firstOrNull { it.uri == uri }

    // ── the detection path never writes the user column ──────────────────────────────────────────

    @Test
    fun `a first detection lands the whole row with no user choice`() = runTest {
        dao.upsertDetection(detection(URI))

        val stored = row(URI)!!
        assertEquals(1_700L, stored.dateModified)
        assertEquals(2_048L, stored.sizeBytes)
        assertEquals("1", stored.tagsCsv)
        assertEquals(9_000L, stored.scannedAt)
        assertEquals("a file nobody categorised carries no choice", "", stored.userTagsCsv)
    }

    @Test
    fun `re-detecting a categorised file refreshes the detection and keeps the choice`() = runTest {
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, UserPhotoTags.encode(listOf(2, 8)))

        // The file was replaced in place, so the scanner sees a drifted freshness key and re-detects.
        dao.upsertDetection(detection(URI, dateModified = 1_900L, sizeBytes = 5_000L, tagsCsv = "1,4", scannedAt = 9_500L))

        val stored = row(URI)!!
        assertEquals("the new freshness key landed", 1_900L, stored.dateModified)
        assertEquals(5_000L, stored.sizeBytes)
        assertEquals("the new detection landed", "1,4", stored.tagsCsv)
        assertEquals(9_500L, stored.scannedAt)
        assertEquals("the user's choice survived the re-scan", "2,8", stored.userTagsCsv)
        assertEquals(setOf(2, 8), stored.userTags())
    }

    @Test
    fun `a detection entity carrying a user value cannot overwrite the stored choice`() = runTest {
        dao.setUserTagsCsv(URI, "3")

        // Even handed a populated user column, the detection write does not name it.
        dao.upsertDetection(detection(URI).copy(userTagsCsv = "7"))

        assertEquals("3", row(URI)!!.userTagsCsv)
    }

    // ── storing a choice for a file the scanner has not reached ──────────────────────────────────

    @Test
    fun `a choice is storable before any detection exists`() = runTest {
        // The common case right after a photo is taken: the scheduler has not got to it yet.
        dao.setUserTagsCsv(URI, "5")

        val stored = row(URI)!!
        assertEquals("5", stored.userTagsCsv)
        assertEquals("no detection is claimed", "", stored.tagsCsv)
        assertEquals("the freshness key stays unscanned", 0L, stored.dateModified)
        assertEquals(0L, stored.sizeBytes)
        assertEquals(0L, stored.scannedAt)
    }

    @Test
    fun `the shell row still reads as needing a scan, and the scan then fills it in`() = runTest {
        dao.setUserTagsCsv(URI, "5")

        // Zeros match no live MediaStore row, which is what keeps the file in the scanner's queue.
        val shell = row(URI)!!
        assertTrue("an unscanned shell can never match a real file", shell.dateModified != 1_700L)

        dao.upsertDetection(detection(URI))

        val stored = row(URI)!!
        assertEquals(1_700L, stored.dateModified)
        assertEquals("1", stored.tagsCsv)
        assertEquals("the choice that was there first is still there", "5", stored.userTagsCsv)
    }

    @Test
    fun `a later choice replaces the earlier one and leaves the detection alone`() = runTest {
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, "2,8")
        dao.setUserTagsCsv(URI, "9")

        val stored = row(URI)!!
        assertEquals("9", stored.userTagsCsv)
        assertEquals("1", stored.tagsCsv)
        assertEquals(9_000L, stored.scannedAt)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `clearing a choice empties the column without dropping the detection`() = runTest {
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, "2")
        dao.setUserTagsCsv(URI, UserPhotoTags.encode(emptyList()))

        val stored = row(URI)!!
        assertEquals("", stored.userTagsCsv)
        assertEquals("1", stored.tagsCsv)
    }

    @Test
    fun `reading a file with no row at all reports no stored value`() = runTest {
        assertNull(dao.getUserTagsCsv(URI))

        dao.setUserTagsCsv(URI, "6")
        assertEquals("6", dao.getUserTagsCsv(URI))
    }

    // ── a detector-version bump discards detections, not choices ─────────────────────────────────

    @Test
    fun `clearDetections drops uncategorised rows and keeps categorised ones re-scannable`() = runTest {
        dao.upsertDetection(detection(PLAIN_URI))
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, "2,8")

        dao.clearDetections()

        assertNull("a row holding only a detection has nothing left to keep", row(PLAIN_URI))

        val kept = row(URI)!!
        assertEquals("the choice is not the detector's to discard", "2,8", kept.userTagsCsv)
        assertEquals("its detection is gone", "", kept.tagsCsv)
        assertEquals("and its freshness key is zeroed, so the next scan re-detects it", 0L, kept.dateModified)
        assertEquals(0L, kept.sizeBytes)
        assertEquals(0L, kept.scannedAt)
    }

    @Test
    fun `a kept row takes its new detection normally afterwards`() = runTest {
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, "2,8")
        dao.clearDetections()

        dao.upsertDetection(detection(URI, tagsCsv = "4", scannedAt = 9_900L))

        val stored = row(URI)!!
        assertEquals("4", stored.tagsCsv)
        assertEquals(9_900L, stored.scannedAt)
        assertEquals("2,8", stored.userTagsCsv)
    }

    @Test
    fun `clearDetections on a table of choices alone deletes nothing`() = runTest {
        dao.setUserTagsCsv(URI, "1")
        dao.setUserTagsCsv(PLAIN_URI, "2")

        dao.clearDetections()

        assertEquals(2, dao.getAll().size)
    }

    // ── pruning a file that left MediaStore ──────────────────────────────────────────────────────

    @Test
    fun `a deleted file takes its row and its choice with it`() = runTest {
        dao.upsertDetection(detection(URI))
        dao.setUserTagsCsv(URI, "2")

        // The file itself is gone, so there is nothing left for the choice to describe.
        dao.deleteByUris(listOf(URI))

        assertNull(row(URI))
    }

    private companion object {
        const val URI = "content://media/external/images/media/1000012591"
        const val PLAIN_URI = "content://media/external/images/media/1000012592"
    }
}
