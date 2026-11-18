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
import eu.akoos.photos.presentation.gallery.DeviceFolderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsFolderBoxTest {

    private fun local(uri: String, bucket: String?, taken: Long = 1L) =
        LocalMediaItem(uri = uri, dateTaken = taken, displayName = "$uri.jpg", mimeType = "image/jpeg", sizeBytes = 1L, bucketName = bucket)

    private val cloud = CloudPhoto(
        linkId = "l1", shareId = "s", volumeId = "v", captureTime = 1L, displayName = "p.jpg",
        mimeType = "image/jpeg", sizeBytes = 1L, thumbnailUrl = null, revisionId = "r",
    )

    private val listing = listOf(
        local("u1", "Camera", taken = 10),
        local("u2", "Camera", taken = 30),
        local("u3", "Trip", taken = 20),
        local("u4", null),
    )

    @Test
    fun the_current_folder_comes_from_the_listing() {
        val state = detailsFoldersFor("u3", listing, emptyMap())
        assertEquals("Trip", state.current)
        assertEquals(listOf("Camera", "Trip"), state.folders.map { it.name })
        assertEquals("u2", state.folders.first { it.name == "Camera" }.coverUri)
    }

    @Test
    fun a_file_the_listing_misses_or_without_a_folder_has_none() {
        assertNull(detailsFoldersFor("gone", listing, emptyMap()).current)
        assertNull(detailsFoldersFor("u4", listing, emptyMap()).current)
    }

    @Test
    fun a_pinned_cover_counts_only_while_the_folder_holds_it() {
        val pinned = detailsFoldersFor("u1", listing, mapOf("Camera" to "u1"))
        assertEquals("u1", pinned.folders.first { it.name == "Camera" }.coverUri)
        val movedOut = detailsFoldersFor("u1", listing, mapOf("Camera" to "u3"))
        assertEquals("u2", movedOut.folders.first { it.name == "Camera" }.coverUri)
    }

    @Test
    fun after_a_move_the_listing_names_the_new_folder() {
        val moved = listing.map { if (it.uri == "u1") it.copy(bucketName = "Trip") else it }
        val live = detailsFoldersFor("u1", moved, emptyMap())
        val shown = detailsShownFolder("u1", "Camera", live)
        assertEquals("Trip", shown?.name)
        assertEquals(2, shown?.count)
    }

    @Test
    fun until_the_listing_answers_the_item_names_its_folder_with_itself_as_cover() {
        assertEquals(DeviceFolderChoice("Camera", "u1", 0), detailsShownFolder("u1", "Camera", null))
        val other = detailsFoldersFor("u3", listing, emptyMap())
        assertEquals(DeviceFolderChoice("Camera", "u1", 0), detailsShownFolder("u1", "Camera", other))
        assertNull(detailsShownFolder("u1", " ", null))
    }

    @Test
    fun the_page_marks_the_current_folder_and_leaves_it_without_a_tap() {
        val rows = detailsFolderRows(detailsFoldersFor("u3", listing, emptyMap()).folders, "Trip")
        val trip = rows.first { it.key == "Trip" }
        val camera = rows.first { it.key == "Camera" }
        assertEquals(DetailsRowMark.Current, trip.mark)
        assertFalse(trip.tappable)
        assertNull(camera.mark)
        assertTrue(camera.tappable)
    }

    @Test
    fun the_page_rows_carry_the_video_count() {
        val withVideo = listing + local("v1", "Trip").copy(mimeType = "video/mp4")
        val rows = detailsFolderRows(detailsFoldersFor("u1", withVideo, emptyMap()).folders, "Camera")
        val trip = rows.first { it.key == "Trip" }
        assertEquals(2, trip.count)
        assertEquals(1, trip.videoCount)
        assertEquals(0, rows.first { it.key == "Camera" }.videoCount)
    }

    @Test
    fun the_box_is_for_a_guest_with_a_device_file_only() {
        val device = GalleryItem.LocalOnly(local("u1", "Camera"))
        assertTrue(detailsFolderBoxShown(device, isSignedIn = false, trashMode = false, private = false, readOnlyAlbum = false))
        assertFalse(detailsFolderBoxShown(device, isSignedIn = true, trashMode = false, private = false, readOnlyAlbum = false))
        assertFalse(detailsFolderBoxShown(GalleryItem.CloudOnly(cloud), false, false, false, false))
        assertFalse(detailsFolderBoxShown(device, false, trashMode = true, private = false, readOnlyAlbum = false))
        assertFalse(detailsFolderBoxShown(device, false, trashMode = false, private = true, readOnlyAlbum = false))
        assertFalse(detailsFolderBoxShown(device, false, trashMode = false, private = false, readOnlyAlbum = true))
        assertFalse(detailsFolderBoxShown(null, false, false, false, false))
        assertEquals("u1", detailsDeviceUri(GalleryItem.Synced(cloud, local("u1", "Camera"))))
    }
}
