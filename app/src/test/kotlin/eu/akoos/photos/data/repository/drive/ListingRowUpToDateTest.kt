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

package eu.akoos.photos.data.repository.drive

import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.data.db.dao.ListingRefreshStamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which listed photos a full refresh may keep as stored without fetching their details again
 * ([listingRowUpToDate]): only a fully built row the listing shows unchanged.
 */
class ListingRowUpToDateTest {

    private val stub = PhotoLinkDto(linkId = "L1", captureTime = 1_700_000_000L, contentHash = "hash-1", tags = listOf(3, 0))

    private val lazyRow = ListingRefreshStamp(
        linkId = "L1",
        revisionId = "rev-1",
        contentHash = "hash-1",
        captureTime = 1_700_000_000L,
        tagsCsv = "0,3",
        thumbnailUrl = null,
        nameFingerprint = "fp-1",
        lazyThumbnailReady = true,
    )

    private val noThumbnailOnDisk: (String?) -> Boolean = { false }
    private val thumbnailOnDisk: (String?) -> Boolean = { true }

    @Test
    fun `a built row the listing shows unchanged is kept as stored`() {
        assertTrue(listingRowUpToDate(stub, lazyRow, noThumbnailOnDisk))
    }

    @Test
    fun `a row with its thumbnail decrypted on disk is kept as stored`() {
        val row = lazyRow.copy(thumbnailUrl = "file:///thumbs/L1.jpg", lazyThumbnailReady = false)

        assertTrue(listingRowUpToDate(stub, row, thumbnailOnDisk))
    }

    @Test
    fun `a photo with no stored row is fetched`() {
        assertFalse(listingRowUpToDate(stub, null, noThumbnailOnDisk))
    }

    @Test
    fun `a stub row whose details never loaded is fetched`() {
        assertFalse(listingRowUpToDate(stub, lazyRow.copy(revisionId = ""), noThumbnailOnDisk))
    }

    @Test
    fun `a photo whose content changed is fetched`() {
        assertFalse(listingRowUpToDate(stub.copy(contentHash = "hash-2"), lazyRow, noThumbnailOnDisk))
    }

    @Test
    fun `a listing entry with no content hash is fetched`() {
        assertFalse(listingRowUpToDate(stub.copy(contentHash = null), lazyRow, noThumbnailOnDisk))
    }

    @Test
    fun `a photo whose capture time changed is fetched`() {
        assertFalse(listingRowUpToDate(stub.copy(captureTime = 1_700_000_001L), lazyRow, noThumbnailOnDisk))
    }

    @Test
    fun `a photo whose tags changed is fetched`() {
        assertFalse(listingRowUpToDate(stub.copy(tags = listOf(3)), lazyRow, noThumbnailOnDisk))
    }

    @Test
    fun `a row with no name digest yet is fetched`() {
        assertFalse(listingRowUpToDate(stub, lazyRow.copy(nameFingerprint = null), noThumbnailOnDisk))
    }

    @Test
    fun `a row whose decrypted thumbnail file is gone is fetched`() {
        val row = lazyRow.copy(thumbnailUrl = "file:///thumbs/L1.jpg")

        assertFalse(listingRowUpToDate(stub, row, noThumbnailOnDisk))
    }

    @Test
    fun `a row with neither a thumbnail on disk nor the lazy decrypt material is fetched`() {
        assertFalse(listingRowUpToDate(stub, lazyRow.copy(lazyThumbnailReady = false), noThumbnailOnDisk))
    }
}
