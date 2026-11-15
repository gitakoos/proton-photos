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

package eu.akoos.photos.presentation.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CachedThumbnailPresenceTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a missing or empty folder holds no cached thumbnail`() {
        assertFalse(holdsCachedThumbnail(File(temp.root, "absent")))
        assertFalse(holdsCachedThumbnail(temp.newFolder("thumbnails")))
    }

    @Test
    fun `work files and other names do not count`() {
        val dir = temp.newFolder("thumbnails")
        File(dir, "thumb_enc_abc").writeText("x")
        File(dir, "thumb_dec_abc").writeText("x")
        File(dir, "other.jpg").writeText("x")
        File(dir, "thumb_abc.png").writeText("x")

        assertFalse(holdsCachedThumbnail(dir))
    }

    @Test
    fun `one decrypted thumbnail among many files is found`() {
        val dir = temp.newFolder("thumbnails")
        repeat(200) { File(dir, "thumb_enc_$it").writeText("x") }
        File(dir, "thumb_link-1.jpg").writeText("x")

        assertTrue(holdsCachedThumbnail(dir))
    }
}
