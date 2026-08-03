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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Pins [PhotoTagDetector.detectTags], the classification stamped on EVERY upload's commit and cached
 * for every file on the device.
 *
 * Three properties carry the weight. The id RANGE, because the Drive PhotoTag enum runs 0 to 9 and
 * the server rejects anything outside it: an id this function invents is not a mis-filed photo, it is
 * a refused commit and so a lost backup. The SIZE GATE, because it is all that stands between a
 * library scan and a half-megabyte read per file, and moving it silently changes which photos ever
 * get a motion or panorama marker. And the exact MARKER set, because the detector carries a version
 * whose bump wipes and re-detects every file on the device, so a change here is expensive enough to
 * be worth noticing before it ships.
 *
 * No device, no MediaStore and no real file: the content resolver hands back the bytes each case
 * needs, which is all the detector ever reads.
 */
class PhotoTagDetectorTest {

    private companion object {
        /** The detector skips the XMP read at or below this size, so cases either sit on it or clear it. */
        const val XMP_FLOOR = 1_500_000L
        const val ABOVE_FLOOR = XMP_FLOOR + 1
        /** Every id the Drive PhotoTag enum defines. Anything outside it is refused by the server. */
        val SERVER_ACCEPTED_IDS = 0..9
    }

    /** Head bytes the stubbed resolver serves on the next open; null stands for "cannot be opened". */
    private var headBytes: ByteArray? = null

    private lateinit var context: Context
    private val uri = mockk<Uri>()

    @Before
    fun setUp() {
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers { headBytes?.let { ByteArrayInputStream(it) } }
        context = mockk()
        every { context.contentResolver } returns resolver
    }

    /** Binary head bytes with [marker] embedded, the shape an XMP packet has inside a JPEG APP1. */
    private fun xmpBytes(marker: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
            "<x:xmpmeta $marker />".toByteArray(Charsets.ISO_8859_1)

    private fun detect(
        mimeType: String = "image/jpeg",
        displayName: String = "IMG_0001.jpg",
        sizeBytes: Long = 200_000L,
        xmp: String? = null,
    ): List<Int> {
        headBytes = xmp?.let { xmpBytes(it) }
        return PhotoTagDetector.detectTags(context, uri, mimeType, displayName, sizeBytes)
    }

    // ── the id range the server accepts ──────────────────────────────────────────────────────────

    @Test
    fun `every id the detector can produce is one the server accepts`() {
        // The reachable output space, walked: an id outside 0..9 is a refused commit, so this is the
        // test that must fail first if a new tag is ever wired up with the wrong number.
        val mimes = listOf(
            "image/jpeg", "image/heic", "image/png", "video/mp4", "video/quicktime",
            "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-raw",
            "application/octet-stream", "",
        )
        val names = listOf(
            "IMG_0001.jpg", "Screenshot_20260101.png", "screen_shot 1.png", "photo.dng",
            "clip.cr3", "PANO_0002.jpg", "MVIMG_0003.jpg", "no-extension", "",
        )
        val markers = listOf(
            null, "Camera:MotionPhoto=\"1\"", "GCamera:MicroVideo=\"1\"",
            "Item:Semantic=\"MotionPhoto\"", "GPano:UsePanoramaViewer=\"True\"",
            "GPano:UsePanoramaViewer Camera:MotionPhoto=\"1\"", "dc:description=\"nothing\"",
        )
        var produced = 0
        for (mime in mimes) for (name in names) for (marker in markers) {
            for (size in listOf(0L, XMP_FLOOR, ABOVE_FLOOR)) {
                detect(mime, name, size, marker).forEach { id ->
                    produced++
                    assertTrue(
                        "$mime / $name / $marker / $size produced id $id, which the server refuses",
                        id in SERVER_ACCEPTED_IDS,
                    )
                }
            }
        }
        assertTrue("the walk has to actually classify something", produced > 0)
    }

    @Test
    fun `the favourite id never comes out of a detection`() {
        // Tag 0 travels on its own endpoint; a detector that emitted it would put the commit at risk.
        assertFalse(0 in detect("video/mp4", "clip.mp4"))
        assertFalse(0 in detect("image/jpeg", "Screenshot_1.png", ABOVE_FLOOR, "Camera:MotionPhoto=\"1\""))
    }

    // ── the cheap tags, read off the mime and the name ───────────────────────────────────────────

    @Test
    fun `a video is tagged from its mime alone`() {
        assertEquals(listOf(2), detect("video/mp4", "clip.mp4"))
        assertEquals(listOf(2), detect("VIDEO/MP4", "clip.mp4"))
        assertEquals(listOf(2), detect("video/quicktime", "clip.mov"))
    }

    @Test
    fun `an ordinary photo carries no tag at all`() {
        assertTrue(detect("image/jpeg", "IMG_0001.jpg").isEmpty())
    }

    @Test
    fun `a raw file is tagged from either its extension or its mime`() {
        assertEquals(listOf(9), detect("image/jpeg", "photo.dng"))
        assertEquals(listOf(9), detect("image/jpeg", "PHOTO.CR3"))
        assertEquals(listOf(9), detect("image/x-adobe-dng", "photo.bin"))
        assertEquals(listOf(9), detect("IMAGE/X-RAW", "photo.bin"))
    }

    @Test
    fun `a raw extension and a raw mime together still tag the file once`() {
        assertEquals(listOf(9), detect("image/x-adobe-dng", "photo.dng"))
    }

    @Test
    fun `an extension that merely looks raw is not raw`() {
        assertTrue(detect("image/jpeg", "photo.raw").isEmpty())
        assertTrue(detect("image/jpeg", "photo.dngx").isEmpty())
    }

    @Test
    fun `a screenshot is recognised from the two names the system writes`() {
        assertEquals(listOf(1), detect("image/png", "Screenshot_20260101-120000.png"))
        assertEquals(listOf(1), detect("image/png", "screen_shot 2026-01-01.png"))
        assertEquals(listOf(1), detect("image/png", "SCREENSHOT.PNG"))
    }

    @Test
    fun `a screenshot name only counts on an image`() {
        // A screen RECORDING carries the same name prefix, and it is a video, not a screenshot.
        assertEquals(listOf(2), detect("video/mp4", "Screenshot_20260101.mp4"))
    }

    @Test
    fun `the name has to start with the screenshot prefix, not merely contain it`() {
        assertTrue(detect("image/png", "my screenshot.png").isEmpty())
        assertTrue(detect("image/png", "edited-Screenshot_1.png").isEmpty())
    }

    @Test
    fun `the detector never reads the folder a photo sits in`() {
        // It is handed a name and a mime and nothing else, so a screenshot the folder identifies but
        // the filename does not is left untagged here.
        assertTrue(detect("image/png", "2026-01-01 12.00.00.png").isEmpty())
    }

    // ── the size gate in front of the XMP read ───────────────────────────────────────────────────

    @Test
    fun `a small photo is never scanned, whatever its bytes say`() {
        assertTrue(detect("image/jpeg", "IMG_1.jpg", 900_000L, "Camera:MotionPhoto=\"1\"").isEmpty())
        assertTrue(detect("image/jpeg", "IMG_1.jpg", 0L, "GPano:UsePanoramaViewer=\"True\"").isEmpty())
    }

    @Test
    fun `the floor is exclusive, so a photo exactly on it is skipped`() {
        assertTrue(detect("image/jpeg", "IMG_1.jpg", XMP_FLOOR, "Camera:MotionPhoto=\"1\"").isEmpty())
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", XMP_FLOOR + 1, "Camera:MotionPhoto=\"1\""))
    }

    @Test
    fun `a large video is not scanned for still-image markers`() {
        // Only an image mime opens the file, so a video's own metadata never yields tag 4 or 8.
        assertEquals(listOf(2), detect("video/mp4", "clip.mp4", 80_000_000L, "Camera:MotionPhoto=\"1\""))
    }

    @Test
    fun `a large file with no image mime at all is not scanned`() {
        assertTrue(detect("application/octet-stream", "blob.bin", ABOVE_FLOOR, "GPano:").isEmpty())
    }

    // ── the XMP markers ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `all four motion photo markers are recognised`() {
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "Camera:MotionPhoto=\"1\""))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MicroVideo=\"1\""))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "MotionPhoto=\"1\""))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "Item:Semantic=\"MotionPhoto\""))
    }

    @Test
    fun `the motion photo flag is read with the spacing and quoting cameras actually write`() {
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MotionPhoto = '1'"))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "MotionPhoto=1"))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MicroVideo = '1'"))
        assertEquals(listOf(4), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "MicroVideo=1"))
    }

    @Test
    fun `a flag switched off leaves the photo untagged`() {
        // A camera that can shoot motion photos writes the attribute on every frame and sets it to
        // zero when motion is off, so the value is the whole signal.
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MotionPhoto=\"0\"").isEmpty())
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MicroVideo=\"0\"").isEmpty())
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "Camera:MotionPhoto = '0'").isEmpty())
    }

    @Test
    fun `an attribute that merely starts with a marker name is not the marker`() {
        // Both of these sit beside a real flag on a genuine motion photo and beside a zeroed one on
        // an ordinary still, so neither can stand in for the flag itself.
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MotionPhotoVersion=\"1\"").isEmpty())
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MicroVideoVersion=\"1\"").isEmpty())
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:MicroVideoOffset=\"4013058\"").isEmpty())
    }

    @Test
    fun `a switched-off flag beside its version attribute still leaves the photo untagged`() {
        // The shape an ordinary Pixel still actually has: the flag at zero, the version at one.
        assertTrue(
            detect(
                "image/jpeg", "IMG_1.jpg", ABOVE_FLOOR,
                "GCamera:MicroVideo=\"0\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"0\"",
            ).isEmpty(),
        )
    }

    @Test
    fun `a genuine motion photo is still tagged when its version attribute rides along`() {
        assertEquals(
            listOf(4),
            detect(
                "image/jpeg", "IMG_1.jpg", ABOVE_FLOOR,
                "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"4013058\"",
            ),
        )
        assertEquals(
            listOf(4),
            detect(
                "image/jpeg", "IMG_1.jpg", ABOVE_FLOOR,
                "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"",
            ),
        )
    }

    @Test
    fun `a packet with no motion wording at all leaves the photo untagged`() {
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GCamera:HdrPlusMakernote=\"1\"").isEmpty())
    }

    @Test
    fun `a panorama is recognised only from its own namespace marker`() {
        assertEquals(listOf(8), detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GPano:UsePanoramaViewer=\"True\""))
        // Never from the name and never from a shape: a wide stitch is proven by the marker or not at all.
        assertTrue(detect("image/jpeg", "PANO_0001.jpg", ABOVE_FLOOR, "dc:description=\"wide\"").isEmpty())
    }

    @Test
    fun `a photo that is both a motion photo and a panorama gets both markers`() {
        assertEquals(
            listOf(4, 8),
            detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "GPano:Foo=\"1\" Camera:MotionPhoto=\"1\""),
        )
    }

    @Test
    fun `an unrelated packet leaves the photo untagged`() {
        assertTrue(detect("image/jpeg", "IMG_1.jpg", ABOVE_FLOOR, "dc:creator=\"someone\"").isEmpty())
    }

    // ── the order the tags come out in ───────────────────────────────────────────────────────────

    @Test
    fun `the tags come out cheapest first, in the order every upload has always sent`() {
        // The list goes to the commit as-is, so its order is part of what the server sees.
        assertEquals(
            listOf(9, 1, 4, 8),
            detect(
                "image/x-adobe-dng", "Screenshot_1.dng", ABOVE_FLOOR,
                "Camera:MotionPhoto=\"1\" GPano:X=\"1\"",
            ),
        )
    }

    // ── a read that cannot happen ────────────────────────────────────────────────────────────────

    @Test
    fun `a file the app can no longer open costs only its metadata tags`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } throws IOException("gone")
        val broken = mockk<Context>()
        every { broken.contentResolver } returns resolver
        assertEquals(
            "the cheap tags survive a failed read",
            listOf(9),
            PhotoTagDetector.detectTags(broken, uri, "image/x-adobe-dng", "photo.dng", ABOVE_FLOOR),
        )
    }

    @Test
    fun `a resolver that hands back nothing is not an error`() {
        headBytes = null
        assertTrue(PhotoTagDetector.detectTags(context, uri, "image/jpeg", "IMG_1.jpg", ABOVE_FLOOR).isEmpty())
    }

    @Test
    fun `a stream that dies mid-read leaves the photo with only its cheap tags`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } returns object : InputStream() {
            override fun read(): Int = throw IOException("torn")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("torn")
        }
        val torn = mockk<Context>()
        every { torn.contentResolver } returns resolver
        assertEquals(
            listOf(9),
            PhotoTagDetector.detectTags(torn, uri, "image/x-adobe-dng", "photo.dng", ABOVE_FLOOR),
        )
    }

    // ── the version that governs the cache ───────────────────────────────────────────────────────

    @Test
    fun `the detector version is the one the cached detections were built under`() {
        // A behaviour change above without a bump here leaves every device on the old classification.
        assertEquals(2, PhotoTagDetector.DETECTOR_VERSION)
    }
}
