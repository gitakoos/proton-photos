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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumContentsSummaryTest {

    private fun row(size: Long) = AlbumContentsRow("IMG_$size.jpg", size)

    @Test
    fun `known sizes add up`() {
        val summary = albumContentsSummary(listOf(row(100), row(250), row(1)))
        assertEquals(AlbumContentsSummary(totalBytes = 351, knownCount = 3, unknownCount = 0), summary)
    }

    @Test
    fun `a zero size is unknown and left out of the total`() {
        val summary = albumContentsSummary(listOf(row(100), row(0), row(50)))
        assertEquals(AlbumContentsSummary(totalBytes = 150, knownCount = 2, unknownCount = 1), summary)
    }

    @Test
    fun `a negative size is unknown too`() {
        val summary = albumContentsSummary(listOf(row(-1), row(10)))
        assertEquals(AlbumContentsSummary(totalBytes = 10, knownCount = 1, unknownCount = 1), summary)
    }

    @Test
    fun `only unknown sizes give a zero total`() {
        val summary = albumContentsSummary(listOf(row(0), row(0)))
        assertEquals(AlbumContentsSummary(totalBytes = 0, knownCount = 0, unknownCount = 2), summary)
    }

    @Test
    fun `an empty album is all zeros`() {
        assertEquals(AlbumContentsSummary(0, 0, 0), albumContentsSummary(emptyList()))
    }

    @Test
    fun `a total beyond Int does not overflow`() {
        val fourGib = 4L * 1024 * 1024 * 1024
        val rows = List(30_000) { row(fourGib) }
        val summary = albumContentsSummary(rows)
        assertEquals(30_000L * fourGib, summary.totalBytes)
        assertEquals(30_000, summary.knownCount)
        assertEquals(0, summary.unknownCount)
    }
}
