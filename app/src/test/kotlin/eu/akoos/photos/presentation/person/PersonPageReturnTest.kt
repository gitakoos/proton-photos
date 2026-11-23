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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.location.LocationGridRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the person page comes back from the viewer: it keeps following its person instead of reading
 * the library again, keeps its photos on screen through any later read, and finds the photo the viewer
 * closed on in the runs the grid draws under its month headers.
 */
class PersonPageReturnTest {

    private fun photo(id: String) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = "content://media/external/images/media/$id",
            dateTaken = 1_000L,
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_000L,
            bucketName = "Camera",
        ),
    )

    @Test
    fun `a page still following its person is not read again on return`() {
        assertFalse(reloadsPersonPage(followedPersonId = 7L, requested = 7L, following = true))
    }

    @Test
    fun `a page whose read ended reads its person again`() {
        assertTrue(reloadsPersonPage(followedPersonId = 7L, requested = 7L, following = false))
    }

    @Test
    fun `a page opened for the first time reads its person`() {
        assertTrue(reloadsPersonPage(followedPersonId = null, requested = 7L, following = false))
    }

    @Test
    fun `a page asked for another person reads that person`() {
        assertTrue(reloadsPersonPage(followedPersonId = 7L, requested = 3L, following = true))
    }

    @Test
    fun `the first read of an empty page shows the spinner`() {
        assertTrue(showsPersonSpinner(isLoading = true, isEmpty = true, leaving = false))
    }

    @Test
    fun `photos already shown stay on screen while the page reads again`() {
        assertFalse(showsPersonSpinner(isLoading = true, isEmpty = false, leaving = false))
    }

    @Test
    fun `a page emptied on its way out shows the spinner`() {
        assertTrue(showsPersonSpinner(isLoading = false, isEmpty = true, leaving = true))
    }

    @Test
    fun `a settled empty page shows no spinner`() {
        assertFalse(showsPersonSpinner(isLoading = false, isEmpty = true, leaving = false))
    }

    @Test
    fun `photos are grouped in the runs under each month header`() {
        val a = photo("1")
        val b = photo("2")
        val c = photo("3")
        val rows = listOf(
            LocationGridRow.Header("hdr_2026-9", "September 2026"),
            LocationGridRow.Photo("a", 0, a),
            LocationGridRow.Photo("b", 1, b),
            LocationGridRow.Header("hdr_2026-8", "August 2026"),
            LocationGridRow.Photo("c", 2, c),
        )

        assertEquals(listOf(listOf(a, b), listOf(c)), photosByMonth(rows))
    }

    @Test
    fun `no rows make no runs`() {
        assertEquals(emptyList<List<GalleryItem>>(), photosByMonth(emptyList()))
    }
}
