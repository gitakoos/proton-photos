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

import eu.akoos.photos.util.PhotoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which side of a photo each details-sheet row is sourced from, for the three rows that can be
 * answered by more than one side: the coordinates, a video's length, and how long any of it may stay
 * on screen.
 *
 * Altitude is deliberately absent. Only a file's own EXIF ever carries a height, so the sheet reads it
 * straight off the EXIF and there is no choice to pin. Plain JVM assertions, no Android.
 */
class DetailsSheetSourcesTest {

    private val exifFix = PhotoMetadata(gpsLatitude = 47.4979, gpsLongitude = 19.0402)

    // region the coordinates follow the same fix the place name does

    @Test
    fun `a resolved fix states the coordinates`() {
        // A backed-up or cloud-only photo keeps its location where the device cannot read it, so this
        // is the only fix it has. Without it the sheet named a place and no coordinates under it.
        val shown = shownCoordinates(DetailsGps(51.5074, -0.1278), exif = null)

        assertEquals(51.5074, shown.latitude!!, 1e-9)
        assertEquals(-0.1278, shown.longitude!!, 1e-9)
    }

    @Test
    fun `a resolved fix outranks the EXIF pair`() {
        // Both rows read one fix or the other, never one each.
        val shown = shownCoordinates(DetailsGps(51.5074, -0.1278), exifFix)

        assertEquals(51.5074, shown.latitude!!, 1e-9)
        assertEquals(-0.1278, shown.longitude!!, 1e-9)
    }

    @Test
    fun `the EXIF pair stands while no fix is resolved`() {
        // A device photo whose EXIF read landed before the resolve did.
        val shown = shownCoordinates(resolved = null, exif = exifFix)

        assertEquals(47.4979, shown.latitude!!, 1e-9)
        assertEquals(19.0402, shown.longitude!!, 1e-9)
    }

    @Test
    fun `neither side leaves both rows out`() {
        assertNull(shownCoordinates(resolved = null, exif = null).latitude)
        assertNull(shownCoordinates(resolved = null, exif = null).longitude)
        assertNull(shownCoordinates(resolved = null, exif = PhotoMetadata()).latitude)
        assertNull(shownCoordinates(resolved = null, exif = PhotoMetadata()).longitude)
    }

    @Test
    fun `a half-written EXIF pair shows only the value it carries`() {
        val shown = shownCoordinates(resolved = null, exif = PhotoMetadata(gpsLatitude = 47.4979))

        assertEquals(47.4979, shown.latitude!!, 1e-9)
        assertNull(shown.longitude)
    }

    // endregion
    // region a video's length comes from whichever side knows it

    @Test
    fun `the on-device file's own reading leads`() {
        assertEquals(5_000L, videoDurationMs(local = 5_000L, cloudStored = 9_000L, fromBlob = 7_000L))
    }

    @Test
    fun `a cloud-only video answers from the stored length`() {
        // Nothing of it is on the device and no full-res has been fetched, which is where every
        // cloud-only video stands until it downloads.
        assertEquals(9_000L, videoDurationMs(local = null, cloudStored = 9_000L, fromBlob = null))
    }

    @Test
    fun `a decrypted full-res answers when nothing is stored`() {
        assertEquals(7_000L, videoDurationMs(local = null, cloudStored = null, fromBlob = 7_000L))
    }

    @Test
    fun `a non-positive reading counts as no reading`() {
        assertEquals(9_000L, videoDurationMs(local = 0L, cloudStored = 9_000L, fromBlob = 0L))
        assertEquals(7_000L, videoDurationMs(local = 0L, cloudStored = 0L, fromBlob = 7_000L))
    }

    @Test
    fun `no side knowing the length keeps the dash`() {
        assertNull(videoDurationMs(local = null, cloudStored = null, fromBlob = null))
        assertNull(videoDurationMs(local = 0L, cloudStored = 0L, fromBlob = 0L))
    }

    // endregion
    // region nothing on the sheet outlives the photo it describes

    @Test
    fun `settling on another photo drops what the sheet holds`() {
        assertTrue(metadataOutlivesPhoto(describes = "content://media/1", settled = "content://media/2"))
        assertTrue(metadataOutlivesPhoto(describes = "content://media/1", settled = "link1"))
    }

    @Test
    fun `re-reading the same photo keeps its rows on screen`() {
        // An editor save re-runs the read for the photo already shown. Clearing here would blink every
        // row away and back for no reason.
        assertFalse(metadataOutlivesPhoto(describes = "link1", settled = "link1"))
    }

    @Test
    fun `a first read has nothing to drop`() {
        assertFalse(metadataOutlivesPhoto(describes = null, settled = "content://media/1"))
    }

    // endregion
}
