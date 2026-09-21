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

import eu.akoos.photos.util.MetadataStripConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which stored fixes a landed strip invalidates. Two things can go wrong and both are silent: a
 * strip that removed location but drops no row leaves the map, the place facet and the location screen
 * plotting coordinates the file no longer holds, for good, because the GPS backfill skips any file
 * that already has a row; and a strip that touched no location dropping a row throws away a fix that
 * is still in the file and only the backfill can put back. Pure, so the matrix is checked without a
 * ViewModel or a database.
 */
class StrippedLocationIdsTest {

    @Test
    fun `a GPS strip invalidates every file it landed on`() {
        val ids = strippedLocationIds(MetadataStripConfig(stripGps = true), listOf(URI_A, URI_B))

        assertEquals(listOf(URI_A, URI_B), ids)
    }

    @Test
    fun `a timestamp only strip invalidates nothing`() {
        // The coordinates are still in the file, so the row still describes it and has to stand.
        val ids = strippedLocationIds(MetadataStripConfig(stripTimestamp = true), listOf(URI_A, URI_B))

        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `every strip that spares GPS invalidates nothing`() {
        val config = MetadataStripConfig(
            stripCameraInfo = true,
            stripTimestamp = true,
            stripSoftwareInfo = true,
            stripAuthorship = true,
        )

        assertEquals(emptyList<String>(), strippedLocationIds(config, listOf(URI_A, URI_B, URI_C)))
    }

    @Test
    fun `a GPS strip alongside other fields still invalidates`() {
        // The other flags are irrelevant to the decision; only stripGps is.
        val config = MetadataStripConfig(stripGps = true, stripCameraInfo = true, stripTimestamp = true)

        assertEquals(listOf(URI_A), strippedLocationIds(config, listOf(URI_A)))
    }

    @Test
    fun `a strip that landed on nothing invalidates nothing`() {
        // Every file was refused or deferred to the consent retry, so no stored fix went stale here.
        assertEquals(
            emptyList<String>(),
            strippedLocationIds(MetadataStripConfig(stripGps = true), emptyList()),
        )
    }

    @Test
    fun `an empty config invalidates nothing`() {
        assertEquals(emptyList<String>(), strippedLocationIds(MetadataStripConfig(), listOf(URI_A)))
    }

    @Test
    fun `one file listed twice yields its id once`() {
        // A selection can carry the same device file twice; the delete takes one id for it.
        val ids = strippedLocationIds(MetadataStripConfig(stripGps = true), listOf(URI_A, URI_B, URI_A))

        assertEquals(listOf(URI_A, URI_B), ids)
    }

    private companion object {
        const val URI_A = "content://media/external/images/media/1000012591"
        const val URI_B = "content://media/external/images/media/1000012592"
        const val URI_C = "content://media/external/video/media/1000012593"
    }
}
