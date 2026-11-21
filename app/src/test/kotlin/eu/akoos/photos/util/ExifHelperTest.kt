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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Coverage for [ExifHelper.stripToTempFile] (the upload-time metadata strip) and
 * [ExifHelper.readMetadata]. The androidx `ExifInterface` is a self-contained Java decoder/encoder
 * with no native dependency, so it runs under Robolectric on a real on-disk JPEG.
 *
 * The strategy: write a 1×1 baseline JPEG, tag it with the five field GROUPS (GPS / camera /
 * timestamp / software / authorship), strip a chosen subset into a temp file via the production
 * helper, then read the temp back and assert ONLY the configured groups were removed and every other
 * group survived. A `file://` URI is used so Robolectric's ContentResolver opens the bytes for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifHelperTest {

    private lateinit var context: Context
    private lateinit var sourceFile: File

    /**
     * A minimal valid baseline JPEG (1×1 white). ExifInterface refuses to write attributes onto a
     * non-JPEG, so a real JPEG body is required as the carrier for the EXIF segment.
     */
    private val jpegBytes: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43,
        0x00,
        0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
        0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
        0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
        0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        0x07, 0x08, 0x09, 0x0A, 0x0B,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02,
        0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11,
        0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81.toByte(),
        0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52,
        0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,
        0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A,
        0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
        0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(),
        0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(),
        0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(),
        0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(),
        0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(),
        0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(),
        0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(),
        0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(),
        0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(),
        0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
        0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(),
        0xFA.toByte(),
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xBF.toByte(),
        0x80.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte(),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceFile = File.createTempFile("exif_src_", ".jpg", context.cacheDir)
        sourceFile.writeBytes(jpegBytes)
        // Tag all five field groups so each strip subset has something to remove AND something to keep.
        val exif = ExifInterface(sourceFile.absolutePath)
        // GPS group
        exif.setLatLong(47.4979, 19.0402) // Budapest
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "100/1")
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
        // Camera group
        exif.setAttribute(ExifInterface.TAG_MAKE, "TestMake")
        exif.setAttribute(ExifInterface.TAG_MODEL, "TestModel")
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
        // Timestamp group
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2023:01:15 10:30:00")
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2023:01:15 10:30:00")
        // Software group
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "TestSoftware")
        // Authorship group
        exif.setAttribute(ExifInterface.TAG_ARTIST, "TestArtist")
        exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "TestCopyright")
        exif.saveAttributes()
    }

    private fun sourceUri(): String = Uri.fromFile(sourceFile).toString()

    private fun readBack(file: File) = ExifInterface(file.absolutePath)

    // ─── precondition: the source carries every tag group ─────────────────────

    @Test
    fun `source jpeg has all five field groups before stripping`() {
        val meta = ExifHelper.readMetadata(context, sourceUri())
        assertNotNull("GPS lat should be readable", meta.gpsLatitude)
        assertEquals("TestModel", meta.model)
        assertEquals("2023:01:15 10:30:00", meta.dateTimeOriginal)
        assertEquals("TestSoftware", meta.software)
        assertEquals("TestArtist", meta.artist)
        assertEquals("TestCopyright", meta.copyright)
    }

    // ─── strip GPS only ───────────────────────────────────────────────────────

    @Test
    fun `stripping GPS removes location but keeps camera, timestamp and software`() {
        val out = ExifHelper.stripToTempFile(
            context, sourceUri(), MetadataStripConfig(stripGps = true),
        )
        assertNotNull("expected a temp file when something is stripped", out)
        val exif = readBack(out!!)
        assertNull("GPS latlong wiped", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        // Other groups untouched.
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2023:01:15 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("TestSoftware", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("TestArtist", exif.getAttribute(ExifInterface.TAG_ARTIST))
        out.delete()
    }

    // ─── strip camera only ────────────────────────────────────────────────────

    @Test
    fun `stripping camera info removes make and model but keeps the rest`() {
        val out = ExifHelper.stripToTempFile(
            context, sourceUri(), MetadataStripConfig(stripCameraInfo = true),
        )!!
        val exif = readBack(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
        // GPS + timestamp + software + authorship survive.
        assertNotNull(exif.latLong)
        assertEquals("2023:01:15 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("TestSoftware", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("TestArtist", exif.getAttribute(ExifInterface.TAG_ARTIST))
        out.delete()
    }

    // ─── strip timestamp only ─────────────────────────────────────────────────

    @Test
    fun `stripping timestamp removes the datetime tags but keeps the rest`() {
        val out = ExifHelper.stripToTempFile(
            context, sourceUri(), MetadataStripConfig(stripTimestamp = true),
        )!!
        val exif = readBack(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull(exif.latLong)
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("TestSoftware", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("TestArtist", exif.getAttribute(ExifInterface.TAG_ARTIST))
        out.delete()
    }

    // ─── strip software only ──────────────────────────────────────────────────

    /**
     * The software flag owns the editing-tool tags ONLY. Artist and copyright hang off their own
     * flag, so a caller that removes the tool a photo passed through leaves the credit on it: the
     * name survives unless the caller asks for it to go.
     */
    @Test
    fun `stripping software info removes software but leaves artist and copyright`() {
        val out = ExifHelper.stripToTempFile(
            context, sourceUri(), MetadataStripConfig(stripSoftwareInfo = true),
        )!!
        val exif = readBack(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("TestArtist", exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertEquals("TestCopyright", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
        assertNotNull(exif.latLong)
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2023:01:15 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        out.delete()
    }

    // ─── strip authorship only ────────────────────────────────────────────────

    /**
     * The mirror image: the authorship flag alone takes artist and copyright and nothing else, so
     * the tool that wrote the file (and every other group) stays readable.
     */
    @Test
    fun `stripping authorship removes artist and copyright but keeps the rest`() {
        val out = ExifHelper.stripToTempFile(
            context, sourceUri(), MetadataStripConfig(stripAuthorship = true),
        )!!
        val exif = readBack(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
        assertEquals("TestSoftware", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertNotNull(exif.latLong)
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2023:01:15 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        out.delete()
    }

    /**
     * The two flags together remove exactly what the pair covers, which is the field set the upload
     * path asks for: it maps its one software preference onto both flags.
     */
    @Test
    fun `stripping software and authorship together removes software, artist and copyright`() {
        val out = ExifHelper.stripToTempFile(
            context,
            sourceUri(),
            MetadataStripConfig(stripSoftwareInfo = true, stripAuthorship = true),
        )!!
        val exif = readBack(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
        assertNotNull(exif.latLong)
        assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
        out.delete()
    }

    // ─── strip everything ─────────────────────────────────────────────────────

    @Test
    fun `stripping all groups removes every tagged field`() {
        val out = ExifHelper.stripToTempFile(
            context,
            sourceUri(),
            MetadataStripConfig(
                stripGps = true,
                stripCameraInfo = true,
                stripTimestamp = true,
                stripSoftwareInfo = true,
                stripAuthorship = true,
            ),
        )!!
        val exif = readBack(out)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
        out.delete()
    }

    // ─── no-op short-circuit ──────────────────────────────────────────────────

    @Test
    fun `a no-op config returns null without producing a temp file`() {
        // isNoOp short-circuits before any copy — nothing to strip means no temp.
        assertNull(ExifHelper.stripToTempFile(context, sourceUri(), MetadataStripConfig()))
    }

    @Test
    fun `MetadataStripConfig isNoOp reflects whether any group is selected`() {
        assertTrue(MetadataStripConfig().isNoOp)
        assertTrue(!MetadataStripConfig(stripGps = true).isNoOp)
        assertTrue(!MetadataStripConfig(stripSoftwareInfo = true).isNoOp)
        assertTrue(!MetadataStripConfig(stripAuthorship = true).isNoOp)
    }

    // ─── in-place strip ends the file where the stripped image ends ──────────

    @Test
    fun `an in-place strip that shrinks the EXIF leaves no old bytes after the image`() {
        val all = MetadataStripConfig(
            stripGps = true,
            stripCameraInfo = true,
            stripTimestamp = true,
            stripSoftwareInfo = true,
            stripAuthorship = true,
        )
        val expected = ExifHelper.stripToTempFile(context, sourceUri(), all)!!
        val expectedBytes = expected.readBytes()
        expected.delete()
        val before = sourceFile.length()
        val tempsBefore = strippedTempCount()

        val result = ExifHelper.stripFieldsInPlace(context, sourceUri(), all, announce = false)

        assertEquals(StripResult.Stripped, result)
        val after = sourceFile.readBytes()
        assertTrue("the strip should shrink the file", after.size < before)
        assertTrue("the file must equal a fresh strip", expectedBytes.contentEquals(after))
        assertEquals(0xFF.toByte(), after[after.size - 2])
        assertEquals(0xD9.toByte(), after[after.size - 1])
        assertNull(readBack(sourceFile).latLong)
        assertEquals("no stripped temp is left behind", tempsBefore, strippedTempCount())
    }

    // ─── temp cleanup when the EXIF write fails after the copy ────────────────

    @Test
    fun `a strip that fails after creating the temp leaves no stripped temp behind`() {
        // Bytes that copy fine but are NOT a writable JPEG: ExifInterface.saveAttributes() throws
        // for them, exercising the post-create failure branch. A `.jpg` URI suffix means the temp
        // is created (so the only way the file is gone afterwards is the catch deleting it).
        val garbage = File.createTempFile("exif_bad_", ".jpg", context.cacheDir)
        garbage.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
        val before = strippedTempCount()

        val out = ExifHelper.stripToTempFile(
            context, Uri.fromFile(garbage).toString(), MetadataStripConfig(stripGps = true),
        )

        assertNull("a failed strip returns null", out)
        assertEquals("the temp created during the failed strip must be cleaned up", before, strippedTempCount())
        garbage.delete()
    }

    private fun strippedTempCount(): Int =
        context.cacheDir.listFiles { f -> f.name.startsWith("stripped_") }?.size ?: 0

    // ─── readMetadata on an unreadable source ─────────────────────────────────

    @Test
    fun `readMetadata returns empty metadata for a missing source`() {
        val meta = ExifHelper.readMetadata(context, Uri.fromFile(File(context.cacheDir, "nope.jpg")).toString())
        // Graceful empty rather than a throw — every field null.
        assertNull(meta.model)
        assertNull(meta.gpsLatitude)
        assertNull(meta.dateTimeOriginal)
    }

    // ─── an in-place strip of a gallery photo keeps its capture date ──────────

    private val galleryUri = "content://media/external/images/media/35"

    /** Serves [sourceFile] as gallery row 35, its DATE_MODIFIED kept in step with the file as a scan would. */
    private fun installGalleryRow(dateTakenMs: Long, refuseWrites: Boolean = false) {
        GalleryRow.file = sourceFile
        GalleryRow.dateTakenMs = dateTakenMs
        GalleryRow.refuseWrites = refuseWrites
        GalleryRow.opened.clear()
        Robolectric.setupContentProvider(GalleryRow::class.java, MediaStore.AUTHORITY)
        runBlocking { context.settingsDataStore.edit { it.remove(SettingsKeys.DOWNLOAD_DATE_OVERRIDES) } }
    }

    private fun overrides() = runBlocking {
        CaptureDateOverride.parse(context.settingsDataStore.data.first()[SettingsKeys.DOWNLOAD_DATE_OVERRIDES])
    }

    @Test
    fun `a strip that keeps the date holds the photo at its capture date after the rescan`() {
        // DateTimeOriginal with no offset, the file last changed on the day it was taken.
        val captureMs = 1_794_312_000_000L
        sourceFile.setLastModified(captureMs)
        installGalleryRow(dateTakenMs = captureMs)

        val result = ExifHelper.stripFieldsInPlace(
            context, galleryUri, MetadataStripConfig(stripGps = true, stripCameraInfo = true), announce = false,
        )

        assertEquals(StripResult.Stripped, result)
        val entry = overrides()[galleryUri]
        assertEquals("the capture date the row reported is recorded", captureMs, entry?.captureMs)
        assertEquals("stamped with the modified time the strip left", sourceFile.lastModified() / 1000L, entry?.modifiedSeconds)
        // The rescan empties the column of an offsetless date a day away from the new modified time; the
        // row then reads the recorded date instead of the day the file was added.
        val addedSec = 1_794_916_800L
        assertEquals(
            captureMs,
            CaptureDateOverride.resolveDateTaken(0L, entry?.captureMs, addedSec, entry!!.modifiedSeconds!!),
        )
    }

    @Test
    fun `a strip that removes the date records no date for the photo`() {
        installGalleryRow(dateTakenMs = 1_794_312_000_000L)

        val result = ExifHelper.stripFieldsInPlace(
            context, galleryUri, MetadataStripConfig(stripGps = true, stripTimestamp = true), announce = false,
        )

        assertEquals(StripResult.Stripped, result)
        assertNull(overrides()[galleryUri])
    }

    @Test
    fun `a refused gallery write leaves the file and its date alone after one write attempt`() {
        installGalleryRow(dateTakenMs = 1_794_312_000_000L, refuseWrites = true)
        val bytes = sourceFile.readBytes()
        val tempsBefore = strippedTempCount()

        val result = ExifHelper.stripFieldsInPlace(context, galleryUri, MetadataStripConfig(stripGps = true), announce = false)

        assertEquals(StripResult.Failed, result)
        assertEquals("only the write itself opens the file for writing", 1, GalleryRow.opened.count { 'w' in it })
        assertTrue(sourceFile.readBytes().contentEquals(bytes))
        assertEquals(tempsBefore, strippedTempCount())
        assertNull(overrides()[galleryUri])
    }

    @Test
    fun `a strip opens the gallery file for writing once, for the write`() {
        // A write-mode descriptor closed unwritten still sets off a rescan, which could drop the date.
        installGalleryRow(dateTakenMs = 1_794_312_000_000L)

        ExifHelper.stripFieldsInPlace(context, galleryUri, MetadataStripConfig(stripGps = true), announce = false)

        assertEquals(listOf("rw"), GalleryRow.opened.filter { 'w' in it })
    }

    /** One gallery row backed by a test file. */
    class GalleryRow : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            return MatrixCursor(cols).apply {
                addRow(
                    cols.map<String, Any> { col ->
                        when (col) {
                            MediaStore.MediaColumns.DATE_TAKEN -> dateTakenMs
                            MediaStore.MediaColumns.DATE_MODIFIED -> file.lastModified() / 1000L
                            MediaStore.MediaColumns.SIZE -> file.length()
                            else -> 0L
                        }
                    }.toTypedArray(),
                )
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened += mode
            if (refuseWrites && mode.contains('w')) throw SecurityException("no access to $uri")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null

        companion object {
            lateinit var file: File
            var dateTakenMs = 0L
            var refuseWrites = false
            val opened = mutableListOf<String>()
        }
    }
}
