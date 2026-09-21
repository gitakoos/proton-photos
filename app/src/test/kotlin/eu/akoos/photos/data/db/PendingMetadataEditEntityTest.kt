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

package eu.akoos.photos.data.db

import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.usecase.CloudWorkItem
import eu.akoos.photos.domain.usecase.LocationEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure round-trip test for the [PendingMetadataEditEntity] flatten/rehydrate pair. The persisted row
 * stores primitives only (no [CloudPhoto]): [PendingMetadataEditEntity.fromWorkItem] flattens a work
 * item to that row, and [PendingMetadataEditEntity.toWorkItem] rebuilds it against a freshly fetched
 * photo. These assert every scalar field, and every [LocationEdit] variant, survives the trip. No DB
 * and no Robolectric: the two functions are pure.
 */
class PendingMetadataEditEntityTest {

    private fun photo(linkId: String = "link-1") = CloudPhoto(
        linkId = linkId,
        shareId = "share",
        volumeId = "vol1",
        captureTime = 1_600_000_000L,
        displayName = "IMG_1234.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev-1",
    )

    private fun roundTrip(item: CloudWorkItem): CloudWorkItem =
        PendingMetadataEditEntity.fromWorkItem(item, userId = "u1", enqueuedAt = 42L)
            .toWorkItem(item.photo)

    @Test
    fun `a synced edit round-trips every scalar field and an unchanged place`() {
        val source = photo()
        val item = CloudWorkItem(
            photo = source,
            newCaptureMs = 1_700_000_000_000L,
            location = LocationEdit.Unchanged,
            deviceUri = "content://media/external/images/media/7",
            description = "sunset",
            artist = "Ansel Adams",
            copyright = "CC0",
        )

        val result = roundTrip(item)

        assertEquals(1_700_000_000_000L, result.newCaptureMs)
        assertEquals("content://media/external/images/media/7", result.deviceUri)
        assertEquals("sunset", result.description)
        assertEquals("Ansel Adams", result.artist)
        assertEquals("CC0", result.copyright)
        assertEquals(LocationEdit.Unchanged, result.location)
        assertEquals("the freshly fetched photo is carried through", source, result.photo)
    }

    @Test
    fun `a cloud-only edit keeps a null device uri and null text tags`() {
        val item = CloudWorkItem(
            photo = photo(),
            newCaptureMs = null,
            location = LocationEdit.Clear,
        )

        val result = roundTrip(item)

        assertNull("null deviceUri means a cloud-only photo", result.deviceUri)
        assertNull(result.newCaptureMs)
        assertNull("an untouched tag stays null", result.description)
        assertNull("an untouched tag stays null", result.artist)
        assertNull("an untouched tag stays null", result.copyright)
        assertEquals(LocationEdit.Clear, result.location)
    }

    @Test
    fun `an empty text tag stays empty and is not collapsed to null`() {
        val item = CloudWorkItem(
            photo = photo(),
            newCaptureMs = null,
            location = LocationEdit.Unchanged,
            description = "",
            artist = "",
            copyright = "",
        )

        val result = roundTrip(item)

        assertEquals("an empty tag clears that field, distinct from null", "", result.description)
        assertEquals("", result.artist)
        assertEquals("", result.copyright)
    }

    @Test
    fun `a Set place round-trips exact coordinates`() {
        val item = CloudWorkItem(
            photo = photo(),
            newCaptureMs = null,
            location = LocationEdit.Set(47.497913, 19.040236),
        )

        val result = roundTrip(item)

        assertEquals(LocationEdit.Set(47.497913, 19.040236), result.location)
        val set = result.location as LocationEdit.Set
        assertEquals(47.497913, set.latitude, 0.0)
        assertEquals(19.040236, set.longitude, 0.0)
    }

    @Test
    fun `a Set row missing a coordinate falls back to Unchanged`() {
        val row = PendingMetadataEditEntity(
            linkId = "link-1",
            userId = "u1",
            deviceUri = null,
            newCaptureMs = null,
            locationMode = PendingMetadataEditEntity.MODE_SET,
            lat = null,
            lng = null,
            description = null,
            artist = null,
            copyright = null,
            enqueuedAt = 1L,
        )

        assertEquals(LocationEdit.Unchanged, row.toLocationEdit())
    }

    @Test
    fun `fromWorkItem takes its linkId from the photo and carries the enqueue metadata`() {
        val item = CloudWorkItem(
            photo = photo("the-original"),
            newCaptureMs = null,
            location = LocationEdit.Unchanged,
        )

        val row = PendingMetadataEditEntity.fromWorkItem(item, userId = "user-9", enqueuedAt = 99L)

        assertEquals("the-original", row.linkId)
        assertEquals("user-9", row.userId)
        assertEquals(99L, row.enqueuedAt)
    }
}
