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

package eu.akoos.photos.widget

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A device photo on the home screen widget stands the way it was taken: a portrait photo stored as landscape
 * pixels with an EXIF rotation is turned upright, and a photo with no rotation is drawn as stored.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetBitmapOrientationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun landscapeJpeg(name: String, orientation: Int?): File {
        val file = folder.newFile(name)
        val pixels = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { pixels.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Test
    fun `a portrait photo stored sideways is drawn upright`() {
        val file = landscapeJpeg("portrait.jpg", ExifInterface.ORIENTATION_ROTATE_90)

        val bitmap = PhotoWidgetUpdater.loadScaledBitmap(context, Uri.fromFile(file).toString())

        assertNotNull(bitmap)
        assertEquals(40, bitmap!!.width)
        assertEquals(80, bitmap.height)
    }

    @Test
    fun `a photo turned upside down keeps its shape`() {
        val file = landscapeJpeg("upside.jpg", ExifInterface.ORIENTATION_ROTATE_180)

        val bitmap = PhotoWidgetUpdater.loadScaledBitmap(context, Uri.fromFile(file).toString())

        assertEquals(80, bitmap!!.width)
        assertEquals(40, bitmap.height)
    }

    @Test
    fun `a photo with no rotation is drawn as stored`() {
        val file = landscapeJpeg("plain.jpg", orientation = null)

        val bitmap = PhotoWidgetUpdater.loadScaledBitmap(context, Uri.fromFile(file).toString())

        assertEquals(80, bitmap!!.width)
        assertEquals(40, bitmap.height)
    }

    @Test
    fun `turning a decode frees the sideways copy`() {
        val sideways = Bitmap.createBitmap(80, 40, Bitmap.Config.RGB_565)

        val upright = PhotoWidgetUpdater.uprightBitmap(sideways, ExifInterface.ORIENTATION_ROTATE_270)

        assertEquals(40, upright.width)
        assertTrue(sideways.isRecycled)
        assertFalse(upright.isRecycled)
    }

    @Test
    fun `an unrotated decode is kept as it is`() {
        val decoded = Bitmap.createBitmap(80, 40, Bitmap.Config.RGB_565)

        val upright = PhotoWidgetUpdater.uprightBitmap(decoded, ExifInterface.ORIENTATION_NORMAL)

        assertSame(decoded, upright)
        assertFalse(decoded.isRecycled)
    }
}
