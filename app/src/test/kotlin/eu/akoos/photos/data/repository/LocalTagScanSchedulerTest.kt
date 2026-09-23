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

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.PerfDiagnostics
import eu.akoos.photos.util.PhotoTagDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheduler against a real table. A scan's plan trusts the cache rows the scan read, except
 * where a detector change has wiped the table since or the read came back empty, and a photo stored
 * while a scan ran is not queued again by that scan.
 *
 * Detection runs on the scheduler's own worker threads, so each test waits for the row or the phase
 * marker it expects rather than for a fixed time. Whether a plan queued anything is settled by the
 * time [LocalTagScanScheduler.schedule] returns, which is what lets a test assert that nothing was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTagScanSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: TestDatabase
    private lateinit var dao: LocalTagDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.localTagDao()
        PerfDiagnostics.clear()
    }

    @After
    fun tearDown() {
        db.close()
        PerfDiagnostics.clear()
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("local_tag_cache", Context.MODE_PRIVATE)

    private fun storeDetectorVersion(version: Int) {
        prefs.edit().putInt("detector_version", version).commit()
    }

    /** A small screenshot: its tags come from its name and type alone, so detecting it reads no file. */
    private fun screenshot(id: Int) = LocalMediaItem(
        uri = "content://media/external/images/media/$id",
        dateTaken = 1_000_000L - id,
        displayName = "Screenshot_$id.png",
        mimeType = "image/png",
        sizeBytes = 1_000L + id,
        bucketName = "Screenshots",
        dateModified = 1_700L + id,
    )

    private fun detection(item: LocalMediaItem, tagsCsv: String, scannedAt: Long) = LocalTagEntity(
        uri = item.uri,
        dateModified = item.dateModified,
        sizeBytes = item.sizeBytes,
        tagsCsv = tagsCsv,
        scannedAt = scannedAt,
    )

    private suspend fun row(uri: String): LocalTagEntity? = dao.getAll().firstOrNull { it.uri == uri }

    private suspend fun awaitRow(uri: String, until: (LocalTagEntity) -> Boolean): LocalTagEntity =
        withTimeout(TIMEOUT_MS) {
            var found = row(uri)?.takeIf(until)
            while (found == null) {
                delay(POLL_MS)
                found = row(uri)?.takeIf(until)
            }
            found
        }

    private fun marks(label: String): Int = PerfDiagnostics.dump().lines().count { "$label " in it }

    private suspend fun awaitMark(label: String) {
        withTimeout(TIMEOUT_MS) { while (marks(label) == 0) delay(POLL_MS) }
    }

    @Test
    fun `a detector change re-tags a photo the scan read as fresh`() {
        runBlocking {
            storeDetectorVersion(PhotoTagDetector.DETECTOR_VERSION - 1)
            val photo = screenshot(1)
            // Stored under the old detector and fresh by its key: only the wipe says it is out of date.
            val old = detection(photo, tagsCsv = "", scannedAt = 5L)
            dao.upsertDetection(old)

            LocalTagScanScheduler(context, dao).schedule(listOf(photo), mapOf(photo.uri to old))

            assertEquals("1", awaitRow(photo.uri) { it.scannedAt > 5L }.tagsCsv)
            assertEquals(PhotoTagDetector.DETECTOR_VERSION, prefs.getInt("detector_version", 0))
        }
    }

    @Test
    fun `a scan that read the cache before a photo was stored does not queue it again`() {
        runBlocking {
            storeDetectorVersion(PhotoTagDetector.DETECTOR_VERSION)
            val tagged = screenshot(1)
            val untagged = screenshot(2)
            val stored = detection(tagged, tagsCsv = "1", scannedAt = 5L)
            dao.upsertDetection(stored)
            // What two back-to-back scans both read: the first photo's row, nothing yet for the second.
            val read = mapOf(tagged.uri to stored)
            val scheduler = LocalTagScanScheduler(context, dao)

            scheduler.schedule(listOf(tagged, untagged), read)
            awaitMark("tagscan:end")
            scheduler.schedule(listOf(tagged, untagged), read)

            assertEquals("only the first scan queues a batch", 1, marks("tagscan:start"))
            assertEquals("1", row(untagged.uri)?.tagsCsv)
            assertEquals("a fresh row is not tagged again", 5L, row(tagged.uri)?.scannedAt)
        }
    }

    @Test
    fun `an empty cache map is read again rather than trusted`() {
        runBlocking {
            storeDetectorVersion(PhotoTagDetector.DETECTOR_VERSION)
            val photo = screenshot(1)
            dao.upsertDetection(detection(photo, tagsCsv = "1", scannedAt = 5L))

            // An empty map is also what a scan hands over when its own read of the table failed.
            LocalTagScanScheduler(context, dao).schedule(listOf(photo), emptyMap())

            assertEquals("the table says the photo is tagged", 0, marks("tagscan:start"))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
