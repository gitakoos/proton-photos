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

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.akoos.photos.domain.entity.UploadCompressionTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * On-device coverage for [VideoUploadCompressor]. Runs the real Media3 Transformer transcode against
 * the actual videos on the device (camera clips), so it exercises the exact path an opt-in upload
 * takes without needing to reach Drive. It verifies the invariants that a cloud round-trip cannot
 * check precisely: orientation is preserved (a portrait clip stays portrait, never pillar-boxed into
 * a landscape frame), the output is genuinely smaller, the duration survives, and an oversized (8K)
 * source is skipped so the original uploads untouched instead of risking an out-of-memory crash.
 *
 * Needs at least one video in the media store; skips cleanly when none is present. Run with
 * `adb shell am instrument` (the release unit-test task does not include instrumented tests).
 */
@RunWith(AndroidJUnit4::class)
class VideoUploadCompressorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private data class Meta(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMs: Long,
        val location: String?,
        val date: String?,
        val bitrate: Int,
        val fps: String?,
    ) {
        /** Orientation as PLAYED (rotation applied), which is what must survive a transcode. */
        val isPortraitOnScreen: Boolean
            get() = if (rotation == 90 || rotation == 270) width > height else height > width

        override fun toString(): String =
            "${width}x$height rot=$rotation ${durationMs}ms ${if (isPortraitOnScreen) "PORTRAIT" else "landscape"} " +
                "loc=${location ?: "-"} date=${date ?: "-"} ${bitrate / 1000}kbps fps=${fps ?: "-"}"
    }

    private fun probe(uri: Uri): Meta? = probeRetriever { it.setDataSource(context, uri) }

    private fun probe(file: File): Meta? = probeRetriever { it.setDataSource(file.absolutePath) }

    private fun probeRetriever(open: (MediaMetadataRetriever) -> Unit): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            Meta(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The newest device videos as (content uri, byte size), newest first. */
    private fun deviceVideos(limit: Int): List<Triple<Uri, Long, Long>> {
        val result = ArrayList<Triple<Uri, Long, Long>>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                result.add(Triple(uri, cursor.getLong(sizeColumn), cursor.getLong(dateColumn)))
            }
        }
        return result
    }

    @Test
    fun transcode_preserves_orientation_and_shrinks_real_device_videos() = runBlocking<Unit> {
        val videos = deviceVideos(limit = 8)
        assumeTrue("no device videos to test against", videos.isNotEmpty())

        // Space saver forces a downscale even on a 1080p clip (short-side cap 720), so the
        // orientation-preserving scale path is actually exercised rather than a bitrate-only re-encode.
        val params = VideoUploadCompressor.VideoCompressionParams(
            UploadCompressionTier.SPACE_SAVER.videoMaxShortEdgePx,
            UploadCompressionTier.SPACE_SAVER.videoBitrateBps,
        )
        var exercised = 0

        for ((uri, sourceSize, dateTakenMs) in videos) {
            val source = probe(uri) ?: continue
            Log.i(TAG, "SOURCE $uri  $source  ${sourceSize}B  dateTaken=$dateTakenMs")

            val output = VideoUploadCompressor.compressToTemp(context, uri, params, dateTakenMs)

            val longEdge = maxOf(source.width, source.height)
            if (longEdge > MAX_SAFE_LONG_EDGE) {
                assertTrue("an 8K source ($source) must be skipped so the original uploads untouched", output == null)
                Log.i(TAG, "  -> SKIPPED (frame too large); original uploads unchanged  [OK]")
                exercised++
                continue
            }

            if (output == null) {
                // Allowed: a clip that would not shrink (already small / low bitrate) uploads as-is.
                Log.i(TAG, "  -> null (not smaller or unsupported); original uploads unchanged")
                continue
            }

            try {
                val out = probe(output)
                assertTrue("the transcoded file must be readable", out != null)
                out!!
                Log.i(TAG, "  -> OUTPUT $out  ${output.length()}B")
                Log.i(
                    TAG,
                    "  META location: '${source.location}' -> '${out.location}'  |  date: '${source.date}' -> '${out.date}'",
                )

                assertTrue(
                    "compressed (${output.length()}) must be smaller than source ($sourceSize)",
                    output.length() < sourceSize,
                )
                assertTrue(
                    "orientation must be preserved: source $source vs output $out",
                    out.isPortraitOnScreen == source.isPortraitOnScreen,
                )
                if (source.durationMs > 0L) {
                    val drift = abs(out.durationMs - source.durationMs).toDouble() / source.durationMs
                    assertTrue(
                        "duration must be preserved (source ${source.durationMs}ms vs output ${out.durationMs}ms)",
                        drift < 0.15,
                    )
                }
                Log.i(TAG, "  -> [OK] smaller, orientation preserved, duration kept")
                exercised++
            } finally {
                output.delete()
            }
        }

        assumeTrue("no suitable video exercised the transcoder (all skipped or already minimal)", exercised > 0)
        Log.i(TAG, "exercised $exercised device video(s)")
    }

    private companion object {
        const val TAG = "VideoCompressTest"

        /** Mirrors the compressor's own input ceiling: anything above this long edge is left untouched. */
        const val MAX_SAFE_LONG_EDGE = 4096
    }
}
