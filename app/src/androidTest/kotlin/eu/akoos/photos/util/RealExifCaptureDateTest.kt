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

package eu.akoos.photos.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The capture-date read against a REAL file in MediaStore, written and read back through the same
 * androidx ExifInterface the app uses. The pure conversions are covered on the JVM; what only a
 * device can answer is whether the EXIF offset tag actually survives a write into a JPEG and comes
 * back out of [ExifHelper.readMetadata], because that tag is the whole difference between an exact
 * instant and a wall clock read through whatever zone the phone happens to sit in.
 *
 * Each test inserts its own JPEG under Pictures/ with a name unique to the run, so a row left behind
 * by an earlier failure can never be mistaken for this run's fixture, and every inserted row is
 * deleted afterwards whatever the assertions did. A device that refuses the insert (no permission,
 * restricted storage) skips rather than fails.
 */
@RunWith(AndroidJUnit4::class)
class RealExifCaptureDateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Every MediaStore row this run inserted, in insertion order. */
    private val inserted = mutableListOf<Uri>()

    @After
    fun deleteInsertedRows() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        inserted.clear()
    }

    /** A name no other run and no leftover row can hold. */
    private fun uniqueName(mime: String): String {
        val extension = if (mime == "image/png") "png" else "jpg"
        return "pfp_exif_capture_${System.nanoTime()}.$extension"
    }

    /** The bytes of a tiny real JPEG, so the fixture is a file the platform decoder and the EXIF
     *  writer both accept rather than a hand-rolled header. */
    private fun tinyImageBytes(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366AA.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** The MediaStore DATE_TAKEN the row reports, or null when the provider left it unset. A provider
     *  that cannot derive a date from the container leaves it null, which is the state the correction
     *  exists for. */
    private fun dateTakenOf(uri: Uri): Long? {
        val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0).takeIf { it > 0L }
            }
        }
        return null
    }

    /**
     * A real JPEG in MediaStore stamped with [dateTimeOriginal] and, when one is given,
     * [offsetTimeOriginal]. The tags go on through a read-write file descriptor on the inserted Uri,
     * which is the same path a metadata write takes, and the row leaves pending only once the bytes
     * and the tags are both in place so the provider scans the finished file. Null when this device
     * refuses any step, which is the signal to skip.
     */
    private fun insertJpegWithExif(
        dateTimeOriginal: String,
        offsetTimeOriginal: String?,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): Uri? {
        val resolver = context.contentResolver
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName(mime))
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        inserted += uri

        val bytesWritten = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(tinyImageBytes(format))
                true
            }
        }.getOrNull() ?: false
        if (!bytesWritten) return null

        val tagsWritten = runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal)
                    offsetTimeOriginal?.let {
                        setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it)
                    }
                    saveAttributes()
                }
                true
            }
        }.getOrNull() ?: false
        if (!tagsWritten) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            runCatching { resolver.update(uri, done, null, null) }
        }
        return uri
    }

    /** The row's DATE_ADDED promoted to millis, or the current time when the column is unreadable.
     *  Either way a value from today, which is what a capture date has to overrule. */
    private fun dateAddedMsOf(uri: Uri): Long {
        val projection = arrayOf(MediaStore.Images.Media.DATE_ADDED)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0) * 1000L
        }
        return System.currentTimeMillis()
    }

    /** [ms] with its sub-second part dropped. The EXIF datetime string holds whole seconds only, so
     *  that is the finest resolution a round trip through a file can preserve. */
    private fun wholeSeconds(ms: Long): Long = Math.floorDiv(ms, 1000L) * 1000L

    /** The epoch millis of a wall clock in [zone], truncated to the second the EXIF string can hold. */
    private fun instantIn(
        zone: ZoneId,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = wholeSeconds(
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli(),
    )

    /**
     * The offset tag survives a real write and is genuinely honoured on the way back. The fallback
     * zone handed to [ExifDateFormat.fromExif] is UTC, two hours away from the zone the datetime was
     * written in, so the resolved instant can only match the original when the offset on the file is
     * what resolved it. A reader that dropped the tag would land two hours late and fail here.
     */
    @Test
    fun the_offset_tag_round_trips_through_a_real_file_and_pins_the_exact_instant() {
        val zone = ZoneId.of("Europe/Budapest")
        val captured = instantIn(zone, 2021, 7, 14, 15, 42, 9)
        val uri = insertJpegWithExif(
            ExifDateFormat.toExifLocal(captured, zone),
            ExifDateFormat.toExifOffset(captured, zone),
        )
        assumeTrue("this device refuses a MediaStore test image", uri != null)

        val meta = ExifHelper.readMetadata(context, uri!!.toString())
        val readBack = meta.dateTimeOriginal
        assertNotNull("the capture datetime must survive the write", readBack)
        assertNotNull("the offset tag must survive the write", meta.offsetTimeOriginal)
        assertEquals(
            "summer in this zone is two hours ahead of UTC",
            "+02:00",
            meta.offsetTimeOriginal,
        )

        val resolved = ExifDateFormat.fromExif(readBack!!, meta.offsetTimeOriginal, ZoneId.of("UTC"))
        assertNotNull("the pair on the file must resolve to an instant", resolved)
        assertEquals(
            "the offset on the file must outrank the deliberately wrong fallback zone",
            captured,
            resolved,
        )
    }

    /**
     * A file carrying no offset tag reads back as none, and the wall clock on it can then only be
     * anchored by the device zone. Both entry points must agree on that, because the read path picks
     * between them on the strength of the very tag this fixture leaves out.
     */
    @Test
    fun a_file_with_no_offset_tag_falls_back_to_the_device_zone() {
        val zone = ZoneId.of("Europe/Budapest")
        val captured = instantIn(zone, 2021, 11, 3, 8, 5, 30)
        val written = ExifDateFormat.toExifLocal(captured, zone)
        val uri = insertJpegWithExif(written, offsetTimeOriginal = null)
        assumeTrue("this device refuses a MediaStore test image", uri != null)

        val meta = ExifHelper.readMetadata(context, uri!!.toString())
        assertEquals("the capture datetime must survive the write", written, meta.dateTimeOriginal)
        assertNull("a file stamped without an offset must report none", meta.offsetTimeOriginal)
        assertFalse(
            "an absent offset leaves the wall clock unanchored",
            ExifDateFormat.hasUsableOffset(meta.offsetTimeOriginal),
        )

        val deviceZone = ZoneId.systemDefault()
        val throughDeviceZone = ExifDateFormat.fromExifLocal(meta.dateTimeOriginal!!, deviceZone)
        assertNotNull("the datetime on the file must parse", throughDeviceZone)
        assertEquals(
            "with no offset the pair must read exactly as the bare wall clock does",
            throughDeviceZone,
            ExifDateFormat.fromExif(meta.dateTimeOriginal!!, null, deviceZone),
        )
    }

    /**
     * The end-to-end decision on a real file: a photo whose embedded date is years old sits on a row
     * added today, so the EXIF read has to produce that older instant as the correction. The second
     * call feeds the correction back in as the row's date and must find nothing left to do, which is
     * what keeps a repeated walk from rewriting the same entry.
     */
    @Test
    fun a_real_exif_date_corrects_a_row_added_today_and_then_stops() {
        val zone = ZoneId.of("Europe/Budapest")
        val captured = instantIn(zone, 2019, 5, 4, 10, 11, 12)
        val uri = insertJpegWithExif(
            ExifDateFormat.toExifLocal(captured, zone),
            ExifDateFormat.toExifOffset(captured, zone),
        )
        assumeTrue("this device refuses a MediaStore test image", uri != null)

        val meta = ExifHelper.readMetadata(context, uri!!.toString())
        val readBack = meta.dateTimeOriginal
        assertNotNull("the capture datetime must survive the write", readBack)
        val exifMs = ExifDateFormat.fromExif(readBack!!, meta.offsetTimeOriginal, ZoneId.systemDefault())
        assertEquals("the file must read back as the instant it was stamped with", captured, exifMs)

        val hasOffset = ExifDateFormat.hasUsableOffset(meta.offsetTimeOriginal)
        assertTrue("the fixture must carry an offset so the tight slack applies", hasOffset)

        // The row's added time stands in for the date column a photo shows when its embedded date
        // never reached DATE_TAKEN, which is exactly the file that reads as the day it arrived.
        val addedMs = dateAddedMsOf(uri)
        assertEquals(
            "a capture date years off the row's own date must be recorded as the correction",
            exifMs,
            CaptureDateOverride.captureDateCorrection(addedMs, exifMs, hasOffset),
        )
        assertNull(
            "a row already holding the corrected date has nothing left to correct",
            CaptureDateOverride.captureDateCorrection(exifMs!!, exifMs, hasOffset),
        )
    }

    /**
     * The fault itself, staged on a real file: the JPEG carries a capture date years old while its
     * MediaStore row is dated the moment the file landed on the phone, which is what a third-party
     * downloader leaves behind. The three steps run in the order the backfill's date leg runs them,
     * and the correction they produce has to be the embedded instant rather than the arrival one.
     * Feeding that correction back in as the row's own date must then find nothing left to do, so a
     * second walk over the same library writes nothing.
     *
     * A provider that will not take the DATE_TAKEN write, or that keeps a date of its own, cannot
     * hold the scenario at all, so the test skips there instead of reporting a failure it did not
     * measure.
     */
    @Test
    fun a_row_dated_the_day_the_photo_arrived_is_corrected_to_the_embedded_capture_date() {
        val zone = ZoneId.of("Europe/Budapest")
        val captured = instantIn(zone, 2019, 5, 4, 10, 11, 12)
        // A PNG stages the bug the way it actually happens: the provider derives DATE_TAKEN from a
        // JPEG's own EXIF, but leaves it unset for a container it will not read a date from, and the
        // listing then falls back to the moment the file arrived. Forcing the column instead would
        // prove nothing, because the provider rewrites it from the file on the next scan.
        val uri = insertJpegWithExif(
            ExifDateFormat.toExifLocal(captured, zone),
            ExifDateFormat.toExifOffset(captured, zone),
            Bitmap.CompressFormat.PNG,
        )
        assumeTrue("this device refuses a MediaStore test image", uri != null)
        assumeTrue(
            "this device does derive a capture date for this container, so the gap cannot be staged",
            dateTakenOf(uri!!) == null,
        )
        // What the listing reports for a row with no capture date: the moment the file arrived.
        val arrived = dateAddedMsOf(uri)

        val meta = ExifHelper.readMetadata(context, uri.toString())
        val exifMs = (meta.dateTimeOriginal ?: meta.dateTime)?.let {
            ExifDateFormat.fromExif(it, meta.offsetTimeOriginal, ZoneId.systemDefault())
        }
        assertEquals("the file must read back as the instant it was stamped with", captured, exifMs)

        val hasOffset = ExifDateFormat.hasUsableOffset(meta.offsetTimeOriginal)
        assertTrue("the fixture must carry an offset so the tight slack applies", hasOffset)

        val correction = CaptureDateOverride.captureDateCorrection(
            rawDateTakenMs = arrived,
            exifMs = exifMs,
            exifHasOffset = hasOffset,
        )
        assertNotNull("a row dated the day the file arrived must be corrected", correction)
        val corrected = correction!!
        assertEquals(
            "the correction must be the capture date embedded in the file",
            captured,
            wholeSeconds(corrected),
        )
        assertNull(
            "a row already reporting the corrected date has nothing left to correct",
            CaptureDateOverride.captureDateCorrection(corrected, exifMs, hasOffset),
        )
    }

    /**
     * The photo a camera writes: the row's date and the date inside the file name the same instant.
     * Nothing about it is wrong, so the same three steps have to decide there is nothing to record.
     * This is the side a date correction risks damaging, because a rule loose enough to catch the
     * arrival-dated file must still leave every healthy one exactly as it stands.
     */
    @Test
    fun a_row_whose_date_matches_the_embedded_capture_date_is_left_alone() {
        val zone = ZoneId.of("Europe/Budapest")
        val captured = instantIn(zone, 2022, 9, 18, 17, 24, 5)
        val uri = insertJpegWithExif(
            ExifDateFormat.toExifLocal(captured, zone),
            ExifDateFormat.toExifOffset(captured, zone),
        )
        assumeTrue("this device refuses a MediaStore test image", uri != null)

        // No staging here on purpose: a JPEG carrying EXIF is exactly what a camera writes, so the
        // provider derives DATE_TAKEN from the file itself and the row is already healthy.
        val dateTaken = dateTakenOf(uri!!)
        assumeTrue("this device does not derive a capture date for a JPEG", dateTaken != null)

        val meta = ExifHelper.readMetadata(context, uri.toString())
        val exifMs = (meta.dateTimeOriginal ?: meta.dateTime)?.let {
            ExifDateFormat.fromExif(it, meta.offsetTimeOriginal, ZoneId.systemDefault())
        }
        assertEquals("the file must read back as the instant it was stamped with", captured, exifMs)

        assertNull(
            "a row that already agrees with the file must be left untouched",
            CaptureDateOverride.captureDateCorrection(
                rawDateTakenMs = dateTaken!!,
                exifMs = exifMs,
                exifHasOffset = ExifDateFormat.hasUsableOffset(meta.offsetTimeOriginal),
            ),
        )
    }
}
