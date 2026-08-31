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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The share strip proven end to end on a real device: a genuine geotagged JPEG is written to disk,
 * run through [stripForShare], and the FileProvider content Uri it hands back is opened and read the
 * same way a receiving app would. A plain JVM test cannot stand in for this. The strip writes real
 * EXIF into a real container through androidx [ExifInterface] and wraps the result in a real
 * [ShareFileProvider] grant, and both only behave on a device.
 *
 * The property that matters most for privacy is the negative one: the source is only ever read, so
 * every test re-reads the ORIGINAL after the strip and asserts it still carries what it started with.
 * A strip that ever mutated its input would leave the user's own on-disk photo altered, which the
 * copy-to-temp design exists to prevent.
 *
 * Fixtures live in the cache and are deleted afterwards, and the stripped copies the provider serves
 * out of `cacheDir/fullres` are swept on the way out, so a run leaves nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class ShareStripperInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A location well inside Hungary, so a present fix is unmistakable and an absent one is null. */
    private val budapestLat = 47.4979
    private val budapestLon = 19.0402

    /** Every source fixture this run wrote, so it can be removed whatever the assertions did. */
    private val created = mutableListOf<File>()

    @After
    fun cleanup() {
        created.forEach { runCatching { it.delete() } }
        created.clear()
        // The copies handed out through the share provider land here; drop this run's temps too.
        File(context.cacheDir, "fullres").listFiles()?.forEach { f ->
            if (f.name.startsWith("stripped_")) runCatching { f.delete() }
        }
    }

    /**
     * A tiny real JPEG on disk, optionally geotagged and stamped with a camera [make] / [model]. The
     * bytes come from an actual [Bitmap] compress so the EXIF writer and the platform decoder both
     * accept the file rather than a hand-rolled header.
     */
    private fun buildJpeg(
        name: String,
        lat: Double? = budapestLat,
        lon: Double? = budapestLon,
        make: String? = null,
        model: String? = null,
    ): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366AA.toInt())
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        created += file

        val exif = ExifInterface(file.absolutePath)
        if (lat != null && lon != null) exif.setLatLong(lat, lon)
        make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
        model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
        exif.saveAttributes()
        return file
    }

    /** The EXIF of the share copy the provider Uri points at, read the way a receiver would open it. */
    private fun exifOfShared(uri: Uri): ExifInterface {
        val stream = context.contentResolver.openInputStream(uri)
        assertNotNull("the share Uri must be readable", stream)
        return stream!!.use { ExifInterface(it) }
    }

    /**
     * The headline guarantee: sharing with the location strip on hands back a copy with no GPS, and
     * the original file is left exactly as it was. The source is re-read at the end so a strip that
     * ever touched its input would fail here rather than pass silently.
     */
    @Test
    fun stripForShare_removesGps_butLeavesOriginalIntact() {
        val source = buildJpeg("share_geo_${System.nanoTime()}.jpg", make = "TestCam")
        // Sanity: the fixture really carries a location before the strip, so the removal is not vacuous.
        assertNotNull("the source fixture must carry GPS before the strip", ExifInterface(source.absolutePath).latLong)

        val shared = stripForShare(
            context,
            Uri.fromFile(source).toString(),
            "image/jpeg",
            "geo.jpg",
            MetadataStripConfig(stripGps = true),
        )
        assertNotNull("an image strip must hand back a share copy", shared)

        assertNull("the shared copy must carry no location", exifOfShared(shared!!).latLong)

        assertNotNull(
            "the original must keep its location: the strip works on a copy, never the source",
            ExifInterface(source.absolutePath).latLong,
        )
    }

    /**
     * The per-field config honoured on a real file: keeping GPS while stripping the camera group must
     * leave the copy's location intact and drop only the make / model. The image path nulls the chosen
     * tags directly on the copied bytes, so this is a firm assertion rather than a flaky one.
     */
    @Test
    fun stripForShare_perFieldConfig_keepsGpsButStripsCamera() {
        val source = buildJpeg("share_perfield_${System.nanoTime()}.jpg", make = "TestCam", model = "Model X")
        assertNotNull("the source fixture must carry GPS", ExifInterface(source.absolutePath).latLong)
        assertEquals(
            "the source fixture must carry a camera make",
            "TestCam",
            ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_MAKE),
        )

        val shared = stripForShare(
            context,
            Uri.fromFile(source).toString(),
            "image/jpeg",
            "perfield.jpg",
            MetadataStripConfig(stripGps = false, stripCameraInfo = true),
        )
        assertNotNull("an image strip must hand back a share copy", shared)

        val copyExif = exifOfShared(shared!!)
        assertNotNull("location must survive when only the camera group is stripped", copyExif.latLong)
        assertNull("the camera make must be gone", copyExif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull("the camera model must be gone", copyExif.getAttribute(ExifInterface.TAG_MODEL))

        val srcExif = ExifInterface(source.absolutePath)
        assertNotNull("the original must keep its location", srcExif.latLong)
        assertEquals(
            "the original must keep its camera make",
            "TestCam",
            srcExif.getAttribute(ExifInterface.TAG_MAKE),
        )
    }

    /**
     * An unsupported type returns null so the caller shares the original bytes untouched. The config
     * asks to remove GPS, so a null here can only come from the type being unsupported and never from
     * a no-op strip request.
     */
    @Test
    fun stripForShare_unsupported_returnsNull() {
        val txt = File(context.cacheDir, "share_notes_${System.nanoTime()}.txt").apply {
            writeText("not an image")
        }
        created += txt

        val shared = stripForShare(
            context,
            Uri.fromFile(txt).toString(),
            "text/plain",
            "notes.txt",
            MetadataStripConfig(stripGps = true),
        )
        assertNull("an unsupported type must return null so the caller shares the original", shared)
    }
}
