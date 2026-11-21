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
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Which blocks the map's GPS walk reads for a cloud photo, and the fix it finds in them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentEdgeGpsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cameraXAttr = """{"Common":{},"Camera":{"Orientation":1}}"""
    private val commonOnlyXAttr = """{"Common":{"Size":10}}"""

    @Test
    fun `an mp4 or mov is read at both ends`() {
        assertEquals(ContentGpsRead.FIRST_AND_LAST, ContentEdgeGps.plan("video/mp4", hasXAttr = true, xAttrJson = commonOnlyXAttr))
        assertEquals(ContentGpsRead.FIRST_AND_LAST, ContentEdgeGps.plan("video/quicktime", hasXAttr = false, xAttrJson = null))
    }

    @Test
    fun `a video in a container the reader does not know is not read`() {
        assertEquals(ContentGpsRead.NONE, ContentEdgeGps.plan("video/webm", hasXAttr = false, xAttrJson = null))
    }

    @Test
    fun `an image with no XAttr is read from its first block`() {
        assertEquals(ContentGpsRead.FIRST, ContentEdgeGps.plan("image/jpeg", hasXAttr = false, xAttrJson = null))
    }

    @Test
    fun `an image whose XAttr has no Camera block is read from its first block`() {
        assertEquals(ContentGpsRead.FIRST, ContentEdgeGps.plan("image/jpeg", hasXAttr = true, xAttrJson = commonOnlyXAttr))
    }

    @Test
    fun `an image whose uploader read its EXIF is not read again`() {
        assertEquals(ContentGpsRead.NONE, ContentEdgeGps.plan("image/jpeg", hasXAttr = true, xAttrJson = cameraXAttr))
    }

    @Test
    fun `an image whose XAttr could not be decrypted is not read`() {
        assertEquals(ContentGpsRead.NONE, ContentEdgeGps.plan("image/heic", hasXAttr = true, xAttrJson = null))
    }

    @Test
    fun `the GPS in the EXIF at the start of an image is found`() {
        val file = File.createTempFile("edge_", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply { setLatLong(38.9067, 1.4206); saveAttributes() }

        val fix = ContentEdgeGps.read("image/jpeg", file.readBytes(), null)

        assertEquals(38.9067, fix?.first ?: 0.0, 1e-4)
        assertEquals(1.4206, fix?.second ?: 0.0, 1e-4)
    }

    @Test
    fun `bytes that are not an image read as no fix`() {
        assertNull(ContentEdgeGps.read("image/jpeg", ByteArray(64) { 1 }, null))
    }
}
