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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Pins that the viewer shows one page per photo and keeps the user on their photo when twins fold. */
class ViewerOnePagePerPhotoTest {

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private val twinUri = "content://media/external/images/media/9"
    private val before = GalleryItem.CloudOnly(cloud("a"))
    private val deviceTwin = GalleryItem.LocalOnly(local(twinUri))
    private val driveTwin = GalleryItem.CloudOnly(cloud("x"))
    private val paired = GalleryItem.Synced(cloud("x"), local(twinUri))
    private val after = GalleryItem.CloudOnly(cloud("b"))

    private val split = listOf(before, deviceTwin, driveTwin, after)
    private val folded = listOf(before, paired, after)

    @Test
    fun `a list with no repeat is handed back as it is`() {
        val items = listOf(before, deviceTwin, driveTwin, after)

        assertSame(items, distinctViewerItems(items))
    }

    @Test
    fun `a Drive-only page of a paired photo gives way to the pair`() {
        val result = distinctViewerItems(listOf(before, driveTwin, paired, after))

        assertEquals(listOf(before, paired, after), result)
    }

    @Test
    fun `a device-only page of a paired photo gives way to the pair`() {
        val result = distinctViewerItems(listOf(deviceTwin, paired))

        assertEquals(listOf(paired), result)
    }

    @Test
    fun `the same photo listed twice keeps its first page`() {
        val result = distinctViewerItems(listOf(driveTwin, before, driveTwin))

        assertEquals(listOf(driveTwin, before), result)
    }

    @Test
    fun `two pairs over one device file keep one page`() {
        val other = GalleryItem.Synced(cloud("y"), local(twinUri))

        val result = distinctViewerItems(listOf(paired, other))

        assertEquals(listOf(paired), result)
    }

    @Test
    fun `a user on the Drive-only twin stays on that photo when the twins fold`() {
        assertEquals(1, pageOfSamePhoto(split, 2, folded))
    }

    @Test
    fun `a user on the device-only twin stays on that photo when the twins fold`() {
        assertEquals(1, pageOfSamePhoto(split, 1, folded))
    }

    @Test
    fun `a user past the folded twins stays on their photo`() {
        assertEquals(2, pageOfSamePhoto(split, 3, folded))
    }

    @Test
    fun `a user before the folded twins keeps their page`() {
        assertEquals(0, pageOfSamePhoto(split, 0, folded))
    }

    @Test
    fun `a deleted photo leaves its page to the next one`() {
        assertEquals(1, pageOfSamePhoto(listOf(before, driveTwin, after), 1, listOf(before, after)))
    }

    @Test
    fun `the last photo deleted moves the page back onto the one before`() {
        assertEquals(0, pageOfSamePhoto(listOf(before, after), 1, listOf(before)))
    }
}
