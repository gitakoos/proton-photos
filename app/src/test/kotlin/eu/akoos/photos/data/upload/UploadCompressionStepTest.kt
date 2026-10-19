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
import eu.akoos.photos.data.upload.compression.VideoUploadCompression
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.domain.usecase.UploadStructuralStripper
import eu.akoos.photos.util.MetadataStripConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The compression step the backup and the import share: which photos it leaves alone (a Motion Photo, an
 * Ultra HDR still without the HDR opt-in, anything while compression is off, anything that is not an image),
 * what it hands the compressor, and that video transcodes never overlap.
 */
class UploadCompressionStepTest {

    private val context = mockk<Context>(relaxed = true)
    private val stripper = mockk<UploadStructuralStripper>()
    private val video = mockk<VideoUploadCompression>()
    private val step = UploadCompressionStep(context, stripper, video)
    private val on = CompressionSettings(photos = true, tier = UploadCompressionTier.LIGHT)

    @Before
    fun setUp() {
        mockkObject(UploadImageCompressor)
        every { stripper.isMotionPhotoUpload(any()) } returns false
        every { stripper.hasGainMapUpload(any()) } returns false
    }

    @After
    fun tearDown() = unmockkObject(UploadImageCompressor)

    @Test
    fun `a motion photo is never compressed`() {
        every { stripper.isMotionPhotoUpload("file://a.jpg") } returns true

        val result = step.photo("file://a.jpg", "image/jpeg", on, MetadataStripConfig())

        assertNull(result.file)
        assertTrue(result.isMotionPhoto)
        assertEquals(CompressionOutcome.SKIPPED, result.outcome)
        assertEquals(CompressionSkipReason.MOTION_PHOTO, result.reason)
        verify(exactly = 0) { UploadImageCompressor.compress(any(), any(), any(), any()) }
    }

    @Test
    fun `an Ultra HDR still is skipped unless the HDR opt-in is on`() {
        every { stripper.hasGainMapUpload("file://hdr.jpg") } returns true
        every { UploadImageCompressor.compress(any(), any(), any(), any()) } returns
            UploadImageCompressor.Outcome(null, CompressionSkipReason.NOT_SMALLER, 10L)

        val skipped = step.photo("file://hdr.jpg", "image/jpeg", on, MetadataStripConfig())
        assertEquals(CompressionSkipReason.ULTRA_HDR, skipped.reason)
        assertFalse(skipped.isMotionPhoto)
        verify(exactly = 0) { UploadImageCompressor.compress(any(), any(), any(), any()) }

        val optedIn = step.photo("file://hdr.jpg", "image/jpeg", on.copy(hdr = true), MetadataStripConfig())
        assertEquals(CompressionSkipReason.NOT_SMALLER, optedIn.reason)
        verify(exactly = 1) { UploadImageCompressor.compress(any(), "file://hdr.jpg", any(), any()) }
    }

    @Test
    fun `with compression off nothing is probed or compressed`() {
        val result = step.photo("file://a.jpg", "image/jpeg", CompressionSettings(), MetadataStripConfig())

        assertNull(result.outcome)
        assertNull(result.file)
        verify(exactly = 0) { stripper.isMotionPhotoUpload(any()) }
        verify(exactly = 0) { stripper.hasGainMapUpload(any()) }
        verify(exactly = 0) { UploadImageCompressor.compress(any(), any(), any(), any()) }
    }

    @Test
    fun `a file that is not an image passes through`() {
        val result = step.photo("file://clip.mp4", "video/mp4", on, MetadataStripConfig())

        assertNull(result.outcome)
        assertNull(result.producedMime)
        verify(exactly = 0) { stripper.isMotionPhotoUpload(any()) }
        verify(exactly = 0) { UploadImageCompressor.compress(any(), any(), any(), any()) }
    }

    @Test
    fun `a compressed photo is a JPEG at the photo tier with the given strip config`() {
        val out = File.createTempFile("compressed_", ".jpg")
        val strip = MetadataStripConfig(stripGps = true)
        every { UploadImageCompressor.compress(context, "file://a.heic", UploadCompressionTier.LIGHT, strip) } returns
            UploadImageCompressor.Outcome(out, null, 500L)

        val result = step.photo("file://a.heic", "image/heic", on, strip)

        assertEquals(out, result.file)
        assertEquals(UploadImageCompressor.JPEG_MIME, result.producedMime)
        assertEquals(CompressionOutcome.COMPRESSED, result.outcome)
        assertEquals(500L, result.sourceBytes)
        out.delete()
    }

    @Test
    fun `video compression applies only to a video with video compression on`() {
        assertTrue(step.compressesVideo("video/quicktime", CompressionSettings(videos = true)))
        assertFalse(step.compressesVideo("video/mp4", CompressionSettings(photos = true)))
        assertFalse(step.compressesVideo("image/jpeg", CompressionSettings(videos = true)))
    }

    @Test
    fun `a video is transcoded with the video profile and capture date, one at a time`() = runBlocking {
        val profile = VideoCompressionProfile(VideoCodecChoice.HEVC, UploadCompressionTier.SPACE_SAVER)
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        coEvery { video.compress(any(), any(), any(), any(), any()) } coAnswers {
            most.updateAndGet { maxOf(it, running.incrementAndGet()) }
            delay(50)
            running.decrementAndGet()
            VideoUploadCompression.Attempt(null, CompressionOutcome.SKIPPED, CompressionSkipReason.ALREADY_EFFICIENT)
        }
        val settings = CompressionSettings(videos = true, videoProfile = profile)

        List(3) { i -> async { step.video("file://clip$i.mov", settings, 1_700_000_000_000L) } }.awaitAll()

        assertEquals("transcodes never overlap", 1, most.get())
        coVerify(exactly = 1) { video.compress("file://clip0.mov", profile, 1_700_000_000_000L, any(), any()) }
    }
}
