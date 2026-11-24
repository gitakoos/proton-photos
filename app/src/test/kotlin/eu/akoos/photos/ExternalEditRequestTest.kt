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

package eu.akoos.photos

import android.content.Intent
import android.net.Uri
import eu.akoos.photos.navigation.ExternalEditRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalEditRequestTest {

    private val pkg = "eu.akoos.photos"
    private val photo = Uri.parse("content://media/external/images/media/42")
    private val video = Uri.parse("content://media/external/video/media/7")

    private fun requestOf(intent: Intent, resolvedType: String? = null): ExternalEditRequest? =
        externalEditRequestOf(
            intent = intent,
            ownPackage = pkg,
            typeOf = { resolvedType },
            displayNameOf = { _, isVideo -> if (isVideo) "clip.mp4" else "shot.jpg" },
        )

    @Test
    fun `a camera review of a photo opens it in the viewer`() {
        val intent = Intent(ACTION_REVIEW).setDataAndType(photo, "image/jpeg")

        val request = requestOf(intent)

        assertEquals(
            ExternalEditRequest(
                uri = "content://media/external/images/media/42",
                displayName = "shot.jpg",
                mimeType = "image/jpeg",
                isVideo = false,
                isViewOnly = true,
            ),
            request,
        )
    }

    @Test
    fun `a legacy camera review of a video opens it in the viewer`() {
        val intent = Intent(ACTION_REVIEW_LEGACY).setDataAndType(video, "video/mp4")

        val request = requestOf(intent)

        assertEquals(
            ExternalEditRequest(
                uri = "content://media/external/video/media/7",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                isVideo = true,
                isViewOnly = true,
            ),
            request,
        )
    }

    @Test
    fun `a review with no type takes the type the provider reports`() {
        val intent = Intent(ACTION_REVIEW, photo)

        val request = requestOf(intent, resolvedType = "image/heic")

        assertEquals("image/heic", request?.mimeType)
    }

    @Test
    fun `a review with a type but no uri opens nothing`() {
        val intent = Intent(ACTION_REVIEW).setType("image/jpeg")

        assertNull(requestOf(intent))
    }

    @Test
    fun `a review with neither uri nor type opens nothing`() {
        assertNull(requestOf(Intent(ACTION_REVIEW_LEGACY)))
    }

    @Test
    fun `a review of a file uri is refused`() {
        val intent = Intent(ACTION_REVIEW).setDataAndType(Uri.parse("file:///data/data/eu.akoos.photos/files/a.jpg"), "image/jpeg")

        assertNull(requestOf(intent))
    }

    @Test
    fun `a review of the app's own provider is refused`() {
        val intent = Intent(ACTION_REVIEW)
            .setDataAndType(Uri.parse("content://eu.akoos.photos.share.fileprovider/cache/a.jpg"), "image/jpeg")

        assertNull(requestOf(intent))
    }

    @Test
    fun `a lock screen camera review is not handled`() {
        val intent = Intent("android.provider.action.REVIEW_SECURE").setDataAndType(photo, "image/jpeg")

        assertNull(requestOf(intent))
    }

    @Test
    fun `a review of something that is not a photo or video opens nothing`() {
        val intent = Intent(ACTION_REVIEW).setDataAndType(Uri.parse("content://media/external/file/9"), "application/pdf")

        assertNull(requestOf(intent))
    }

    @Test
    fun `view opens the viewer`() {
        val request = requestOf(Intent(Intent.ACTION_VIEW).setDataAndType(photo, "image/jpeg"))

        assertEquals(true, request?.isViewOnly)
    }

    @Test
    fun `edit opens the editor`() {
        val request = requestOf(Intent(Intent.ACTION_EDIT).setDataAndType(photo, "image/jpeg"))

        assertEquals(false, request?.isViewOnly)
    }

    @Test
    fun `another action opens nothing`() {
        assertNull(requestOf(Intent(Intent.ACTION_SEND).setDataAndType(photo, "image/jpeg")))
    }
}
