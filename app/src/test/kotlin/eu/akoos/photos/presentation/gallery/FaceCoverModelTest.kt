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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.data.repository.drive.evictionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A face tile whose cloud cover has no decrypted thumbnail asks for one instead of drawing an empty
 * circle, and a cover it asked for is never what a cache trim deletes.
 */
class FaceCoverModelTest {

    private val linkId = "abcDEF123-_=="
    private val deviceUri = "content://media/external/images/media/42"

    @Test
    fun `a cloud cover without a thumbnail is asked for, one with a thumbnail is not`() {
        assertEquals(linkId, coverKeyToRequest(linkId, emptyMap()))
        assertNull(coverKeyToRequest(linkId, mapOf(linkId to "file:///cache/thumbnails/thumb_x.jpg")))
    }

    @Test
    fun `a device photo and a missing cover never ask the cloud`() {
        assertNull(coverKeyToRequest(deviceUri, emptyMap()))
        assertNull(coverKeyToRequest(null, emptyMap()))
        assertTrue(isCloudPhotoKey(linkId))
        assertFalse(isCloudPhotoKey(deviceUri))
        assertFalse(isCloudPhotoKey("file:///data/vault/a.jpg"))
    }

    private data class Cached(val linkId: String, val mtime: Long)

    @Test
    fun `a trim deletes the oldest thumbnails first and never a pinned cover`() {
        val cached = listOf(Cached("faceCover", 1), Cached("new", 30), Cached("old", 10), Cached("albumCover", 5))
        val order = evictionOrder(cached, setOf("faceCover", "albumCover"), { it.linkId }, { it.mtime })
        assertEquals(listOf("old", "new"), order.map { it.linkId })
    }
}
