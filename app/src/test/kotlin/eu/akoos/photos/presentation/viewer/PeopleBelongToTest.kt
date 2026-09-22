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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [peopleBelongTo], the check that keeps the details panel's People box on the settled photo: a
 * people list read for one photo must never show under another one after a quick swipe.
 */
class PeopleBelongToTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 100L,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    @Test
    fun `nothing loaded yet or no photo matches nothing`() {
        assertFalse(peopleBelongTo(null, GalleryItem.LocalOnly(local("content://media/1"))))
        assertFalse(peopleBelongTo("content://media/1", null))
    }

    @Test
    fun `a list read for the photo on screen belongs to it`() {
        assertTrue(peopleBelongTo("content://media/1", GalleryItem.LocalOnly(local("content://media/1"))))
        assertTrue(peopleBelongTo("link-a", GalleryItem.CloudOnly(cloud("link-a"))))
        assertTrue(peopleBelongTo("link-a", GalleryItem.Synced(cloud("link-a"), local("content://media/1"))))
    }

    @Test
    fun `a list read for another photo does not`() {
        assertFalse(peopleBelongTo("content://media/2", GalleryItem.LocalOnly(local("content://media/1"))))
        assertFalse(peopleBelongTo("link-b", GalleryItem.CloudOnly(cloud("link-a"))))
    }

    @Test
    fun `a photo that finished backing up keeps the list read under its device uri`() {
        val synced = GalleryItem.Synced(cloud("link-a"), local("content://media/1"))
        assertTrue(peopleBelongTo("content://media/1", synced))
        assertFalse(peopleBelongTo("content://media/9", synced))
    }

    @Test
    fun `a cloud-only photo never matches a device uri`() {
        assertFalse(peopleBelongTo("content://media/1", GalleryItem.CloudOnly(cloud("link-a"))))
    }
}
