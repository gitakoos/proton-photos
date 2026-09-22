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

package eu.akoos.photos.navigation

import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A closing viewer's own copy of its inputs drops the same flags its exit resets in nav scope, and keeps
 * the photos and the album it was opened with. Pure, no Android runtime.
 */
class ViewerInputsTest {

    private fun photo(uri: String) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = 0L,
            displayName = uri,
            mimeType = "image/jpeg",
            sizeBytes = 0L,
            bucketName = null,
        ),
    )

    private val album = Album(
        linkId = "album",
        name = "Album",
        photoCount = 2,
        coverLinkId = null,
        lastActivityTimeMs = null,
    )

    private val opened = ViewerInputs(
        items = listOf(photo("content://a"), photo("content://b")),
        initialIndex = 1,
        hiddenCloudLinkIds = setOf("hidden"),
        fromAlbum = true,
        album = album,
        trashMode = true,
        secure = true,
        autoplay = true,
    )

    @Test
    fun `closing drops the secure window, the slideshow and trash mode`() {
        val closed = opened.closed()
        assertFalse(closed.secure)
        assertFalse(closed.autoplay)
        assertFalse(closed.trashMode)
    }

    @Test
    fun `closing keeps the photos and the album the viewer was opened with`() {
        val closed = opened.closed()
        assertSame(opened.items, closed.items)
        assertEquals(1, closed.initialIndex)
        assertEquals(setOf("hidden"), closed.hiddenCloudLinkIds)
        assertTrue(closed.fromAlbum)
        assertSame(album, closed.album)
    }

    @Test
    fun `closing a closed viewer again changes nothing`() {
        val closed = opened.closed()
        assertEquals(closed, closed.closed())
    }
}
