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

package eu.akoos.photos.presentation.common

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the snackbar line the place and search selections show after an album add, resolved against
 * the real strings: a photo that is only queued to upload never reads as added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddToAlbumResultTextTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `photos that were only queued read as joining after upload, never as added`() {
        val text = addToAlbumResultText(res, joined = 0, queued = 3, albumName = "Trip")

        assertEquals(res.getString(R.string.gallery_add_to_album_queued), text)
        assertNotEquals(res.getString(R.string.gallery_added_to_album, 0, "Trip"), text)
    }

    @Test
    fun `a mix of joined and queued photos names the ones still uploading`() {
        assertEquals(
            res.getQuantityString(R.plurals.gallery_add_to_album_some_queued, 2, 2),
            addToAlbumResultText(res, joined = 4, queued = 2, albumName = "Trip"),
        )
    }

    @Test
    fun `photos that all joined name the count and the album`() {
        assertEquals(
            res.getString(R.string.gallery_added_to_album, 4, "Trip"),
            addToAlbumResultText(res, joined = 4, queued = 0, albumName = "Trip"),
        )
    }

    @Test
    fun `nothing joined and nothing queued says there was nothing to add`() {
        assertEquals(
            res.getString(R.string.album_picker_empty),
            addToAlbumResultText(res, joined = 0, queued = 0, albumName = "Trip"),
        )
    }
}
