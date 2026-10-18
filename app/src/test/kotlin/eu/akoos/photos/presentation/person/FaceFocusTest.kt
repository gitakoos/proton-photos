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

package eu.akoos.photos.presentation.person

import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.presentation.gallery.cropAlignmentFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins where a person's tile is centred: on the middle of their clearest face on the photo, and on the
 * middle of the photo (no focus) when the stored box cannot be read as fractions.
 */
class FaceFocusTest {

    private fun face(photo: String, l: Float, t: Float, r: Float, b: Float, score: Float = 0.9f) =
        FaceOnPhoto(id = "$photo#$l", photoKey = photo, personId = 1L, boxLeft = l, boxTop = t, boxRight = r, boxBottom = b, score = score)

    @Test
    fun `a photo is centred on the middle of the person's face`() {
        val focus = faceFocusByPhoto(listOf(face("a", 0.1f, 0.2f, 0.3f, 0.6f)))
        assertEquals(0.2f, focus["a"]!!.x, 1e-5f)
        assertEquals(0.4f, focus["a"]!!.y, 1e-5f)
    }

    @Test
    fun `the clearest face wins when the person is on a photo twice`() {
        val focus = faceFocusByPhoto(
            listOf(
                face("a", 0.0f, 0.0f, 0.2f, 0.2f, score = 0.6f),
                face("a", 0.6f, 0.4f, 0.8f, 0.6f, score = 0.95f),
            ),
        )
        assertEquals(0.7f, focus["a"]!!.x, 1e-5f)
        assertEquals(0.5f, focus["a"]!!.y, 1e-5f)
    }

    @Test
    fun `a pixel box or an empty one gives no focus`() {
        val focus = faceFocusByPhoto(
            listOf(
                face("pixels", 120f, 80f, 340f, 300f),
                face("empty", 0.4f, 0.4f, 0.4f, 0.4f),
            ),
        )
        assertNull(focus["pixels"])
        assertNull(focus["empty"])
    }

    @Test
    fun `no focus keeps the centred crop and a focus becomes a bias`() {
        assertEquals(Alignment.Center, cropAlignmentFor(null))
        assertEquals(Alignment.Center, cropAlignmentFor(Offset(1.5f, 0.5f)))
        val bias = cropAlignmentFor(Offset(0.2f, 0.5f)) as BiasAlignment
        assertEquals(-0.6f, bias.horizontalBias, 1e-5f)
        assertEquals(0.0f, bias.verticalBias, 1e-5f)
    }
}
