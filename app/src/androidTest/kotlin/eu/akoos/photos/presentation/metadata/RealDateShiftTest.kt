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

package eu.akoos.photos.presentation.metadata

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.domain.usecase.MetadataWriteResult
import eu.akoos.photos.domain.usecase.WriteLocalPhotoMetadataUseCase
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The bulk date shift measured against REAL files. [DateShift] decides which photos move and by how
 * much, and the JVM suite pins every one of those rules; what only a device can answer is whether the
 * decision, once written through the real ExifInterface into real JPEGs, actually lands. That is the
 * whole promise of the feature: each file moves by the SAME delta from its OWN date, so the minutes
 * and hours between the shots, which are the record of the day, come back out of the files unchanged.
 *
 * The write goes through [WriteLocalPhotoMetadataUseCase.writeCaptureDate] exactly as the editor
 * drives it, one call per file with that file's own date plus the shared delta, and every assertion is
 * made on what [ExifHelper.readMetadata] reads back off the file afterwards rather than on what the
 * call was asked to do.
 *
 * Each test inserts its own three JPEGs under Pictures/ with names unique to the run, so a row left
 * behind by an earlier failure can never be mistaken for this run's fixture, and every inserted row is
 * deleted afterwards whatever the assertions did. A device that refuses the insert, the fixture stamp
 * or consent for a file this test itself created skips rather than fails, because it cannot stage the
 * scenario at all.
 */
@RunWith(AndroidJUnit4::class)
class RealDateShiftTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The real write the editor drives. It takes a Context and nothing else, so it is built here
     *  rather than injected. */
    private val writeMetadata = WriteLocalPhotoMetadataUseCase(context)

    /** Every MediaStore row this run inserted, in insertion order. */
    private val inserted = mutableListOf<Uri>()

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    /** The zone the fixture dates are written in, together with their offset tags, so the instant each
     *  file carries is pinned by the file itself and no device zone can move it. */
    private val zone = ZoneId.of("Europe/Budapest")

    @After
    fun deleteInsertedRows() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        inserted.clear()
    }

    /** One photo of the fixture: the row a write lands on, and the instant it was stamped with. */
    private data class Shot(val uri: Uri, val stampedMs: Long)

    /** A name no other run and no leftover row can hold. */
    private fun uniqueName(): String = "pfp_date_shift_${System.nanoTime()}.jpg"

    /** The bytes of a tiny real JPEG, so the fixture is a file the platform decoder and the EXIF
     *  writer both accept rather than a hand-rolled header. */
    private fun tinyJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366AA.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** [ms] with its sub-second part dropped. The EXIF datetime string holds whole seconds only, so
     *  that is the finest resolution a round trip through a file can preserve. */
    private fun wholeSeconds(ms: Long): Long = Math.floorDiv(ms, 1000L) * 1000L

    /** The epoch millis of a wall clock in [zone], truncated to the second the EXIF string can hold. */
    private fun instantIn(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        hourOfDay: Int,
        minute: Int,
        second: Int,
    ): Long = wholeSeconds(
        ZonedDateTime.of(year, month, dayOfMonth, hourOfDay, minute, second, 0, zone)
            .toInstant()
            .toEpochMilli(),
    )

    /**
     * A real JPEG in MediaStore stamped with [dateTimeOriginal] and [offsetTimeOriginal]. The tags go
     * on through a read-write file descriptor on the inserted Uri, which is the same path a metadata
     * write takes, and the row leaves pending only once the bytes and the tags are both in place so
     * the provider scans the finished file. Null when this device refuses any step, which is the
     * signal to skip.
     */
    private fun insertJpegWithExif(dateTimeOriginal: String, offsetTimeOriginal: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
                out.write(tinyJpegBytes())
                true
            }
        }.getOrNull() ?: false
        if (!bytesWritten) return null

        val tagsWritten = runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal)
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetTimeOriginal)
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

    /**
     * Three real JPEGs whose capture dates sit at deliberately UNEVEN distances: the second is 2 hours
     * after the first, the third 2 days and 5 hours after the second. Even spacing would let a write
     * that re-spaced the photos, or one that put them on a ladder of its own, pass by coincidence, so
     * the two gaps differ by more than an order of magnitude and the spread crosses a day boundary.
     * All three sit in the same summer offset, so nothing here rests on a DST crossing.
     *
     * Null when this device refuses any step, which is the signal to skip.
     */
    private fun insertOuting(): List<Shot>? {
        val first = instantIn(2018, 4, 11, 9, 15, 20)
        val captures = listOf(first, first + 2 * hour, first + 2 * day + 5 * hour)
        return captures.map { ms ->
            val uri = insertJpegWithExif(
                ExifDateFormat.toExifLocal(ms, zone),
                ExifDateFormat.toExifOffset(ms, zone),
            ) ?: return null
            Shot(uri, ms)
        }
    }

    /** The absolute instant [uri]'s own EXIF reports, resolved through the offset tag on the file and
     *  truncated to the whole second the datetime string holds. Null when the file carries no readable
     *  capture datetime. */
    private fun captureInstantOf(uri: Uri): Long? {
        val meta = ExifHelper.readMetadata(context, uri.toString())
        val dateTime = meta.dateTimeOriginal ?: return null
        return ExifDateFormat.fromExif(dateTime, meta.offsetTimeOriginal, ZoneId.systemDefault())
            ?.let(::wholeSeconds)
    }

    /** What every file of [shots] reports right now, each asserted to be readable. */
    private fun captureInstantsOf(shots: List<Shot>): List<Long> = shots.map { shot ->
        val readBack = captureInstantOf(shot.uri)
        assertNotNull("every fixture file must report a capture date", readBack)
        readBack!!
    }

    /** True when the OS demanded consent for any of [results]. A file this test created is owned by
     *  the app and should need none, so a device that asks cannot stage the write at all. */
    private fun demandedConsent(results: List<MetadataWriteResult>): Boolean =
        results.any { it is MetadataWriteResult.NeedsPermission }

    /**
     * The promise of the shift, on real files: three photos taken at uneven distances are moved by one
     * shared delta, and every one of them lands on its OWN date plus that delta with the gaps between
     * them untouched.
     *
     * The pick puts the oldest photo five hours earlier, so the delta is negative and the whole
     * selection moves back in time, nowhere near the future ceiling. The writes then run exactly as
     * the editor runs them, one call per file carrying that file's own date plus the delta, and the
     * assertions are made on what the files themselves report afterwards.
     */
    @Test
    fun three_real_photos_shift_by_one_delta_and_keep_the_gaps_between_them() {
        val outing = insertOuting()
        assumeTrue("this device refuses a MediaStore test image", outing != null)
        val shots = outing!!

        // The dates the FILES carry, which is what the editor builds its shift targets from.
        val before = captureInstantsOf(shots)
        shots.forEachIndexed { index, shot ->
            assertEquals(
                "the fixture must read back as the instant it was stamped with",
                shot.stampedMs,
                before[index],
            )
        }

        val targets = shots.mapIndexed { index, shot -> DateShift.Target(shot.uri.toString(), before[index]) }
        val span = DateShift.span(targets)
        assertNotNull("three dated files must produce a span", span)
        val pickedEarliest = span!!.earliestMs - 5 * hour
        assertTrue(
            "a pick five hours back cannot reach the future ceiling, so the clamp is not what is measured here",
            DateShift.allowsEarliest(span, pickedEarliest, System.currentTimeMillis()),
        )
        val delta = DateShift.deltaFor(span, pickedEarliest)
        assertEquals("the delta is the distance the oldest photo moves", -(5 * hour), delta)

        // The editor's own write, per file: the delta is shared, the instant is not.
        val results = targets.map { target ->
            runBlocking { writeMetadata.writeCaptureDate(target.uri, target.captureMs + delta) }
        }
        assumeTrue(
            "this device demands consent for a file the test itself created, so the write cannot be measured",
            !demandedConsent(results),
        )
        results.forEach { result ->
            assertTrue("every shifted file must be written: $result", result is MetadataWriteResult.Success)
        }

        val after = captureInstantsOf(shots)
        before.indices.forEach { index ->
            assertEquals(
                "each file must land on its own date plus the shared delta",
                wholeSeconds(before[index] + delta),
                wholeSeconds(after[index]),
            )
        }

        // The property an absolute date destroys and a shift exists to protect. Both sides come off
        // the files at whole-second resolution, so the intervals compare exactly.
        assertEquals(
            "the gap from the first shot to the second must survive the shift",
            before[1] - before[0],
            after[1] - after[0],
        )
        assertEquals(
            "the gap from the second shot to the third must survive the shift",
            before[2] - before[1],
            after[2] - after[1],
        )
    }

    /**
     * The other bulk mode on the same fixture, so the difference between them is demonstrated rather
     * than described: one absolute date written over all three files puts every one of them on that
     * single instant and leaves nothing between them. This is the behaviour a user wants when the
     * whole selection belongs to one moment, and it is the reason the shift has to exist beside it.
     */
    @Test
    fun one_absolute_date_over_the_same_three_photos_collapses_them_onto_it() {
        val outing = insertOuting()
        assumeTrue("this device refuses a MediaStore test image", outing != null)
        val shots = outing!!

        val before = captureInstantsOf(shots)
        assertEquals("the fixture must start out on three different dates", 3, before.toSet().size)

        val chosen = instantIn(2020, 6, 1, 12, 0, 0)
        val results = shots.map { shot ->
            runBlocking { writeMetadata.writeCaptureDate(shot.uri.toString(), chosen) }
        }
        assumeTrue(
            "this device demands consent for a file the test itself created, so the write cannot be measured",
            !demandedConsent(results),
        )
        results.forEach { result ->
            assertTrue("every dated file must be written: $result", result is MetadataWriteResult.Success)
        }

        val after = captureInstantsOf(shots)
        after.forEach { instant ->
            assertEquals("every file must report the one instant that was written", chosen, instant)
        }
        assertEquals(
            "an absolute date leaves no spacing at all between the photos",
            0L,
            after.max() - after.min(),
        )
    }
}
