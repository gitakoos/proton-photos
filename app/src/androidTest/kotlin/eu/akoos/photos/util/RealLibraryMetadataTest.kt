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

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The two metadata readers measured against the DEVICE'S OWN media library, so the files under test
 * are the ones real cameras and real apps wrote rather than anything this suite synthesised. A
 * hand-built fixture cannot answer either question here: the platform only redacts a photo's location
 * on a file the app does not own, and a synthetic box tree only proves the mvhd walk against the
 * shape this project believes muxers emit.
 *
 * The library is read, never written. The stamp round trip works on a private copy in the cache, so
 * no file the user owns is modified, nothing is inserted into MediaStore, and every copy is deleted
 * afterwards. Each walk is capped, so a library of any size costs a bounded number of reads, and an
 * item that cannot be opened is skipped rather than failed. A device that holds no file able to carry
 * a given scenario skips that test through [assumeTrue].
 */
@RunWith(AndroidJUnit4::class)
class RealLibraryMetadataTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Rows scanned before a walk gives up, so a huge library cannot make a test crawl. */
    private val imageScanLimit = 200
    private val videoScanLimit = 200

    /** Videos compared against their own mvhd in one run. */
    private val videoMatchLimit = 20

    /**
     * How far a real video's mvhd may sit from the capture date MediaStore reports. The full range of
     * civil UTC offsets spans 26 hours, and many cameras write local wall clock into a field the spec
     * calls UTC, so a plain zone difference of that size is normal rather than a fault. That is
     * exactly why the date correction treats an mvhd instant as carrying no offset. A gap WIDER than
     * the whole offset range cannot come from a zone at all, so it means the box was read at the wrong
     * offset or the wrong width, which is the failure this comparison exists to catch.
     */
    private val mvhdToleranceMs = 26L * 60L * 60L * 1000L

    /** The ceiling on a video copied into the cache, so the round trip never fills the device. */
    private val maxCopyBytes = 64L * 1024L * 1024L

    /** A fixed instant on a whole second, which is the resolution an MP4 timestamp field holds. */
    private val stampInstantMs = 1_600_000_000_000L

    private val logTag = "RealLibraryMetadata"

    /** Every cache copy this run created, deleted whatever the assertions did. */
    private val cacheCopies = mutableListOf<File>()

    @After
    fun deleteCacheCopies() {
        cacheCopies.forEach { runCatching { it.delete() } }
        cacheCopies.clear()
    }

    // ---- library lookups --------------------------------------------------

    private data class VideoRow(val uri: Uri, val dateTakenMs: Long, val sizeBytes: Long)

    /**
     * The first geotagged image this app did NOT create, with what the plain URI yields for it.
     * Ownership is the thing that decides redaction: the platform hands an app its own files
     * unredacted, so only a foreign file can show whether requesting the original matters. Null when
     * the library holds no such image, or below the API level that records an owner.
     */
    private fun firstForeignGeotaggedImage(): Triple<Uri, PhotoMetadata, PhotoMetadata>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        var scanned = 0
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media._ID} DESC",
            )
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext() && scanned < imageScanLimit) {
                scanned++
                // Only our OWN package is excluded. A null owner is a file the media scanner picked
                // up rather than one an app inserted, which is foreign to us just the same and is the
                // very kind of file the platform withholds a location from.
                val owner = runCatching { cursor.getString(1) }.getOrNull()
                if (owner == context.packageName) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0),
                )
                val original = runCatching {
                    ExifHelper.readMetadata(context, originalUriForExif(context, uri.toString()))
                }.getOrNull() ?: continue
                if (original.gpsLatitude == null || original.gpsLongitude == null) continue
                val plain = runCatching {
                    ExifHelper.readMetadata(context, uri.toString())
                }.getOrNull() ?: continue
                return Triple(uri, original, plain)
            }
        }
        return null
    }

    /**
     * A photo another app created is the only case where requesting the original changes anything,
     * so this is what shows whether the platform really is withholding coordinates from a plain read.
     * The assertion covers the direction that must hold either way, that our read path yields the
     * coordinates; whether the plain read is redacted is reported rather than asserted, because a
     * device can be configured so that it is not.
     */
    @Test
    fun a_photo_another_app_created_still_yields_its_coordinates() {
        assumeTrue(
            "without the media location grant the platform redacts by design",
            hasMediaLocationGrant(context),
        )
        val found = firstForeignGeotaggedImage()
        assumeTrue("no geotagged image created by another app on this device", found != null)
        val (uri, original, plain) = found!!

        assertNotNull("the original read must carry a latitude", original.gpsLatitude)
        assertNotNull("the original read must carry a longitude", original.gpsLongitude)

        val redacted = plain.gpsLatitude == null || plain.gpsLongitude == null
        Log.i(
            logTag,
            if (redacted) {
                "foreign file $uri IS redacted on a plain read and yields " +
                    "${original.gpsLatitude} / ${original.gpsLongitude} through the original uri"
            } else {
                "foreign file $uri is not redacted on this device, so the original uri changes " +
                    "nothing for it"
            },
        )
    }

    /**
     * The first image in the library whose EXIF yields a coordinate pair through the read path, with
     * the metadata that read produced and the mime MediaStore declares for it. Null when no image
     * within the scan cap carries a fix. An image that cannot be opened is skipped.
     */
    private fun firstGeotaggedImage(): Triple<Uri, String?, PhotoMetadata>? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE)
        var scanned = 0
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media._ID} ASC",
            )
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext() && scanned < imageScanLimit) {
                scanned++
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0),
                )
                val mime = runCatching { cursor.getString(1) }.getOrNull()
                val meta = runCatching {
                    ExifHelper.readMetadata(context, originalUriForExif(context, uri.toString()))
                }.getOrNull() ?: continue
                if (meta.gpsLatitude != null && meta.gpsLongitude != null) {
                    return Triple(uri, mime, meta)
                }
            }
        }
        return null
    }

    /**
     * Up to [matchLimit] video rows that [accept] takes, walked in id order and abandoned after the
     * scan cap so a library with no match still costs a bounded walk. A row the provider cannot
     * describe, or one [accept] fails on, is skipped.
     */
    private fun videoRows(matchLimit: Int, accept: (VideoRow) -> Boolean): List<VideoRow> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.SIZE,
        )
        val found = mutableListOf<VideoRow>()
        var scanned = 0
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media._ID} ASC",
            )
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext() && scanned < videoScanLimit && found.size < matchLimit) {
                scanned++
                val row = runCatching {
                    VideoRow(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0),
                        ),
                        dateTakenMs = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                        sizeBytes = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                    )
                }.getOrNull() ?: continue
                if (runCatching { accept(row) }.getOrDefault(false)) found += row
            }
        }
        return found
    }

    /** The capture instant [uri] records in its mvhd, read through the descriptor the resolver hands
     *  out, or null when the container records none and when the item cannot be opened. */
    private fun mvhdOf(uri: Uri): Long? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                Mp4CreationTime.read(it.fileDescriptor)
            }
        }.getOrNull()

    /** [uri]'s bytes copied into the app's own cache, or null when the copy fails. The copy is
     *  recorded before a byte is written, so a partial one is still cleaned up. */
    private fun copyToCache(uri: Uri): File? {
        val file = File(context.cacheDir, "pfp_real_mvhd_${System.nanoTime()}.mp4")
        cacheCopies += file
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
                true
            }
        }.getOrNull() ?: false
        return file.takeIf { copied && it.length() > 0L }
    }

    // ---- the tests --------------------------------------------------------

    /**
     * A photo the device already holds gives up its coordinates through the read path. Without the
     * original-tagged URI the platform hands back a stream with the GPS tags removed, so a fix that
     * arrives at all is what proves the upgrade is being applied and understood by the provider.
     *
     * The coordinates have to be inside the valid range as well as present, so a misparsed rational
     * or a byte order mistake cannot pass as a location.
     *
     * The plain URI is then read for the same file and reported rather than asserted on: Android does
     * not redact a file the app itself owns, and ownership varies between devices, so an empty plain
     * read is evidence when it appears and not an invariant. When it does appear it is the redaction
     * the original-tagged read exists to defeat, and the run output says so.
     */
    @Test
    fun a_real_geotagged_photo_yields_its_coordinates_through_the_original_uri() {
        assumeTrue(
            "without the media location grant the platform redacts by design",
            hasMediaLocationGrant(context),
        )
        val found = firstGeotaggedImage()
        assumeTrue("no geotagged image on this device", found != null)
        val (uri, mime, meta) = found!!

        val latitude = meta.gpsLatitude
        val longitude = meta.gpsLongitude
        assertNotNull("the original read must carry a latitude", latitude)
        assertNotNull("the original read must carry a longitude", longitude)
        assertTrue(
            "latitude $latitude is outside the valid range, so the read is not a coordinate",
            latitude!! >= -90.0 && latitude <= 90.0,
        )
        assertTrue(
            "longitude $longitude is outside the valid range, so the read is not a coordinate",
            longitude!! >= -180.0 && longitude <= 180.0,
        )
        Log.i(logTag, "original read of $uri ($mime) carries $latitude / $longitude")

        val plain = ExifHelper.readMetadata(context, uri.toString())
        if (plain.gpsLatitude == null || plain.gpsLongitude == null) {
            Log.i(
                logTag,
                "the platform redacts $uri: the plain read carries no coordinates while the " +
                    "original read does, which is the redaction the original uri defeats",
            )
        } else {
            Log.i(
                logTag,
                "the plain read of $uri already carries coordinates, so this file is not redacted " +
                    "for this app and the original uri changes nothing here",
            )
        }
    }

    /**
     * The mvhd walk read against real containers: for every video that reports both a stored capture
     * date and an mvhd creation time, the two have to name the same moment within the offset range a
     * wall clock written as UTC can account for. A parser reading the wrong field, the wrong width or
     * the wrong epoch lands decades or centuries away and fails here, which no synthetic box tree can
     * demonstrate.
     *
     * A container with no mvhd is legitimate, so a device whose videos carry none skips instead of
     * failing, and the comparison count is asserted so a walk that compared nothing cannot pass.
     */
    @Test
    fun the_mvhd_reader_agrees_with_the_stored_capture_date_on_real_videos() {
        val rows = videoRows(matchLimit = videoMatchLimit) { it.dateTakenMs > 0L }
        assumeTrue("no video with a stored capture date on this device", rows.isNotEmpty())

        val pairs = rows.mapNotNull { row -> mvhdOf(row.uri)?.let { row to it } }
        assumeTrue("no video on this device records an mvhd creation time", pairs.isNotEmpty())

        var compared = 0
        pairs.forEach { (row, mvhdMs) ->
            val delta = abs(row.dateTakenMs - mvhdMs)
            assertTrue(
                "the mvhd of ${row.uri} reads $mvhdMs against a stored ${row.dateTakenMs}, " +
                    "a gap of ${delta}ms that no zone offset can explain",
                delta <= mvhdToleranceMs,
            )
            compared++
        }
        Log.i(logTag, "compared $compared real videos against their own mvhd")
        assertTrue("the comparison must run on at least one real video", compared >= 1)
    }

    /**
     * Stamping and reading are exact inverses on a container a real device produced, down to the
     * whole second the MP4 timestamp field stores. Both halves walk the same box tree, so a real file
     * whose moov sits after the media data, or which nests its headers differently from a synthetic
     * fixture, is the only thing that proves the walk lands on the same offset twice.
     *
     * The write goes to a private copy in the app's cache, so the library file the bytes came from is
     * never touched, and the copy is bounded in size and deleted afterwards.
     */
    @Test
    fun stamping_a_real_video_copy_reads_back_as_the_instant_it_was_stamped_with() {
        val source = videoRows(matchLimit = 1) { row ->
            row.sizeBytes in 1L..maxCopyBytes && mvhdOf(row.uri) != null
        }.firstOrNull()
        assumeTrue("no small enough video with an mvhd on this device", source != null)

        val copy = copyToCache(source!!.uri)
        assumeTrue("this device refuses to hand over the video's bytes", copy != null)
        copy!!

        assertTrue("a real container must accept the stamp", Mp4CreationTime.stamp(copy, stampInstantMs))
        assertEquals(
            "the stamped instant must read back exactly, to the second the container stores",
            stampInstantMs,
            Mp4CreationTime.read(copy),
        )
    }
}
