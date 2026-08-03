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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the decision behind the original-URI upgrade every EXIF reader shares: asking for
 * the original is what keeps Android 10+ from redacting a photo's GPS, but asking without the grant
 * makes the read FAIL, so the three gates (OS level, grant, MediaStore-served URI) are the whole
 * correctness of the helper. No Android is instantiated.
 */
class MediaOriginalUriTest {

    private val mediaStoreUri = "content://media/external/images/media/4711"

    @Test
    fun `granted mediastore uri upgrades from Q upwards`() {
        assertTrue(shouldRequireOriginal(sdkInt = 29, hasGrant = true, uri = mediaStoreUri))
        assertTrue(shouldRequireOriginal(sdkInt = 34, hasGrant = true, uri = mediaStoreUri))
    }

    @Test
    fun `below Q nothing is redacted so nothing is upgraded`() {
        assertFalse(shouldRequireOriginal(sdkInt = 26, hasGrant = true, uri = mediaStoreUri))
        assertFalse(shouldRequireOriginal(sdkInt = 28, hasGrant = true, uri = mediaStoreUri))
    }

    @Test
    fun `without the grant the plain uri is kept so the read still succeeds`() {
        assertFalse(shouldRequireOriginal(sdkInt = 29, hasGrant = false, uri = mediaStoreUri))
        assertFalse(shouldRequireOriginal(sdkInt = 34, hasGrant = false, uri = mediaStoreUri))
    }

    @Test
    fun `a file uri never upgrades`() {
        assertFalse(
            shouldRequireOriginal(sdkInt = 34, hasGrant = true, uri = "file:///storage/emulated/0/a.jpg")
        )
        assertFalse(isMediaStoreUri("file:///storage/emulated/0/a.jpg"))
    }

    @Test
    fun `another provider never upgrades`() {
        val documents = "content://com.android.providers.downloads.documents/document/42"
        assertFalse(shouldRequireOriginal(sdkInt = 34, hasGrant = true, uri = documents))
        assertFalse(isMediaStoreUri(documents))
        assertFalse(isMediaStoreUri("content://eu.akoos.photos.provider/cache/a.jpg"))
    }

    @Test
    fun `mediastore is matched on scheme and authority alone`() {
        assertTrue(isMediaStoreUri(mediaStoreUri))
        assertTrue(isMediaStoreUri("content://media/external_primary/images/media/9"))
        assertTrue(isMediaStoreUri("content://media/external/images/media/9?requireOriginal=1"))
        assertTrue(isMediaStoreUri("CONTENT://MEDIA/external/images/media/9"))
    }

    @Test
    fun `a uri with no scheme or a truncated one is not mediastore`() {
        assertFalse(isMediaStoreUri("/storage/emulated/0/a.jpg"))
        assertFalse(isMediaStoreUri("media/external/images/media/9"))
        assertFalse(isMediaStoreUri("con://media/external/images/media/9"))
        assertFalse(isMediaStoreUri("contentx://media/external/images/media/9"))
        assertFalse(isMediaStoreUri(""))
    }
}
