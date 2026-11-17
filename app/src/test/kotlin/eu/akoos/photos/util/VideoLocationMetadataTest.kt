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

import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import org.junit.Assert.assertEquals
import org.junit.Test

/** The location a transcoded video is written with: the source's own fix, or none at all when stripped. */
class VideoLocationMetadataTest {

    private val timestamp = Mp4TimestampData(3_900_000_000L, 3_900_000_000L)
    private val fps = MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS, byteArrayOf(0x42, 0xF0.toByte(), 0, 0), MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32,
    )
    private val isoLocation = MdtaMetadataEntry(
        "com.apple.quicktime.location.ISO6709", "+47.4979+019.0402+120.000/".toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING,
    )

    @Test
    fun `the source fix is written when the extractor found none`() {
        val entries = mutableSetOf<Metadata.Entry>(timestamp, fps)

        VideoLocationMetadata.apply(entries, 47.4979 to 19.0402, stripLocation = false)

        assertEquals(setOf(timestamp, fps, Mp4LocationData(47.4979f, 19.0402f)), entries)
    }

    @Test
    fun `the source fix replaces the extractor's location`() {
        val entries = mutableSetOf<Metadata.Entry>(Mp4LocationData(1f, 2f), timestamp)

        VideoLocationMetadata.apply(entries, -33.8688 to 151.2093, stripLocation = false)

        assertEquals(setOf(timestamp, Mp4LocationData(-33.8688f, 151.2093f)), entries)
    }

    @Test
    fun `a source without a fix leaves the entries as they are`() {
        val entries = mutableSetOf<Metadata.Entry>(Mp4LocationData(1f, 2f), isoLocation, timestamp)

        VideoLocationMetadata.apply(entries, null, stripLocation = false)

        assertEquals(setOf(Mp4LocationData(1f, 2f), isoLocation, timestamp), entries)
    }

    @Test
    fun `a stripped location removes every location entry and keeps the rest`() {
        val entries = mutableSetOf<Metadata.Entry>(Mp4LocationData(1f, 2f), isoLocation, timestamp, fps)

        VideoLocationMetadata.apply(entries, 47.4979 to 19.0402, stripLocation = true)

        assertEquals(setOf(timestamp, fps), entries)
    }
}
