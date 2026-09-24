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

import android.database.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Every read of the device's media names only columns its collection has on the Android version it
 * runs on. The provider refuses a whole query over one column it does not know, so a read built for
 * Android 10 and run on Android 8 or 9 finds no photo at all. From Android 10 the columns and filters
 * stay exactly what they are, and the row reader copes with a cursor that lacks a column.
 */
class MediaStoreQueryColumnsTest {

    private val beforeQ = listOf(26, 28)
    private val fromQ = listOf(29, 30, 34)

    /** The whole-device scan's columns from Android 10, in order. */
    private val scanColumns = listOf(
        "_id", "datetaken", "date_added", "date_modified", "_display_name", "mime_type", "_size",
        "bucket_display_name", "is_pending", "width", "height", "duration",
    )

    /** The one-item and one-folder columns from Android 10, in order. */
    private val itemColumns = listOf(
        "_id", "datetaken", "date_added", "_display_name", "mime_type", "_size",
        "bucket_display_name", "width", "height", "duration",
    )

    /** What the Android 8 and 9 images table holds, of the columns these reads name. */
    private val imagesTableBeforeQ = setOf(
        "_id", "datetaken", "date_added", "date_modified", "_display_name", "mime_type", "_size",
        "bucket_display_name", "width", "height",
    )

    /** The Android 8 and 9 video table: the same columns, and a duration. */
    private val videoTableBeforeQ = imagesTableBeforeQ + "duration"

    // ── the whole-device scan ───────────────────────────────────────────────────────────────────

    @Test
    fun `from Android 10 the scan reads the same columns from both collections`() {
        for (sdk in fromQ) {
            assertEquals("images, API $sdk", scanColumns, scanProjection(sdk, isVideo = false).toList())
            assertEquals("video, API $sdk", scanColumns, scanProjection(sdk, isVideo = true).toList())
        }
    }

    @Test
    fun `below Android 10 the image scan names neither the pending flag nor a duration`() {
        for (sdk in beforeQ) {
            assertEquals(
                "API $sdk",
                scanColumns - "is_pending" - "duration",
                scanProjection(sdk, isVideo = false).toList(),
            )
        }
    }

    @Test
    fun `below Android 10 the video scan keeps the duration and leaves out the pending flag`() {
        for (sdk in beforeQ) {
            assertEquals("API $sdk", scanColumns - "is_pending", scanProjection(sdk, isVideo = true).toList())
        }
    }

    @Test
    fun `the scan filters on the pending flag from Android 10 and reads every row below it`() {
        for (sdk in fromQ) assertEquals("API $sdk", "is_pending <= 1", scanSelection(sdk))
        for (sdk in beforeQ) assertNull("API $sdk", scanSelection(sdk))
    }

    // ── one item, one folder ────────────────────────────────────────────────────────────────────

    @Test
    fun `from Android 10 an item or folder read takes the same columns from both collections`() {
        for (sdk in fromQ) {
            assertEquals("images, API $sdk", itemColumns, itemProjection(sdk, isVideo = false).toList())
            assertEquals("video, API $sdk", itemColumns, itemProjection(sdk, isVideo = true).toList())
        }
    }

    @Test
    fun `below Android 10 only the video item or folder read asks for a duration`() {
        for (sdk in beforeQ) {
            assertEquals("images, API $sdk", itemColumns - "duration", itemProjection(sdk, isVideo = false).toList())
            assertEquals("video, API $sdk", itemColumns, itemProjection(sdk, isVideo = true).toList())
        }
    }

    @Test
    fun `a folder read binds the folder alone and holds to finished rows only from Android 10`() {
        for (sdk in fromQ) {
            assertEquals("API $sdk", "bucket_display_name = ? AND is_pending = 0", bucketSelection(sdk))
        }
        for (sdk in beforeQ) assertEquals("API $sdk", "bucket_display_name = ?", bucketSelection(sdk))
        // The caller binds exactly one argument, the folder name, on every version.
        for (sdk in beforeQ + fromQ) assertEquals("API $sdk", 1, bucketSelection(sdk).count { it == '?' })
    }

    @Test
    fun `below Android 10 every column a read names is one its table has`() {
        for (sdk in beforeQ) {
            for ((isVideo, table) in listOf(false to imagesTableBeforeQ, true to videoTableBeforeQ)) {
                val named = scanProjection(sdk, isVideo).toList() + itemProjection(sdk, isVideo).toList()
                assertTrue("API $sdk, video=$isVideo: ${named - table}", table.containsAll(named))
            }
        }
    }

    // ── reading a row ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a row without a duration, size, folder, dimension or modified time reads them as empty`() {
        val item = oneRow(
            "_id" to 7L,
            "datetaken" to 1_600_000_000_000L,
            "date_added" to 1_600_000_100L,
            "_display_name" to "IMG_0001.jpg",
            "mime_type" to "image/jpeg",
        ).readMediaRow(uri = "content://media/external/images/media/7")

        assertEquals("content://media/external/images/media/7", item.uri)
        assertEquals(1_600_000_000_000L, item.dateTaken)
        assertTrue(item.dateTakenIsExplicit)
        assertEquals("IMG_0001.jpg", item.displayName)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(0L, item.duration)
        assertEquals(0L, item.sizeBytes)
        assertEquals(0L, item.dateModified)
        assertNull(item.bucketName)
        assertEquals(0, item.width)
        assertEquals(0, item.height)
        assertNull(item.dateExpiresSec)
    }

    @Test
    fun `a row without its capture date or type is dated by its added time and typed by its name`() {
        val item = oneRow("_id" to 7L, "date_added" to 1_600_000_100L, "_display_name" to "clip.mp4")
            .readMediaRow(uri = "content://media/external/video/media/7")

        assertEquals(1_600_000_100_000L, item.dateTaken)
        assertFalse(item.dateTakenIsExplicit)
        assertEquals("video/mp4", item.mimeType)
    }

    @Test
    fun `a row holding nothing but its id still reads, as an undated nameless file`() {
        val item = oneRow("_id" to 7L).readMediaRow(uri = "content://media/external/images/media/7")

        assertEquals(0L, item.dateTaken)
        assertEquals("", item.displayName)
        assertEquals("", item.mimeType)
        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun `a full video row reads every column it carries`() {
        val item = oneRow(
            "_id" to 9L,
            "datetaken" to 1_600_000_000_000L,
            "date_added" to 1_600_000_100L,
            "date_modified" to 1_600_000_200L,
            "_display_name" to "VID_0001.mp4",
            "mime_type" to "video/mp4",
            "_size" to 4_096L,
            "bucket_display_name" to "Camera",
            "is_pending" to 0,
            "width" to 1920,
            "height" to 1080,
            "duration" to 12_345L,
        ).readMediaRow(uri = "content://media/external/video/media/9")

        assertEquals(1_600_000_000_000L, item.dateTaken)
        assertEquals(1_600_000_200L, item.dateModified)
        assertEquals("VID_0001.mp4", item.displayName)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(4_096L, item.sizeBytes)
        assertEquals("Camera", item.bucketName)
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
        assertEquals(12_345L, item.duration)
        assertNull(item.dateExpiresSec)
    }

    /**
     * A cursor on one row holding exactly [columns], as a provider answers a projection it trimmed.
     * Only the lookups and reads the row reader makes are answered; anything else fails the test.
     */
    private fun oneRow(vararg columns: Pair<String, Any?>): Cursor {
        val names = columns.map { it.first }
        val values = columns.map { it.second }
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> names.indexOf(args!![0] as String)
                "getLong" -> (values[args!![0] as Int] as Number?)?.toLong() ?: 0L
                "getInt" -> (values[args!![0] as Int] as Number?)?.toInt() ?: 0
                "getString" -> values[args!![0] as Int]?.toString()
                else -> throw UnsupportedOperationException("Cursor.${method.name}")
            }
        } as Cursor
    }
}
