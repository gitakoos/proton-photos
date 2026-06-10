/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.presentation.editor

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for [VideoMetadataStripper]. MediaExtractor/MediaMuxer/
 * MediaMetadataRetriever are framework classes with no JVM implementation, so
 * [VideoMetadataStripper.remuxWithoutLocation] itself is exercised on-device only.
 * These tests target the two factored-out pure helpers:
 *   - [VideoMetadataStripper.extractorFlagsToBufferFlags] (extractor→muxer flag mapping)
 *   - [VideoMetadataStripper.nextSampleBufferCapacity] (grow-vs-rethrow cap decision)
 *
 * The SAMPLE_FLAG and BUFFER_FLAG values are `static final int` constants carrying
 * ConstantValue in android.jar, so the compiler inlines them into both the production
 * mapper and this test — the mapping is deterministic without Robolectric and is not
 * affected by testOptions.unitTests.isReturnDefaultValues.
 *
 * Not covered (no such surface exists in the class): there is NO mp4 location-key
 * predicate and NO should-strip gate. Stripping is structural — the remux simply never
 * calls MediaMuxer.setLocation, so the rewritten moov/udta carries no coordinates and
 * there is no per-key decision to assert.
 */
class VideoMetadataStripperTest {

    // ─── extractorFlagsToBufferFlags ──────────────────────────────────────────

    @Test
    fun `sync sample maps to key-frame buffer flag`() {
        val out = VideoMetadataStripper.extractorFlagsToBufferFlags(MediaExtractor.SAMPLE_FLAG_SYNC)
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, out)
    }

    @Test
    fun `partial-frame sample maps to partial-frame buffer flag`() {
        val out = VideoMetadataStripper.extractorFlagsToBufferFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME, out)
    }

    @Test
    fun `sync and partial-frame both set are OR-combined`() {
        val input = MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
        val out = VideoMetadataStripper.extractorFlagsToBufferFlags(input)
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME, out)
    }

    @Test
    fun `no recognised flags maps to zero`() {
        assertEquals(0, VideoMetadataStripper.extractorFlagsToBufferFlags(0))
    }

    @Test
    fun `unmapped extractor flag is dropped not forwarded`() {
        // SAMPLE_FLAG_ENCRYPTED (2) has no buffer-flag counterpart in the mapping; it
        // must not leak into the output as a stray bit.
        val out = VideoMetadataStripper.extractorFlagsToBufferFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        assertEquals(0, out)
    }

    // ─── nextSampleBufferCapacity (grow-vs-rethrow cap) ───────────────────────

    @Test
    fun `capacity below cap doubles`() {
        assertEquals(2 shl 20, VideoMetadataStripper.nextSampleBufferCapacity(1 shl 20))
    }

    @Test
    fun `capacity just under cap still doubles`() {
        val justUnder = VideoMetadataStripper.MAX_SAMPLE_BUFFER_BYTES / 2
        assertEquals(VideoMetadataStripper.MAX_SAMPLE_BUFFER_BYTES, VideoMetadataStripper.nextSampleBufferCapacity(justUnder))
    }

    @Test
    fun `capacity at cap signals rethrow`() {
        assertNull(VideoMetadataStripper.nextSampleBufferCapacity(VideoMetadataStripper.MAX_SAMPLE_BUFFER_BYTES))
    }

    @Test
    fun `capacity above cap signals rethrow`() {
        assertNull(VideoMetadataStripper.nextSampleBufferCapacity(VideoMetadataStripper.MAX_SAMPLE_BUFFER_BYTES + 1))
    }
}
