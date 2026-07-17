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

package eu.akoos.photos.presentation.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the editor's Overwrite-to-Copy coercion. The important case is a Motion Photo: its container is
 * a writable JPEG, so the format check alone would overwrite it in place and truncate the appended
 * video, destroying the motion. This decision forces a Copy instead, verified without the app.
 */
class PhotoEditorOverwriteCoerceTest {

    @Test
    fun a_plain_writable_photo_overwrites_in_place() {
        assertFalse(overwriteCoercesToCopy(overwritable = true, isMotionPhoto = false))
    }

    @Test
    fun a_motion_photo_is_coerced_to_a_copy_even_though_its_format_is_writable() {
        // The regression guard: JPEG is writable, but a Motion Photo must never be overwritten in place.
        assertTrue(overwriteCoercesToCopy(overwritable = true, isMotionPhoto = true))
    }

    @Test
    fun an_unwritable_format_is_coerced_to_a_copy() {
        assertTrue(overwriteCoercesToCopy(overwritable = false, isMotionPhoto = false))
    }

    @Test
    fun an_unwritable_motion_photo_is_still_coerced_to_a_copy() {
        assertTrue(overwriteCoercesToCopy(overwritable = false, isMotionPhoto = true))
    }
}
