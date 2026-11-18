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

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoVideoCountTest {

    private fun join(photos: Int, videos: Int) =
        joinPhotoVideoCounts(photos, videos, "$photos photos", "$videos videos")

    @Test
    fun photos_and_videos_are_shown_apart() {
        assertEquals("8 photos, 1 videos", join(8, 1))
    }

    @Test
    fun only_videos_leaves_the_photo_count_out() {
        assertEquals("3 videos", join(0, 3))
    }

    @Test
    fun no_videos_shows_the_photo_count_alone() {
        assertEquals("9 photos", join(9, 0))
        assertEquals("0 photos", join(0, 0))
    }
}
