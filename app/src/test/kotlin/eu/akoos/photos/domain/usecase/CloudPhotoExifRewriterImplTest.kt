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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.util.ImagePlaceParts
import eu.akoos.photos.util.Mp4CreationTime
import eu.akoos.photos.util.PlaceFixtures
import eu.akoos.photos.util.PlaceLeftovers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The real [CloudPhotoExifRewriterImpl] on its video branch, which the copy-reupload pipeline reaches
 * for a video date edit. Two things are pinned: the FILE edit is the container mvhd, never EXIF (a video
 * takes the date only, and place / text are image-only, so a place-or-text-only request touches nothing);
 * and the xAttr reports the RAW stream width / height / duration read off the container with no camera or
 * place block, the shape the upload path sends for a video. The image branch is proven untouched by the
 * camera block it still emits, which a video never carries.
 *
 * The mvhd walk is exercised against MP4 box trees built byte by byte (the same fixtures shape
 * [eu.akoos.photos.util.Mp4CreationTimeTest] uses), and the retriever read against
 * [ShadowMediaMetadataRetriever], so no binary fixture or device is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudPhotoExifRewriterImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rewriter = CloudPhotoExifRewriterImpl(context)

    private val captureMs = 1_783_507_135_000L
    private val mp4EpochOffsetSeconds = 2_082_844_800L

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
    }

    // ---- MP4 fixture builders -------------------------------------------------

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** A version-0 mvhd holding [creationMp4Seconds] in both timestamp fields. The trailing fixed fields
     *  are zeroed: the walk never looks past the two timestamps, but the bytes have to be present. */
    private fun mvhd(creationMp4Seconds: Long): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 100)
        buffer.putInt(8 + 100)
        buffer.put(ascii("mvhd"))
        buffer.put(0)             // version 0
        buffer.put(ByteArray(3))  // flags
        buffer.putInt(creationMp4Seconds.toInt())
        buffer.putInt(creationMp4Seconds.toInt())
        return buffer.array()
    }

    private fun container(type: String, vararg children: ByteArray): ByteArray {
        val size = 8 + children.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(size)
        buffer.put(ascii(type))
        children.forEach { buffer.put(it) }
        return buffer.array()
    }

    private fun ftyp(): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putInt(16)
        buffer.put(ascii("ftyp"))
        buffer.put(ascii("isom"))
        buffer.putInt(512)
        return buffer.array()
    }

    private fun mp4File(mvhdMp4Seconds: Long): File {
        val file = File.createTempFile("pfp_rewriter_", ".mp4")
        file.deleteOnExit()
        file.writeBytes(ftyp() + container("moov", mvhd(mvhdMp4Seconds)))
        return file
    }

    private fun toMp4(ms: Long): Long = ms / 1000L + mp4EpochOffsetSeconds

    private fun registerVideo(uri: String, width: Int, height: Int, durationMs: Long) {
        val ds = DataSource.toDataSource(context, Uri.parse(uri))
        ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, width.toString())
        ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, height.toString())
        ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_DURATION, durationMs.toString())
    }

    // ---- the file edit: mvhd, never EXIF --------------------------------------

    @Test
    fun `a video date edit stamps the mvhd to the new capture second`() {
        val file = mp4File(mvhdMp4Seconds = 0L)
        assertNull("the fixture must start with no usable date", Mp4CreationTime.read(file))

        rewriter.rewrite(
            file = file,
            mimeType = "video/mp4",
            writeCaptureMs = captureMs,
            xAttrCaptureMs = captureMs,
            location = LocationEdit.Unchanged,
            description = null,
            artist = null,
            copyright = null,
        )

        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a place-or-text-only request never rewrites a video`() {
        val file = mp4File(mvhdMp4Seconds = toMp4(captureMs))
        val before = file.readBytes()

        // A video takes the date only: writeCaptureMs null (date not addressed) plus a place and a text
        // value must all be no-ops, because place and text are image-only. Nothing may reach the file.
        rewriter.rewrite(
            file = file,
            mimeType = "video/mp4",
            writeCaptureMs = null,
            xAttrCaptureMs = captureMs,
            location = LocationEdit.Set(47.5, 19.05),
            description = "ignored",
            artist = "ignored",
            copyright = "ignored",
        )

        assertArrayEquals("a place/text edit must not rewrite a video", before, file.readBytes())
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    // ---- the xAttr: raw dims + duration, no camera / place block ---------------

    @Test
    fun `a video xAttr reports the raw dimensions and duration with no camera or place block`() {
        val uri = "content://media/external/video/media/1"
        registerVideo(uri, width = 1920, height = 1080, durationMs = 5_000L)

        // Even with a place asked for, a video xAttr carries no place block: the edit is a no-op there.
        val xAttr = rewriter.xAttrFor(uri, "video/mp4", captureMs, LocationEdit.Set(47.5, 19.05))

        assertEquals(1920, xAttr.displayWidth)
        assertEquals(1080, xAttr.displayHeight)
        assertEquals(5_000L, xAttr.durationMillis)
        assertNull("a video xAttr carries no camera orientation", xAttr.cameraOrientation)
        assertNull("a video xAttr carries no capture-time block", xAttr.cameraCaptureTimeIso)
        assertNull(xAttr.cameraDevice)
        assertNull("a video xAttr carries no place block", xAttr.latitude)
        assertNull(xAttr.longitude)
    }

    @Test
    fun `a video xAttr omits a dimension the container could not report rather than sending a zero`() {
        val uri = "content://media/external/video/media/2"
        // A container that answers nothing: every field must fall out, never land as 0.
        val xAttr = rewriter.xAttrFor(uri, "video/mp4", captureMs, LocationEdit.Unchanged)

        assertNull(xAttr.displayWidth)
        assertNull(xAttr.displayHeight)
        assertNull(xAttr.durationMillis)
    }

    // ---- the image branch is unchanged ----------------------------------------

    @Test
    fun `an image xAttr keeps the camera block and capture time`() {
        // The image branch always emits a camera orientation (defaulting to NORMAL) and the capture-time
        // ISO, neither of which a video ever carries, so its presence proves the image path still ran and
        // was not diverted to the video branch. A bogus uri reads as empty EXIF, which is enough here.
        val xAttr = rewriter.xAttrFor(
            "content://media/external/images/media/3",
            "image/jpeg",
            captureMs,
            LocationEdit.Unchanged,
        )

        assertNotNull("an image always carries a camera orientation", xAttr.cameraOrientation)
        assertEquals(
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(captureMs)),
            xAttr.cameraCaptureTimeIso,
        )
        assertNull("an image xAttr never carries a video duration", xAttr.durationMillis)
    }

    private val xmpWithPlace = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="38,54.402N"/></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithPlace(): File {
        val file = File.createTempFile("pfp_rewriter_", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply { setLatLong(38.9067, 1.4206); saveAttributes() }
        return file
    }

    private fun rewrite(file: File, mimeType: String, location: LocationEdit) =
        rewriter.rewrite(file, mimeType, null, captureMs, location, null, null, null)

    @Test
    fun `a removed place is taken out of a JPEG file and the copy carries none`() {
        val file = jpegWithPlace()

        val xAttr = rewrite(file, "image/jpeg", LocationEdit.Clear)

        assertNull(ExifInterface(file.absolutePath).latLong)
        assertNull(xAttr.latitude)
    }

    @Test
    fun `a removed place the file still holds fails the edit`() {
        val file = jpegWithPlace()

        assertThrows(PlaceLeftInFileException::class.java) { rewrite(file, "image/heic", LocationEdit.Clear) }
    }

    @Test
    fun `a new place that leaves the old one in the file fails the edit`() {
        val file = jpegWithPlace()

        assertThrows(PlaceLeftInFileException::class.java) { rewrite(file, "image/heic", LocationEdit.Set(10.0, 20.0)) }
    }

    private val fixtures by lazy { PlaceFixtures(context) }

    private fun fileOf(bytes: ByteArray): File =
        File.createTempFile("pfp_rewriter_", ".jpg", context.cacheDir).apply { writeBytes(bytes) }

    @Test
    fun `a removed place held in the XMP is taken out of it`() {
        val file = jpegWithPlace()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_XMP, xmpWithPlace)
            saveAttributes()
        }

        rewrite(file, "image/jpeg", LocationEdit.Clear)

        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("GPSLatitude"))
    }

    @Test
    fun `a removed place held by a picture appended after the main one is taken out of it`() {
        val file = fileOf(fixtures.jpeg() + fixtures.jpeg(withPlace = true))

        rewrite(file, "image/jpeg", LocationEdit.Clear)

        assertFalse(PlaceLeftovers.holdsPlace(file.readBytes()))
    }

    @Test
    fun `a removed place is taken out of an Ultra HDR photo and its gain map stays reachable`() {
        val file = fileOf(fixtures.ultraHdr())

        rewrite(file, "image/jpeg", LocationEdit.Clear)

        val bytes = file.readBytes()
        val gainMap = fixtures.mpfSecondPicture(bytes)
        assertTrue(ImagePlaceParts.isJpeg(bytes, gainMap))
        assertNull(fixtures.exifPlaceAt(bytes, gainMap))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains(PlaceFixtures.GAIN_MAP_XMP))
    }

    @Test
    fun `a new place on an Ultra HDR photo replaces the old one in the gain map too`() {
        val file = fileOf(fixtures.ultraHdr())

        rewrite(file, "image/jpeg", LocationEdit.Set(10.0, 20.0))

        val bytes = file.readBytes()
        assertEquals(10.0, fixtures.exifPlaceAt(bytes, 0)!![0], 1e-4)
        assertNull(fixtures.exifPlaceAt(bytes, fixtures.mpfSecondPicture(bytes)))
    }

    @Test
    fun `a removed place is taken out of a motion photo's movie`() {
        val original = fixtures.motionPhoto()
        val file = fileOf(original)

        rewrite(file, "image/jpeg", LocationEdit.Clear)

        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("+38.9067"))
        val movieData = 72
        assertArrayEquals(original.copyOfRange(original.size - movieData, original.size), bytes.copyOfRange(bytes.size - movieData, bytes.size))
    }

    @Test
    fun `a removed place kept in a PNG's compressed XMP fails the edit`() {
        val file = fileOf(fixtures.pngWithXmp(fixtures.xmp("""exif:GPSLatitude="38,54.402N">"""), compressed = true))

        assertThrows(PlaceLeftInFileException::class.java) { rewrite(file, "image/png", LocationEdit.Clear) }
    }

    @Test
    fun `a new place written into a JPEG file replaces the old one`() {
        val file = jpegWithPlace()

        rewrite(file, "image/jpeg", LocationEdit.Set(10.0, 20.0))

        assertEquals(10.0, ExifInterface(file.absolutePath).latLong!![0], 1e-4)
    }
}
