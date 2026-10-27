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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.mediastore.MediaStoreRowPresence
import eu.akoos.photos.data.mediastore.MediaStoreRowsProbe
import eu.akoos.photos.data.mediastore.canPrunePendingDeleteFromProbe
import eu.akoos.photos.data.mediastore.classifyUnresolvedAfterProbe
import eu.akoos.photos.data.mediastore.mediaStoreRowId
import eu.akoos.photos.data.mediastore.parseContentUriOrNull
import eu.akoos.photos.data.mediastore.urisPresentInMediaStore
import eu.akoos.photos.util.isMediaStoreUri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for MediaStore URI classification used before createDeleteRequest /
 * createTrashRequest, and for the inconclusive-vs-absent probe contract that keeps free-up and
 * pending-delete from inventing CLOUD_ONLY / queue pruning on a failed provider query.
 */
class MediaStoreUriProbeTest {

    @Test
    fun `mediaStoreRowId reads the trailing numeric segment`() {
        assertEquals(42L, mediaStoreRowId("content://media/external/images/media/42"))
        assertEquals(7L, mediaStoreRowId("content://media/external/video/media/7"))
        assertEquals(9L, mediaStoreRowId("content://media/external/images/media/9?requireOriginal=1"))
    }

    @Test
    fun `mediaStoreRowId returns null when there is no numeric id`() {
        assertNull(mediaStoreRowId("content://media/external/images/media"))
        assertNull(mediaStoreRowId("file:///sdcard/photo.jpg"))
        assertNull(mediaStoreRowId(""))
    }

    @Test
    fun `urisPresentInMediaStore keeps only ids that still exist`() {
        val uris = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media/3",
            "content://other/no-id",
        )
        assertEquals(
            listOf(
                "content://media/external/images/media/1",
                "content://media/external/images/media/3",
            ),
            urisPresentInMediaStore(uris, existingIds = setOf(1L, 3L, 99L)),
        )
    }

    @Test
    fun `urisPresentInMediaStore drops everything when the probe finds nothing`() {
        val uris = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
        )
        assertTrue(urisPresentInMediaStore(uris, existingIds = emptySet()).isEmpty())
    }

    @Test
    fun `parseContentUriOrNull requires a MediaStore content URI`() {
        // Gate is pure string matching (isMediaStoreUri). Uri.parse itself needs the Android
        // runtime; under JVM stubs a MediaStore string may still fail to parse, so pin the
        // rejection contract here and the MediaStore authority match via isMediaStoreUri.
        assertTrue(isMediaStoreUri("content://media/external/images/media/42"))
        assertNull(parseContentUriOrNull("file:///sdcard/photo.jpg"))
        assertNull(parseContentUriOrNull("content://com.android.providers.downloads.documents/document/1"))
        assertNull(parseContentUriOrNull("content://eu.akoos.photos.provider/cache/a.jpg"))
        assertNull(parseContentUriOrNull(""))
        assertNull(parseContentUriOrNull("media/external/images/media/9"))
    }

    @Test
    fun `inconclusive probe never classifies unresolved as missing`() {
        val unresolved = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
        )
        assertNull(classifyUnresolvedAfterProbe(unresolved, MediaStoreRowsProbe.Inconclusive))
        assertFalse(canPrunePendingDeleteFromProbe(MediaStoreRowsProbe.Inconclusive))
    }

    @Test
    fun `completed empty probe classifies numeric URIs as genuinely missing`() {
        val unresolved = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media",
        )
        val classified = classifyUnresolvedAfterProbe(
            unresolved,
            MediaStoreRowsProbe.Complete(emptyMap()),
        )
        requireNotNull(classified)
        assertEquals(
            listOf(
                "content://media/external/images/media/1",
                "content://media/external/images/media/2",
                "content://media/external/images/media",
            ),
            classified.missing,
        )
        assertTrue(classified.needsPermission.isEmpty())
        assertTrue(canPrunePendingDeleteFromProbe(MediaStoreRowsProbe.Complete(emptyMap())))
    }

    @Test
    fun `completed probe splits present rows from missing`() {
        val unresolved = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media/3",
        )
        val classified = classifyUnresolvedAfterProbe(
            unresolved,
            MediaStoreRowsProbe.Complete(
                mapOf(1L to MediaStoreRowPresence(id = 1L, isTrashed = false)),
            ),
        )
        requireNotNull(classified)
        assertEquals(listOf("content://media/external/images/media/1"), classified.needsPermission)
        assertEquals(
            listOf(
                "content://media/external/images/media/2",
                "content://media/external/images/media/3",
            ),
            classified.missing,
        )
    }
}
