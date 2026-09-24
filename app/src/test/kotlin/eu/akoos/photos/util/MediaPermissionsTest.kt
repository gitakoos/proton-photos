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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the media permission sets. Android 9 and older must ask for the write permission
 * too, or every write to shared storage fails there. Android 10 and newer must never ask for it: it
 * is undeclared from then on, so it comes back denied, and a check that counted it would read media
 * access as missing. Access is judged by the read set alone on every version. No Android is
 * instantiated.
 */
class MediaPermissionsTest {

    private val readStorage = "android.permission.READ_EXTERNAL_STORAGE"
    private val writeStorage = "android.permission.WRITE_EXTERNAL_STORAGE"
    private val readImages = "android.permission.READ_MEDIA_IMAGES"
    private val readVideo = "android.permission.READ_MEDIA_VIDEO"

    // ── what a request asks for ──────────────────────────────────────────────────────────────────

    @Test
    fun `Android 8 and 9 request read and write storage`() {
        assertEquals(listOf(readStorage, writeStorage), MediaPermissions.requestSet(26).toList())
        assertEquals(listOf(readStorage, writeStorage), MediaPermissions.requestSet(28).toList())
    }

    @Test
    fun `Android 10 to 12L request read storage only`() {
        assertEquals(listOf(readStorage), MediaPermissions.requestSet(29).toList())
        assertEquals(listOf(readStorage), MediaPermissions.requestSet(32).toList())
    }

    @Test
    fun `Android 13 and newer request images and video`() {
        assertEquals(listOf(readImages, readVideo), MediaPermissions.requestSet(33).toList())
        assertEquals(listOf(readImages, readVideo), MediaPermissions.requestSet(35).toList())
    }

    // ── what decides whether access is granted ───────────────────────────────────────────────────

    @Test
    fun `access is judged by read storage up to Android 12L`() {
        for (sdk in listOf(26, 28, 29, 32)) {
            assertEquals("sdk $sdk", listOf(readStorage), MediaPermissions.readSet(sdk).toList())
        }
    }

    @Test
    fun `access is judged by images and video from Android 13`() {
        for (sdk in listOf(33, 35)) {
            assertEquals("sdk $sdk", listOf(readImages, readVideo), MediaPermissions.readSet(sdk).toList())
        }
    }

    @Test
    fun `write never appears from Android 10 on`() {
        for (sdk in 29..36) {
            assertFalse("sdk $sdk", writeStorage in MediaPermissions.requestSet(sdk))
            assertFalse("sdk $sdk", writeStorage in MediaPermissions.readSet(sdk))
        }
    }

    @Test
    fun `write never decides access on any version`() {
        for (sdk in 26..36) {
            assertFalse("sdk $sdk", writeStorage in MediaPermissions.readSet(sdk))
        }
    }

    @Test
    fun `every request covers the set access is judged by`() {
        // The request is what grants the read set, so a request missing one of them would leave access
        // denied however the user answers.
        for (sdk in 26..36) {
            val requested = MediaPermissions.requestSet(sdk).toList()
            assertTrue("sdk $sdk", requested.containsAll(MediaPermissions.readSet(sdk).toList()))
        }
    }

    // ── a request's result reduced to the read set ───────────────────────────────────────────────

    @Test
    fun `a denied write leaves granted read access granted`() {
        val results = mapOf(readStorage to true, writeStorage to false)
        assertEquals(mapOf(readStorage to true), MediaPermissions.readResults(results, 28))
    }

    @Test
    fun `a granted write leaves denied read access denied`() {
        val results = mapOf(readStorage to false, writeStorage to true)
        assertEquals(mapOf(readStorage to false), MediaPermissions.readResults(results, 26))
    }

    @Test
    fun `results from Android 10 on pass through unchanged`() {
        val storage = mapOf(readStorage to true)
        assertEquals(storage, MediaPermissions.readResults(storage, 29))
        assertEquals(storage, MediaPermissions.readResults(storage, 32))
        val media = mapOf(readImages to true, readVideo to false)
        assertEquals(media, MediaPermissions.readResults(media, 33))
        assertEquals(media, MediaPermissions.readResults(media, 35))
    }

    @Test
    fun `an interrupted request stays empty`() {
        // The system answers an interrupted request with no entries at all; reducing it must not
        // invent one.
        assertEquals(emptyMap<String, Boolean>(), MediaPermissions.readResults(emptyMap(), 28))
        assertEquals(emptyMap<String, Boolean>(), MediaPermissions.readResults(emptyMap(), 35))
    }
}
