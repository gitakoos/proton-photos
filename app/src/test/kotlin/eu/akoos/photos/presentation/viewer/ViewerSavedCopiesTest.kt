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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.editor.SavedEditCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins how an open viewer takes in a copy the editor saved from one of its photos: the copy goes in
 * right after its source, and the viewer pages to it only while the user is still on the source.
 */
class ViewerSavedCopiesTest {

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "$linkId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(uri: String, name: String = "IMG_0001.jpg") = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private val a = GalleryItem.CloudOnly(cloud("a"))
    private val b = GalleryItem.CloudOnly(cloud("b"))
    private val c = GalleryItem.CloudOnly(cloud("c"))

    @Test
    fun `a Drive copy in the timeline goes right after its source`() {
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val saved = SavedEditCopy(1, setOf("b"), "b_edit", copyIsLocal = false)

        val after = insertSavedCopies(listOf(a, b, c), listOf(saved), live = listOf(a, b, copy, c))

        assertEquals(listOf(a, b, copy, c), after)
    }

    @Test
    fun `a Drive copy not in the timeline yet leaves the list as it was`() {
        val snapshot = listOf(a, b, c)
        val saved = SavedEditCopy(1, setOf("b"), "b_edit", copyIsLocal = false)

        assertSame(snapshot, insertSavedCopies(snapshot, listOf(saved), live = listOf(a, b, c)))
    }

    @Test
    fun `a copy of a photo outside the list is not added`() {
        val snapshot = listOf(a, c)
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val saved = SavedEditCopy(1, setOf("b"), "b_edit", copyIsLocal = false)

        assertSame(snapshot, insertSavedCopies(snapshot, listOf(saved), live = listOf(a, b, copy, c)))
    }

    @Test
    fun `a copy already in the list is not added twice`() {
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val snapshot = listOf(a, b, copy)
        val saved = SavedEditCopy(1, setOf("b"), "b_edit", copyIsLocal = false)

        assertSame(snapshot, insertSavedCopies(snapshot, listOf(saved), live = listOf(a, b, copy)))
    }

    @Test
    fun `a device copy the timeline has not listed shows under its own uri, name and type`() {
        val source = GalleryItem.Synced(cloud("s"), local("content://media/external/images/media/1"))
        val saved = SavedEditCopy(
            1, setOf("content://media/external/images/media/1", "s"),
            "content://media/external/images/media/2", copyIsLocal = true,
            copyName = "IMG_0001_edit_20261120_120000.png", copyMimeType = "image/png",
        )

        val after = insertSavedCopies(listOf(a, source), listOf(saved), live = emptyList())

        val expected = GalleryItem.LocalOnly(
            local("content://media/external/images/media/2", "IMG_0001_edit_20261120_120000.png")
                .copy(mimeType = "image/png"),
        )
        assertEquals(listOf(a, source, expected), after)
    }

    @Test
    fun `a backed up source is found by its device uri as well as its Drive link`() {
        val source = GalleryItem.Synced(cloud("s"), local("content://media/external/images/media/1"))
        val copy = GalleryItem.LocalOnly(local("content://media/external/images/media/2"))
        val saved = SavedEditCopy(
            1, setOf("content://media/external/images/media/1"),
            "content://media/external/images/media/2", copyIsLocal = true,
        )

        assertEquals(listOf(source, copy), insertSavedCopies(listOf(source), listOf(saved), live = listOf(copy)))
    }

    @Test
    fun `a Drive-only source with a kept device copy waits for the timeline`() {
        val snapshot = listOf(b)
        val saved = SavedEditCopy(1, setOf("b"), "content://media/external/images/media/9", copyIsLocal = true)

        assertSame(snapshot, insertSavedCopies(snapshot, listOf(saved), live = emptyList()))
    }

    @Test
    fun `no copies leave the list untouched`() {
        val snapshot = listOf(a, b)

        assertSame(snapshot, insertSavedCopies(snapshot, emptyList(), live = listOf(a, b)))
    }

    @Test
    fun `the viewer pages to the copy while the user is still on its source`() {
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val saved = SavedEditCopy(7, setOf("b"), "b_edit", copyIsLocal = false)

        val landing = savedCopyLanding(listOf(a, b, copy, c), listOf(saved), seen = emptySet(), settledPage = 1)

        assertEquals(SavedCopyLanding(seen = setOf(7L), goTo = 2), landing)
    }

    @Test
    fun `a copy that arrives after the user paged away is only marked as seen`() {
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val saved = SavedEditCopy(7, setOf("b"), "b_edit", copyIsLocal = false)

        val landing = savedCopyLanding(listOf(a, b, copy, c), listOf(saved), seen = emptySet(), settledPage = 3)

        assertEquals(SavedCopyLanding(seen = setOf(7L), goTo = null), landing)
    }

    @Test
    fun `a copy already seen is not paged to again`() {
        val copy = GalleryItem.CloudOnly(cloud("b_edit"))
        val saved = SavedEditCopy(7, setOf("b"), "b_edit", copyIsLocal = false)

        assertNull(savedCopyLanding(listOf(a, b, copy), listOf(saved), seen = setOf(7L), settledPage = 1))
    }

    @Test
    fun `nothing happens while the copy is not in the list yet`() {
        val saved = SavedEditCopy(7, setOf("b"), "b_edit", copyIsLocal = false)

        assertNull(savedCopyLanding(listOf(a, b, c), listOf(saved), seen = emptySet(), settledPage = 1))
    }

    @Test
    fun `of two copies of the same photo the newer one is paged to`() {
        val first = GalleryItem.CloudOnly(cloud("b_edit1"))
        val second = GalleryItem.CloudOnly(cloud("b_edit2"))
        val copies = listOf(
            SavedEditCopy(3, setOf("b"), "b_edit1", copyIsLocal = false),
            SavedEditCopy(4, setOf("b"), "b_edit2", copyIsLocal = false),
        )

        val landing = savedCopyLanding(listOf(b, second, first), copies, seen = emptySet(), settledPage = 0)

        assertEquals(SavedCopyLanding(seen = setOf(3L, 4L), goTo = 1), landing)
    }
}
