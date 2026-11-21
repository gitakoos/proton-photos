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

package eu.akoos.photos.presentation.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which clip of a joined video gives the output its location: the first one in play order, as in Media3. */
class VideoConcatLocationTest {

    private val primary = VideoSource(id = PRIMARY_SOURCE_ID, uri = "content://media/primary", durationMs = 10_000L)
    private val added = VideoSource(id = PRIMARY_SOURCE_ID + 1, uri = "content://media/added", durationMs = 8_000L)

    @Test
    fun `the first clip in play order gives the location`() {
        val clips = listOf(
            VideoClip(id = 2L, startMs = 0L, endMs = 3_000L, sourceId = added.id),
            VideoClip(id = 1L, startMs = 0L, endMs = 5_000L, sourceId = primary.id),
        )

        assertEquals("content://media/added", locationSourceUri(clips, listOf(primary, added)))
    }

    @Test
    fun `a reorder moves the location to the clip now played first`() {
        val clips = listOf(
            VideoClip(id = 1L, startMs = 0L, endMs = 5_000L, sourceId = primary.id),
            VideoClip(id = 2L, startMs = 0L, endMs = 3_000L, sourceId = added.id),
        )

        assertEquals("content://media/primary", locationSourceUri(clips, listOf(primary, added)))
    }

    @Test
    fun `nothing to join gives no location source`() {
        assertNull(locationSourceUri(emptyList(), listOf(primary, added)))
        assertNull(locationSourceUri(listOf(VideoClip(id = 1L, startMs = 0L, endMs = 1_000L)), emptyList()))
    }
}
