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

import androidx.exifinterface.media.ExifInterface
import eu.akoos.photos.util.DisplayOrientation
import eu.akoos.photos.util.FitPoint
import eu.akoos.photos.util.orient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two value decisions inside the viewer's pixel source: how far a decode is subsampled,
 * and which turn an EXIF tag actually stands for.
 *
 * The subsample is the memory guard — the viewer already holds a full-res frame plus its cached
 * neighbours when this runs, so a second unbounded decode of a 50 MP photo is what takes the heap
 * down. The orientation is a correctness guard, because `BitmapFactory` ignores the tag while the
 * displayed image honours it, and any mismatch shifts every coordinate a caller derives.
 */
class ViewerPixelsTest {

    // ─── subsampling ────────────────────────────────────────────────────────

    @Test
    fun `an image already within the cap is not subsampled`() {
        assertEquals(1, sampleSizeFor(4000, 3000, ViewerPixels.MAX_DIM))
        assertEquals(1, sampleSizeFor(ViewerPixels.MAX_DIM, ViewerPixels.MAX_DIM, ViewerPixels.MAX_DIM))
    }

    @Test
    fun `the cap applies to the longest edge whichever it is`() {
        assertEquals(2, sampleSizeFor(8000, 100, ViewerPixels.MAX_DIM))
        assertEquals(2, sampleSizeFor(100, 8000, ViewerPixels.MAX_DIM))
    }

    @Test
    fun `the subsample doubles until the longest edge fits`() {
        assertEquals(2, sampleSizeFor(4097, 3000, ViewerPixels.MAX_DIM))
        assertEquals(4, sampleSizeFor(16385, 3000, ViewerPixels.MAX_DIM))
        // A 108 MP phone sensor: 12000x9000 comes down to 3000x2250.
        assertEquals(4, sampleSizeFor(12000, 9000, ViewerPixels.MAX_DIM))
    }

    @Test
    fun `an unmeasured source asks for no subsample`() {
        assertEquals(1, sampleSizeFor(0, 0, ViewerPixels.MAX_DIM))
        assertEquals(1, sampleSizeFor(-1, -1, ViewerPixels.MAX_DIM))
    }

    @Test
    fun `a nonsensical cap does not spin`() {
        assertEquals(1, sampleSizeFor(12000, 9000, 0))
        assertEquals(1, sampleSizeFor(12000, 9000, -4096))
    }

    // ─── orientation decomposition ──────────────────────────────────────────

    @Test
    fun `an absent or normal tag means the frame is shown as decoded`() {
        assertEquals(DisplayOrientation.None, displayOrientationOf(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(DisplayOrientation.None, displayOrientationOf(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(DisplayOrientation.None, displayOrientationOf(9999))
    }

    @Test
    fun `the three plain rotations carry no mirror`() {
        assertEquals(DisplayOrientation(90, false), displayOrientationOf(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(DisplayOrientation(180, false), displayOrientationOf(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(DisplayOrientation(270, false), displayOrientationOf(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `the four mirrored tags decompose to a mirror plus a turn`() {
        assertEquals(DisplayOrientation(0, true), displayOrientationOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(DisplayOrientation(180, true), displayOrientationOf(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(DisplayOrientation(270, true), displayOrientationOf(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(DisplayOrientation(90, true), displayOrientationOf(ExifInterface.ORIENTATION_TRANSVERSE))
    }

    @Test
    fun `a vertical flip really lands where the tag says`() {
        // The decomposition is mirror-then-rotate, so the vertical flip has to come out of a
        // horizontal mirror plus a half turn rather than looking like one directly.
        val o = displayOrientationOf(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        val w = 400f
        val h = 300f
        assertEquals(FitPoint(0f, h), o.orient(FitPoint(0f, 0f), w, h))
        assertEquals(FitPoint(w, 0f), o.orient(FitPoint(w, h), w, h))
        assertEquals(FitPoint(50f, h - 20f), o.orient(FitPoint(50f, 20f), w, h))
    }

    @Test
    fun `a quarter turn swaps the axes and a half turn does not`() {
        assertTrue(displayOrientationOf(ExifInterface.ORIENTATION_ROTATE_90).swapsAxes)
        assertTrue(displayOrientationOf(ExifInterface.ORIENTATION_TRANSPOSE).swapsAxes)
        assertEquals(false, displayOrientationOf(ExifInterface.ORIENTATION_ROTATE_180).swapsAxes)
        assertEquals(false, displayOrientationOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL).swapsAxes)
    }
}
