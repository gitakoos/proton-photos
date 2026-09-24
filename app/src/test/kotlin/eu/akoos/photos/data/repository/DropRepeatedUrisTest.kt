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

package eu.akoos.photos.data.repository

import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device scan's de-duplication: a row a shifting cursor window hands back twice is kept once,
 * in its first place, so no grid ever sees the same uri as two items, and the scan learns that it
 * dropped one, since the same shift left rows unread.
 */
class DropRepeatedUrisTest {

    private fun item(uri: String, dateTaken: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = "Camera",
    )

    @Test
    fun `a row read twice is kept once, in its first place`() {
        val list = mutableListOf(
            item("content://media/external/images/media/3", 300),
            item("content://media/external/images/media/2", 200),
            item("content://media/external/images/media/2", 200),
            item("content://media/external/images/media/1", 100),
            item("content://media/external/images/media/3", 300),
        )
        val dropped = list.dropRepeatedUris()
        assertEquals(
            listOf("media/3", "media/2", "media/1"),
            list.map { it.uri.substringAfter("external/images/") },
        )
        assertTrue("a repeat marks the read as torn, so the scan must not count as complete", dropped)
    }

    @Test
    fun `an image and a video with the same id are different items`() {
        val list = mutableListOf(
            item("content://media/external/images/media/7", 10),
            item("content://media/external/video/media/7", 10),
        )
        assertFalse(list.dropRepeatedUris())
        assertEquals(2, list.size)
    }

    @Test
    fun `a list without repeats is left as it is`() {
        val list = mutableListOf(
            item("content://media/external/images/media/2", 2),
            item("content://media/external/images/media/1", 1),
        )
        assertFalse(list.dropRepeatedUris())
        assertEquals(2, list.size)
        val single = mutableListOf(item("content://media/external/images/media/1", 1))
        assertFalse(single.dropRepeatedUris())
        assertEquals(1, single.size)
        val empty = mutableListOf<LocalMediaItem>()
        assertFalse(empty.dropRepeatedUris())
        assertEquals(0, empty.size)
    }
}
