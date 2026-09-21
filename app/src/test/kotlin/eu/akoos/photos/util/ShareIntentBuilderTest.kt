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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the MIME the share sheet is opened with, the single value that decides which apps a user is
 * offered for a selection.
 *
 * It is a narrowing, and narrowing is where it can go wrong in both directions: too wide and the
 * chooser lists apps that cannot take the files at all, too narrow and an app that would have
 * accepted the batch is simply missing. Six surfaces share this one answer, so it is pinned here
 * rather than at any of them.
 */
class ShareIntentBuilderTest {

    private fun localItem(mime: String, name: String = "file") = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = "content://media/$name", dateTaken = 1_700_000_000_000L, displayName = name,
            mimeType = mime, sizeBytes = 1L, bucketName = null,
        ),
    )

    private fun cloudPhoto(mime: String, name: String = "file") = CloudPhoto(
        linkId = "link-$name", shareId = "share", volumeId = "vol", captureTime = 1_700_000_000L,
        displayName = name, mimeType = mime, sizeBytes = 1L, thumbnailUrl = null, revisionId = "rev",
    )

    private fun cloudItem(mime: String, name: String = "file") = GalleryItem.CloudOnly(cloudPhoto(mime, name))

    /** A backed-up photo carries a mime on both halves; the share MIME is read off the device copy. */
    private fun syncedItem(localMime: String, cloudMime: String, name: String = "file") = GalleryItem.Synced(
        cloud = cloudPhoto(cloudMime, name),
        local = LocalMediaItem(
            uri = "content://media/$name", dateTaken = 1_700_000_000_000L, displayName = name,
            mimeType = localMime, sizeBytes = 1L, bucketName = null,
        ),
    )

    // ── the raw MIME list ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a selection of photos narrows to the image wildcard`() {
        assertEquals("image/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg")))
        assertEquals("image/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg", "image/png", "image/heic")))
    }

    @Test
    fun `a selection of videos narrows to the video wildcard`() {
        assertEquals("video/*", ShareIntentBuilder.shareableMimeOf(listOf("video/mp4")))
        assertEquals("video/*", ShareIntentBuilder.shareableMimeOf(listOf("video/mp4", "video/quicktime")))
    }

    @Test
    fun `one video in a batch of photos opens the whole chooser`() {
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg", "video/mp4")))
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("video/mp4", "image/jpeg")))
    }

    @Test
    fun `an empty selection opens the whole chooser rather than crashing on the first item`() {
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(emptyList()))
    }

    @Test
    fun `a missing mime widens the whole selection`() {
        // A blank mime is neither an image nor a video, so a single unmeasured file costs the batch
        // its narrow chooser rather than being quietly treated as one of the others.
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg", "")))
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("")))
    }

    @Test
    fun `the prefix is matched exactly, so an upper-case mime widens the selection`() {
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("IMAGE/JPEG")))
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg", "Video/mp4")))
    }

    @Test
    fun `a type that is neither an image nor a video widens the selection`() {
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("application/octet-stream")))
        assertEquals("*/*", ShareIntentBuilder.shareableMimeOf(listOf("image/jpeg", "application/pdf")))
    }

    @Test
    fun `an unusual image subtype is still an image`() {
        assertEquals("image/*", ShareIntentBuilder.shareableMimeOf(listOf("image/x-adobe-dng", "image/avif")))
    }

    // ── the same answer from a gallery selection ────────────────────────────────────────────────

    @Test
    fun `a single photo of any sync state shares as an image`() {
        assertEquals("image/*", ShareIntentBuilder.shareableMime(listOf(localItem("image/jpeg"))))
        assertEquals("image/*", ShareIntentBuilder.shareableMime(listOf(cloudItem("image/jpeg"))))
        assertEquals("image/*", ShareIntentBuilder.shareableMime(listOf(syncedItem("image/jpeg", "image/jpeg"))))
    }

    @Test
    fun `a mixed-state selection of photos still narrows to the image wildcard`() {
        val items = listOf(
            localItem("image/jpeg", "a"),
            cloudItem("image/png", "b"),
            syncedItem("image/heic", "image/heic", "c"),
        )
        assertEquals("image/*", ShareIntentBuilder.shareableMime(items))
    }

    @Test
    fun `a photo and a video selected together open the whole chooser`() {
        assertEquals(
            "*/*",
            ShareIntentBuilder.shareableMime(listOf(localItem("image/jpeg", "a"), cloudItem("video/mp4", "b"))),
        )
    }

    @Test
    fun `a backed-up item is judged by its device copy's mime, not the cloud one`() {
        // The Synced branch reads the local twin, so a device copy with no mime widens a selection
        // whose cloud half would have narrowed it.
        assertEquals("*/*", ShareIntentBuilder.shareableMime(listOf(syncedItem("", "image/jpeg"))))
        assertEquals("video/*", ShareIntentBuilder.shareableMime(listOf(syncedItem("video/mp4", "image/jpeg"))))
    }

    @Test
    fun `an empty gallery selection opens the whole chooser`() {
        assertEquals("*/*", ShareIntentBuilder.shareableMime(emptyList()))
    }
}
