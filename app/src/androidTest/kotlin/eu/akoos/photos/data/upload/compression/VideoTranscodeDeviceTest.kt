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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.akoos.photos.data.upload.VideoUploadCompressor
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.HdrHandling
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.domain.entity.compression.VideoSourceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Real transcodes on a device or emulator. Needs synthetic clips pushed to
 * `Android/data/<package>/files/clips/` (ffmpeg `testsrc2`: 1080p30 H.264 at 16 Mbps, 1080p60, 480p,
 * 360p, a 720p clip at 1 Mbps, an HDR10 H.265 clip, a portrait clip with a 90° display matrix, and
 * 720p clips in MOV, 3GP, WebM and MKV containers);
 * each test skips when its clip is missing.
 */
@RunWith(AndroidJUnit4::class)
class VideoTranscodeDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var caps: EncoderCapabilities
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        caps = VideoEncoderCapabilities().snapshot()
        tempDir = File(context.cacheDir, "transcode-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun clip(name: String): Pair<Uri, VideoSourceInfo> {
        val file = File(context.getExternalFilesDir("clips"), name)
        assumeTrue("clip $name not pushed", file.exists())
        val uri = Uri.fromFile(file)
        return uri to VideoSourceProbe.probe(context, uri)!!
    }

    private fun planFor(source: VideoSourceInfo, profile: VideoCompressionProfile): VideoCompressionPlan.Transcode {
        val plan = VideoCompressionPlanner.plan(source, profile, caps)
        assertTrue("expected a transcode plan, got $plan", plan is VideoCompressionPlan.Transcode)
        return plan as VideoCompressionPlan.Transcode
    }

    /**
     * Plans and transcodes [name]. [forceCodec] overrides the planned codec, so the transcoder can run
     * H.265 or AV1 on the emulator's software encoders, which the planner never picks.
     */
    private fun run(
        name: String,
        profile: VideoCompressionProfile,
        dateMs: Long = 0L,
        forceCodec: VideoCodec? = null,
    ): Pair<VideoCompressionPlan.Transcode, VideoUploadCompressor.Outcome> = runBlocking {
        val (uri, source) = clip(name)
        val planned = planFor(source, profile)
        val plan = forceCodec?.let { planned.copy(codec = it, plannedFallback = null, hardware = false) } ?: planned
        val outcome = VideoUploadCompressor.transcode(context, uri, source, plan, sourceDateEpochMs = dateMs, tempDir = tempDir)
        Log.i(TAG, "RESULT $name ${plan.codec} ${source.sizeBytes} -> ${outcome.outputBytes} ${outcome.outcome} ${outcome.reason ?: ""}")
        plan to outcome
    }

    private fun VideoUploadCompressor.Outcome.output(): File {
        assertNotNull("expected an output, got $reason $detail", file)
        return file!!
    }

    private fun trackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        } finally {
            extractor.release()
        }
    }

    private fun videoFormat(file: File): MediaFormat {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } finally {
            extractor.release()
        }
    }

    private fun <T> retrieve(file: File, read: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            read(retriever)
        } finally {
            retriever.release()
        }
    }

    private fun decodesAFrame(file: File) =
        retrieve(file) { it.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) != null }

    @Test
    fun h264_1080p_into_h264_comes_out_smaller() {
        val result = run("1080p30_h264_16M.mp4", VideoCompressionProfile(codec = VideoCodecChoice.AVC))
        val file = result.second.output()
        assertEquals(CompressionOutcome.COMPRESSED, result.second.outcome)
        assertEquals("video/avc", videoFormat(file).getString(MediaFormat.KEY_MIME))
        assertEquals("yes", retrieve(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) })
        assertTrue(decodesAFrame(file))
    }

    @Test
    fun hevc_without_a_hardware_encoder_falls_back_to_h264() {
        assumeTrue("this device has hardware H.265", !caps.hasHardware(VideoCodec.HEVC))
        val (plan, outcome) = run("360p_h264.mp4", VideoCompressionProfile(codec = VideoCodecChoice.HEVC))
        assertEquals(VideoCodec.AVC, plan.codec)
        assertEquals(VideoCodec.HEVC, plan.plannedFallback)
        assertEquals(CompressionOutcome.FELL_BACK, outcome.outcome)
    }

    @Test
    fun hevc_and_av1_outputs_play_back() {
        for (codec in listOf(VideoCodec.HEVC, VideoCodec.AV1)) {
            if (!caps.isAvailable(codec)) continue
            val file = run("360p_h264.mp4", VideoCompressionProfile(codec = VideoCodecChoice.AVC), forceCodec = codec).second.output()
            assertEquals(codec.mimeType, videoFormat(file).getString(MediaFormat.KEY_MIME))
            assertTrue("the $codec output decodes", decodesAFrame(file))
        }
    }

    @Test
    fun mov_3gp_webm_and_mkv_sources_come_out_as_mp4() {
        val clips = listOf("container_mov.mov", "container_3gp.3gp", "container_webm.webm", "container_mkv.mkv")
            .filter { File(context.getExternalFilesDir("clips"), it).exists() }
        assumeTrue("container clips not pushed", clips.isNotEmpty())
        for (name in clips) {
            val file = run(name, VideoCompressionProfile(codec = VideoCodecChoice.AVC)).second.output()
            val header = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
            assertEquals("$name: ISO base media file", "ftyp", String(header, 4, 4, Charsets.US_ASCII))
            assertTrue("$name: not a QuickTime file", String(header, 8, 4, Charsets.US_ASCII) != "qt  ")
            assertEquals(listOf("audio/mp4a-latm", "video/avc"), trackMimes(file).sorted())
            assertTrue("$name decodes", decodesAFrame(file))
        }
    }

    @Test
    fun hdr10_into_h264_is_real_sdr_h264() {
        // Space saver: the 5 Mbps clip is already efficient against the 1080p targets.
        val (plan, outcome) = run(
            "1080p_hdr10_hevc.mp4",
            VideoCompressionProfile(VideoCodecChoice.AVC, UploadCompressionTier.SPACE_SAVER),
        )
        assertEquals(HdrHandling.TONE_MAP_TO_SDR, plan.hdr)
        // The emulator can't tone-map (no 10-bit GL), so the export fails and the original would upload.
        assumeTrue("this device cannot tone-map HDR (${outcome.detail})", outcome.outcome != CompressionOutcome.FAILED)
        assertEquals("video/avc", outcome.outputMime)
        outcome.file?.let { file ->
            val format = videoFormat(file)
            if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                assertTrue("transfer $transfer", transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG)
            }
        }
    }

    @Test
    fun space_saver_caps_sixty_fps_to_thirty() {
        val result = run("1080p60_h264.mp4", VideoCompressionProfile(VideoCodecChoice.AVC, UploadCompressionTier.SPACE_SAVER))
        assertEquals(30f, result.first.frameRateCap)
        val output = VideoSourceProbe.probe(context, Uri.fromFile(result.second.output()))!!
        assertEquals(30f, output.frameRate!!, 2f)
        assertEquals(720, output.shortSide)
    }

    @Test
    fun a_rotated_portrait_clip_stays_portrait() {
        val file = run("portrait_rot.mp4", VideoCompressionProfile(VideoCodecChoice.AVC, UploadCompressionTier.SPACE_SAVER)).second.output()
        val (width, height, rotation) = retrieve(file) { r ->
            fun key(k: Int) = r.extractMetadata(k)!!.toInt()
            Triple(
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        }
        assertEquals(720, minOf(width, height))
        assertTrue("output must display portrait: ${width}x$height rot $rotation", (height > width) != (rotation % 180 == 90))
    }

    @Test
    fun the_capture_time_is_written_into_the_output() {
        val capture = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2019, Calendar.JUNE, 15, 10, 30, 0)
        }.timeInMillis
        val file = run(
            "1080p30_h264_16M.mp4",
            VideoCompressionProfile(VideoCodecChoice.AVC, UploadCompressionTier.SPACE_SAVER),
            dateMs = capture,
        ).second.output()
        val date = retrieve(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) }
        assertTrue("container date should be 2019-06-15, was $date", date?.startsWith("20190615") == true)
    }

    @Test
    fun an_already_small_clip_is_skipped_by_the_planner() {
        val (_, source) = clip("720p_already_small.mp4")
        val plan = VideoCompressionPlanner.plan(source, VideoCompressionProfile(codec = VideoCodecChoice.AVC), caps)
        assertEquals(VideoCompressionPlan.Skip(CompressionSkipReason.ALREADY_EFFICIENT), plan)
    }

    @Test
    fun cancelling_a_transcode_deletes_its_temp_file() = runBlocking {
        val (uri, source) = clip("1080p30_h264_16M.mp4")
        val plan = planFor(source, VideoCompressionProfile(codec = VideoCodecChoice.AVC))
        val started = CompletableDeferred<Unit>()
        val job = async {
            VideoUploadCompressor.transcode(
                context, uri, source, plan, tempDir = tempDir,
                onProgress = { if (it.fraction > 0f) started.complete(Unit) },
            )
        }
        withTimeout(60_000L) { started.await() }
        assertTrue("a temp file exists while encoding", tempDir.listFiles().orEmpty().isNotEmpty())
        job.cancel()
        runCatching { job.await() }
        // The delete is posted to the transcode's Looper; give it a moment to land.
        repeat(20) {
            if (tempDir.listFiles().orEmpty().isEmpty()) return@runBlocking
            Thread.sleep(100)
        }
        assertNull("temp left behind: ${tempDir.listFiles()?.toList()}", tempDir.listFiles()?.firstOrNull())
    }

    private companion object {
        const val TAG = "VideoTranscodeTest"
    }
}
