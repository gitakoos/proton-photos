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

package eu.akoos.photos.presentation.picker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.widget.WidgetHiddenSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a pick from another app takes from its request, which picks may go back to it and in what form,
 * and the switch that keeps the app out of every chooser until the user turns it on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickRequestTest {

    @Test
    fun `a request for photos takes photos and never a video or a document`() {
        val photos = pickRequestOf("image/*", null, allowMultiple = false)

        assertTrue(photos.accepts("image/jpeg"))
        assertTrue(photos.accepts("IMAGE/HEIC"))
        assertFalse(photos.accepts("video/mp4"))
        assertFalse(photos.accepts("application/pdf"))
    }

    @Test
    fun `a request that names no type takes photos and videos and nothing else`() {
        val any = pickRequestOf(null, null, allowMultiple = false)

        assertTrue(any.accepts("image/png"))
        assertTrue(any.accepts("video/quicktime"))
        assertFalse(any.accepts("application/zip"))
        assertTrue(pickRequestOf("*/*", null, allowMultiple = false).accepts("video/mp4"))
        assertFalse(pickRequestOf("*/*", null, allowMultiple = false).accepts("text/plain"))
    }

    @Test
    fun `the mime list narrows the type and an exact type takes only itself`() {
        val request = pickRequestOf("*/*", arrayOf("image/png", "video/*"), allowMultiple = false)

        assertTrue(request.accepts("image/png"))
        assertFalse(request.accepts("image/jpeg"))
        assertTrue(request.accepts("video/mp4"))
    }

    @Test
    fun `a pick on a media collection reads as photos or videos`() {
        assertTrue(pickRequestOf("vnd.android.cursor.dir/image", null, false).accepts("image/jpeg"))
        assertFalse(pickRequestOf("vnd.android.cursor.dir/image", null, false).accepts("video/mp4"))
        assertTrue(pickRequestOf("vnd.android.cursor.dir/video", null, false).accepts("video/mp4"))
    }

    @Test
    fun `the request is read off the intent, allow multiple included`() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val request = pickRequestOf(intent)

        assertEquals(listOf("image/*"), request.mimeTypes)
        assertTrue(request.allowMultiple)
        assertFalse(pickRequestOf(Intent(Intent.ACTION_PICK)).allowMultiple)
    }

    @Test
    fun `the answer carries every pick with a read grant and the first as its data`() {
        val first = Uri.parse("content://media/external/images/media/1")
        val second = Uri.parse("content://eu.akoos.photos.share.fileprovider/fullres/a.jpg")

        val result = pickResultIntent(listOf(first, second), listOf("image/jpeg", "image/jpeg"))

        assertEquals(first, result.data)
        assertEquals(2, result.clipData!!.itemCount)
        assertEquals(second, result.clipData!!.getItemAt(1).uri)
        assertTrue(result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `the picker stays out of every chooser until it is turned on, and goes again when turned off`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(ExternalPicker.isEnabled(context))
        ExternalPicker.setEnabled(context, true)
        assertTrue(ExternalPicker.isEnabled(context))
        ExternalPicker.setEnabled(context, false)
        assertFalse(ExternalPicker.isEnabled(context))
    }

    @Test
    fun `only get-content and pick ask for a photo`() {
        assertTrue(isPickAction(Intent.ACTION_GET_CONTENT))
        assertTrue(isPickAction(Intent.ACTION_PICK))
        assertFalse(isPickAction(Intent.ACTION_VIEW))
        assertFalse(isPickAction(null))
    }

    private fun local(name: String, mime: String = "image/jpeg", bucket: String? = "Camera") = LocalMediaItem(
        uri = "content://media/$name", dateTaken = 1L, displayName = name, mimeType = mime, sizeBytes = 1L,
        bucketName = bucket,
    )

    private fun cloud(linkId: String, mime: String = "image/jpeg") = CloudPhoto(
        linkId = linkId, shareId = "share", volumeId = "vol", captureTime = 1L, displayName = "$linkId.jpg",
        mimeType = mime, sizeBytes = 1L, thumbnailUrl = null, revisionId = "rev",
    )

    private val many = pickRequestOf("image/*", null, allowMultiple = true)

    @Test
    fun `a photo put away never goes out, by its drive copy, its folder, its twin or the vault`() {
        val picks = listOf(
            GalleryItem.LocalOnly(local("ok")),
            GalleryItem.CloudOnly(cloud("hidden-cloud")),
            GalleryItem.Synced(cloud("hidden-twin-copy"), local("synced")),
            GalleryItem.LocalOnly(local("in-hidden-folder", bucket = "Private")),
            GalleryItem.LocalOnly(local("twin")),
            GalleryItem.LocalOnly(local("vaulted")),
            GalleryItem.CloudOnly(cloud("fine")),
        )
        val hidden = WidgetHiddenSets(cloudIds = setOf("hidden-cloud", "hidden-twin-copy"), folderNames = setOf("Private"))

        val out = handoverItems(many, picks, hidden, setOf("content://media/twin")) { it == "content://media/vaulted" }

        assertEquals(listOf(picks[0], picks[6]), out)
    }

    @Test
    fun `only the kinds asked for go out, and one photo when the request takes one`() {
        val picks = listOf(
            GalleryItem.CloudOnly(cloud("clip", mime = "video/mp4")),
            GalleryItem.LocalOnly(local("first")),
            GalleryItem.LocalOnly(local("second")),
        )

        assertEquals(picks.drop(1), handoverItems(many, picks, WidgetHiddenSets(), emptySet()) { false })
        val one = pickRequestOf("image/*", null, allowMultiple = false)
        assertEquals(listOf(picks[1]), handoverItems(one, picks, WidgetHiddenSets(), emptySet()) { false })
    }

    @Test
    fun `when nothing can go, the reason is the kind asked for, or that the picks are hidden`() {
        val video = GalleryItem.CloudOnly(cloud("clip", mime = "video/mp4"))
        val photo = GalleryItem.LocalOnly(local("p"))

        assertEquals(PickAnswer.WrongKind(videosOnly = false), nothingToHandOver(many, listOf(video)))
        assertEquals(
            PickAnswer.WrongKind(videosOnly = true),
            nothingToHandOver(pickRequestOf("video/*", null, true), listOf(photo)),
        )
        assertEquals(PickAnswer.OnlyHidden, nothingToHandOver(many, listOf(photo, video)))
    }

    @Test
    fun `a single-photo pick keeps the photo just tapped, and nothing more`() {
        val a = GalleryItem.LocalOnly(local("a"))
        val b = GalleryItem.LocalOnly(local("b"))

        assertEquals(setOf(b), eu.akoos.photos.presentation.gallery.singlePickOf(setOf(a, b), setOf(a)))
        assertEquals(setOf(a), eu.akoos.photos.presentation.gallery.singlePickOf(setOf(a), emptySet()))
        assertEquals(emptySet<GalleryItem>(), eu.akoos.photos.presentation.gallery.singlePickOf(emptySet(), setOf(a)))
    }
}
