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

package eu.akoos.photos.data.sdk

import eu.akoos.photos.util.toLowerHex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An upload through the SDK must carry the same metadata sections the app's own upload writes into a
 * photo's extended attributes, or Drive Web shows a photo without its size, place or camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdkUploadMetadataTest {

    private fun json(sections: Map<String, ByteArray>, key: String) = JSONObject(String(sections.getValue(key)))

    @Test
    fun `media is always written, with the duration in seconds`() {
        val sections = sdkAdditionalMetadata(1080, 1920, 7250, null, null, null, null, null, null)
        val media = json(sections, "Media")
        assertEquals(1080, media.getInt("Width"))
        assertEquals(1920, media.getInt("Height"))
        assertEquals(7.25, media.getDouble("Duration"), 0.0001)
        assertFalse(sections.containsKey("Location"))
        assertFalse(sections.containsKey("Camera"))
        assertEquals(0, json(sdkAdditionalMetadata(10, 10, 0, null, null, null, null, null, null), "Media").getInt("Duration"))
    }

    @Test
    fun `location is written only for a usable fix`() {
        val located = sdkAdditionalMetadata(1, 1, 0, 47.4979, 19.0402, null, null, null, null)
        assertEquals(47.4979, json(located, "Location").getDouble("Latitude"), 0.0)
        assertEquals(19.0402, json(located, "Location").getDouble("Longitude"), 0.0)
        assertFalse(sdkAdditionalMetadata(1, 1, 0, 0.0, 0.0, null, null, null, null).containsKey("Location"))
    }

    @Test
    fun `camera carries every known field, the subject rectangle included`() {
        val sections = sdkAdditionalMetadata(
            1, 1, 0, null, null, "2026-10-23T08:00:00Z", "Pixel \"9\"", 6, intArrayOf(10, 20, 30, 40),
        )
        val camera = json(sections, "Camera")
        assertEquals("2026-10-23T08:00:00Z", camera.getString("CaptureTime"))
        assertEquals("Pixel \"9\"", camera.getString("Device"))
        assertEquals(6, camera.getInt("Orientation"))
        val rect = camera.getJSONObject("SubjectCoordinates")
        assertEquals(listOf(10, 20, 30, 40), listOf(rect.getInt("Top"), rect.getInt("Left"), rect.getInt("Bottom"), rect.getInt("Right")))
        assertTrue(sdkAdditionalMetadata(1, 1, 0, null, null, null, null, 1, null).containsKey("Camera"))
    }

    @Test
    fun `a SHA-1 hex string turns into its bytes and back`() {
        val hex = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
        val bytes = hexToBytes(hex)
        assertEquals(20, bytes.size)
        assertEquals(hex, bytes.toLowerHex())
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte()), hexToBytes("00ff"))
    }
}
