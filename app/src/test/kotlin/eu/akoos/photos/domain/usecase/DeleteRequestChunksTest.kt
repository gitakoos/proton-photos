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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [nextMediaStoreRequestBatch]: one stable-order request batch, bounded by the platform max.
 */
class DeleteRequestChunksTest {

    @Test
    fun `an empty input yields an empty batch`() {
        assertEquals(emptyList<String>(), nextMediaStoreRequestBatch(emptyList()))
    }

    @Test
    fun `a list at the max stays intact and ordered`() {
        val uris = (1..DELETE_REQUEST_MAX_URIS).map { "content://media/external/images/media/$it" }
        assertEquals(uris, nextMediaStoreRequestBatch(uris))
    }

    @Test
    fun `an oversized input returns only the first max entries in order`() {
        val uris = (1..2_001).map { "content://media/external/images/media/$it" }
        assertEquals(uris.take(DELETE_REQUEST_MAX_URIS), nextMediaStoreRequestBatch(uris))
    }

    @Test
    fun `a custom max returns the stable prefix`() {
        val uris = (1..5).map { "uri-$it" }
        assertEquals(uris.take(2), nextMediaStoreRequestBatch(uris, max = 2))
    }

    @Test
    fun `max must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            nextMediaStoreRequestBatch(listOf("uri-1"), max = 0)
        }
    }
}
