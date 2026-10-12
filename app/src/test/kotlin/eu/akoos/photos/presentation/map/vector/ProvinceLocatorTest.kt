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

package eu.akoos.photos.presentation.map.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins that a photo's position finds its province: inside an outline, just off the coast, and not at all in open sea,
 * on a small made-up map and on the bundled one.
 */
class ProvinceLocatorTest {

    private fun box(owner: Int, minLat: Float, minLng: Float, maxLat: Float, maxLng: Float): DetailRing {
        val lat = floatArrayOf(minLat, minLat, maxLat, maxLat, minLat)
        val lng = floatArrayOf(minLng, maxLng, maxLng, minLng, minLng)
        return DetailRing(owner, minLat, minLng, maxLat, maxLng, lat, lng, ByteArray(5))
    }

    private fun smallMap(): WorldDetail {
        val west = DetailArea("West", "XX", 0f, 0f)
        val east = DetailArea("East", "XX", 0f, 0f)
        return WorldDetail(
            countries = emptyList(),
            provinces = listOf(west, east),
            countryRings = emptyList(),
            provinceRings = listOf(box(0, 10f, 10f, 12f, 12f), box(1, 10f, 12f, 12f, 14f)),
            places = emptyList(),
        )
    }

    @Test
    fun a_point_inside_an_outline_marks_that_province_only() {
        val visited = ProvinceLocator(smallMap()).visited(listOf(11f to 11f))
        assertTrue(visited[0])
        assertFalse(visited[1])
    }

    @Test
    fun a_point_just_off_the_edge_joins_the_nearest_province() {
        val visited = ProvinceLocator(smallMap()).visited(listOf(11f to 9.95f))
        assertTrue(visited[0])
        assertFalse(visited[1])
    }

    @Test
    fun a_point_far_from_every_outline_marks_nothing() {
        val visited = ProvinceLocator(smallMap()).visited(listOf(30f to 30f))
        assertFalse(visited[0])
        assertFalse(visited[1])
    }

    @Test
    fun a_city_with_county_rights_marks_its_county_and_a_county_marks_the_cities_inside_it() {
        val outerLat = floatArrayOf(10f, 10f, 20f, 20f, 10f)
        val outerLng = floatArrayOf(10f, 20f, 20f, 10f, 10f)
        val holeLat = floatArrayOf(14f, 14f, 16f, 16f, 14f)
        val holeLng = floatArrayOf(14f, 16f, 16f, 14f, 14f)
        val county = DetailArea("County", "XX", 12f, 12f)
        val city = DetailArea("City", "XX", 15f, 15f, cityLevel = true)
        val countyRing = DetailRing(0, 10f, 10f, 20f, 20f, outerLat, outerLng, ByteArray(5))
        val countyHole = DetailRing(0, 14f, 14f, 16f, 16f, holeLat, holeLng, ByteArray(5))
        val cityRing = box(1, 14f, 14f, 16f, 16f)
        val detail = WorldDetail(emptyList(), listOf(county, city), emptyList(), listOf(countyRing, countyHole, cityRing), emptyList())

        val inCity = ProvinceLocator(detail).visited(listOf(15f to 15f))
        assertTrue(inCity[0])
        assertTrue(inCity[1])

        val inCounty = ProvinceLocator(detail).visited(listOf(11f to 11f))
        assertTrue(inCounty[0])
        assertTrue(inCounty[1])
    }

    @Test
    fun the_bundled_map_places_pecs_in_baranya_and_budapest_in_its_own_unit() {
        val file = File("src/main/assets/world_detail.bin")
        assertTrue("run from the app module", file.exists())
        val detail = WorldDetailData.parse(file.readBytes())
        val pecs = ProvinceLocator(detail).visited(listOf(46.0727f to 18.2323f))
        val pecsNames = detail.provinces.filterIndexed { i, _ -> pecs[i] }.map { it.name }
        assertTrue(pecsNames.toString(), pecsNames.any { it.contains("Baranya") })
        assertTrue(pecsNames.toString(), pecsNames.any { it.startsWith("P") && it.endsWith("cs") })
        val budapest = ProvinceLocator(detail).visited(listOf(47.4979f to 19.0402f))
        val budapestNames = detail.provinces.filterIndexed { i, _ -> budapest[i] }.map { it.name }
        assertTrue(budapestNames.toString(), budapestNames.any { it.contains("Budapest") })
    }
}
