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

package eu.akoos.photos.data.upload.compression

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.akoos.photos.domain.entity.compression.HdrTransfer
import eu.akoos.photos.domain.entity.compression.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real encoder scan and source probe. The probe test needs the clips described in
 * [VideoTranscodeDeviceTest] and skips without them.
 */
@RunWith(AndroidJUnit4::class)
class VideoEncoderCapabilitiesDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun scan_finds_avc_and_reports_sane_ranges() {
        val snapshot = VideoEncoderCapabilities().snapshot()
        assertTrue("H.264 encoding is mandatory on Android", snapshot.isAvailable(VideoCodec.AVC))
        snapshot.codecs.values.flatMap { it.encoders }.forEach { encoder ->
            assertTrue("${encoder.name} width", encoder.maxWidth >= 176)
            assertTrue("${encoder.name} height", encoder.maxHeight >= 144)
        }
    }

    @Test
    fun probe_reads_codec_size_rate_and_hdr_from_the_test_clips() {
        val dir = context.getExternalFilesDir("clips")
        val clip = File(dir, "1080p30_h264_16M.mp4")
        assumeTrue("test clips not pushed", clip.exists())

        val avc = VideoSourceProbe.probe(context, Uri.fromFile(clip))!!
        Log.i(TAG, "1080p30 probe: $avc")
        assertEquals("video/avc", avc.mimeType)
        assertEquals(1080, avc.shortSide)
        assertEquals(30f, avc.frameRate!!, 0.5f)
        assertTrue(avc.hasAudio)
        assertEquals(12_000.0, avc.durationMs.toDouble(), 200.0)
        assertEquals(16.0, avc.effectiveVideoBitrate!! / 1_000_000.0, 1.5)

        File(dir, "1080p_hdr10_hevc.mp4").takeIf { it.exists() }?.let { file ->
            val hdr = VideoSourceProbe.probe(context, Uri.fromFile(file))!!
            Log.i(TAG, "hdr probe: $hdr")
            assertEquals("video/hevc", hdr.mimeType)
            assertEquals(HdrTransfer.PQ, hdr.hdrTransfer)
        }
        File(dir, "1080p60_h264.mp4").takeIf { it.exists() }?.let { file ->
            val sixty = VideoSourceProbe.probe(context, Uri.fromFile(file))!!
            assertEquals(60f, sixty.frameRate!!, 1f)
            assertTrue(!sixty.hasAudio)
        }
    }

    private companion object {
        const val TAG = "CompressionDeviceTest"
    }
}
