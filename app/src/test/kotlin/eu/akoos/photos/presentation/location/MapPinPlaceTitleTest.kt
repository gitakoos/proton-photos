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

package eu.akoos.photos.presentation.location

import eu.akoos.photos.domain.usecase.PlaceLabelParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How the page of one map pin's photos is named: the city they share, else the country, else nothing. Pure. */
class MapPinPlaceTitleTest {

    private val ibiza = PlaceLabelParts("Ibiza", "ES", "Spain")
    private val santAntoni = PlaceLabelParts("Sant Antoni de Portmany", "ES", "Spain")
    private val paris = PlaceLabelParts("Paris", "FR", "France")

    @Test
    fun `photos all in one city are named after it`() {
        assertEquals(ibiza, sharedPlace(listOf(ibiza, ibiza)))
    }

    @Test
    fun `photos in two cities of one country are named after the country`() {
        assertEquals(PlaceLabelParts("", "ES", "Spain"), sharedPlace(listOf(ibiza, santAntoni)))
    }

    @Test
    fun `photos in two countries share no place`() {
        assertNull(sharedPlace(listOf(ibiza, paris)))
    }

    @Test
    fun `no placed photo shares no place`() {
        assertNull(sharedPlace(emptyList()))
    }
}
