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

package eu.akoos.photos.data.importer

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFileDateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun temp(suffix: String, bytes: ByteArray) =
        File.createTempFile("file_date_", suffix, context.cacheDir).apply { writeBytes(bytes) }

    /** A moov holding one version-0 mvhd whose creation time is [epochSeconds]. */
    private fun movie(epochSeconds: Long): ByteArray {
        val mvhd = ByteBuffer.allocate(108).apply {
            putInt(108)
            put("mvhd".toByteArray(Charsets.US_ASCII))
            putInt(0)
            putInt((epochSeconds + 2_082_844_800L).toInt())
            putInt((epochSeconds + 2_082_844_800L).toInt())
        }.array()
        return ByteBuffer.allocate(8 + mvhd.size).apply {
            putInt(8 + mvhd.size)
            put("moov".toByteArray(Charsets.US_ASCII))
            put(mvhd)
        }.array()
    }

    @Test
    fun `a video is dated by its mvhd`() {
        val seconds = 1_700_000_000L
        assertEquals(seconds * 1000L, ImportFileDate.read(context, temp(".mp4", movie(seconds)), "video/mp4"))
    }

    @Test
    fun `an image is dated by its EXIF`() {
        val jpeg = temp(".jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2021:06:15 12:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            saveAttributes()
        }
        assertEquals(
            Instant.parse("2021-06-15T10:00:00Z").toEpochMilli(),
            ImportFileDate.read(context, jpeg, "image/jpeg"),
        )
    }

    @Test
    fun `bytes that record no date give none`() {
        val junk = "not a real file".toByteArray()
        assertNull(ImportFileDate.read(context, temp(".jpg", junk), "image/jpeg"))
        assertNull(ImportFileDate.read(context, temp(".mp4", junk), "video/mp4"))
        assertNull(ImportFileDate.read(context, temp(".bin", movie(1_700_000_000L)), "application/octet-stream"))
    }
}
