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

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Set as" intent the wallpaper and contact-photo setters answer, and the grant that reaches them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetAsIntentTest {

    private val uri = Uri.parse("content://eu.akoos.photos.share.fileprovider/fullres/a.jpg")

    @Test
    fun `set as is an attach-data intent on the photo with its type and a read grant`() {
        val intent = ShareIntentBuilder.buildSetAsIntent(uri, "image/jpeg")

        assertEquals(Intent.ACTION_ATTACH_DATA, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("image/jpeg", intent.type)
        assertEquals("image/jpeg", intent.getStringExtra("mimeType"))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `the chooser carries the photo and the grant over to the app picked`() {
        val chooser = Intent.createChooser(ShareIntentBuilder.buildSetAsIntent(uri, "image/jpeg"), "Set as")

        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
