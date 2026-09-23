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
import org.junit.Test

/**
 * Pins the device scan's de-duplication: a row a shifting cursor window hands back twice is kept once,
 * in its first place, so no grid ever sees the same uri as two items.
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
        list.dropRepeatedUris()
        assertEquals(
            listOf("media/3", "media/2", "media/1"),
            list.map { it.uri.substringAfter("external/images/") },
        )
    }

    @Test
    fun `an image and a video with the same id are different items`() {
        val list = mutableListOf(
            item("content://media/external/images/media/7", 10),
            item("content://media/external/video/media/7", 10),
        )
        list.dropRepeatedUris()
        assertEquals(2, list.size)
    }

    @Test
    fun `a list without repeats is left as it is`() {
        val list = mutableListOf(item("content://media/external/images/media/1", 1))
        list.dropRepeatedUris()
        assertEquals(1, list.size)
        val empty = mutableListOf<LocalMediaItem>()
        empty.dropRepeatedUris()
        assertEquals(0, empty.size)
    }
}
