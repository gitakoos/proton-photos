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

package eu.akoos.photos.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.VideoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A video upload carries the source's place in its encrypted attributes unless GPS is stripped, and still no
 * Camera block; an image whose unredacted original is refused still builds its attributes from the plain read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadXAttrLocationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()
    private val builder = UploadXAttrBuilder(context, readVideoLocation = { uri ->
        asked += uri
        VideoLocation(38.9067, 1.4206)
    })

    private val video = LocalMediaItem(
        uri = "content://media/external/video/media/7",
        dateTaken = 1_700_000_000_000L,
        displayName = "clip.mp4",
        mimeType = "video/mp4",
        sizeBytes = 10L,
        bucketName = null,
        width = 1920,
        height = 1080,
    )

    @Test
    fun `a video carries the place its container records`() {
        val xAttr = builder.buildXAttrMetadata(video.uri, video, stripOnUpload = false, stripConfig = MetadataStripConfig())

        assertEquals(38.9067, xAttr.latitude ?: 0.0, 1e-9)
        assertEquals(1.4206, xAttr.longitude ?: 0.0, 1e-9)
    }

    @Test
    fun `a video keeps sending no Camera block`() {
        val xAttr = builder.buildXAttrMetadata(video.uri, video, stripOnUpload = false, stripConfig = MetadataStripConfig())

        assertNull(xAttr.cameraOrientation)
        assertNull(xAttr.cameraDevice)
        assertNull(xAttr.cameraCaptureTimeIso)
    }

    @Test
    fun `a video uploaded with GPS stripped carries no place and its location is not read`() {
        val xAttr = builder.buildXAttrMetadata(video.uri, video, stripOnUpload = true, stripConfig = MetadataStripConfig(stripGps = true))

        assertNull(xAttr.latitude)
        assertNull(xAttr.longitude)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an image whose unredacted original is refused builds its attributes from the plain read`() {
        val file = File.createTempFile("upload_", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setLatLong(38.9067, 1.4206)
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val plainUri = Uri.fromFile(file).toString()
        val refusedOriginal = UploadXAttrBuilder(context, readVideoLocation = { null }, exifReadUri = { "content://media/refused/1" })
        val image = video.copy(uri = plainUri, displayName = "a.jpg", mimeType = "image/jpeg", width = 8, height = 8)

        val xAttr = refusedOriginal.buildXAttrMetadata(plainUri, image, stripOnUpload = false, stripConfig = MetadataStripConfig())

        assertEquals(38.9067, xAttr.latitude ?: 0.0, 1e-4)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, xAttr.cameraOrientation)
    }

    @Test
    fun `a video whose location cannot be read still uploads without a place`() {
        val failing = UploadXAttrBuilder(context, readVideoLocation = { error("unreadable") })

        val xAttr = failing.buildXAttrMetadata(video.uri, video, stripOnUpload = false, stripConfig = MetadataStripConfig())

        assertNull(xAttr.latitude)
        assertEquals(1920, xAttr.displayWidth)
    }
}
