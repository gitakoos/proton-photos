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

package eu.akoos.photos.presentation.map

import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.domain.usecase.PlaceCountry
import eu.akoos.photos.domain.usecase.PlacesData
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.db.entity.isNullIsland
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the globe and the OSM map share: the pins both plot, the single places state the globe reads so
 * "loaded" never arrives without the cities, and the fixes at 0, 0 that both leave out.
 */
class GlobeMapTest {

    private fun city(name: String, lat: Double, lng: Double) =
        PlaceCity(city = name, countryCode = "HU", countryName = "Hungary", latitude = lat, longitude = lng, count = 1, cover = null)

    private fun pin(i: Int) = MapPin(id = "p$i", latitude = i.toDouble(), longitude = 0.0, item = null)

    @Test
    fun before_the_first_result_nothing_is_loaded() {
        val places = (null as PlacesData?).toMapPlaces()
        assertFalse(places.loaded)
        assertTrue(places.cities.isEmpty())
        assertTrue(places.countries.isEmpty())
    }

    @Test
    fun loaded_comes_with_the_cities_and_countries_of_the_same_result() {
        val cities = listOf(city("Budapest", 47.5, 19.0), city("Pecs", 46.1, 18.2))
        val country = PlaceCountry(countryCode = "HU", countryName = "Hungary", count = 2, cityCount = 2, cover = null)
        val places = PlacesData(countries = listOf(country), cities = cities).toMapPlaces()
        assertTrue(places.loaded)
        assertEquals(cities, places.cities)
        assertEquals(listOf(country), places.countries)
    }

    @Test
    fun a_loaded_account_with_no_places_is_loaded_and_empty() {
        val places = PlacesData(emptyList(), emptyList()).toMapPlaces()
        assertTrue(places.loaded)
        assertTrue(places.cities.isEmpty())
    }

    @Test
    fun a_fix_at_zero_zero_is_a_null_island_and_any_other_is_not() {
        fun fix(lat: Double, lng: Double) = PhotoLocationEntity(id = "x", userId = "u", latitude = lat, longitude = lng)
        assertTrue(fix(0.0, 0.0).isNullIsland())
        assertFalse(fix(0.0, 19.0).isNullIsland())
        assertFalse(fix(47.5, 0.0).isNullIsland())
        assertFalse(fix(0.001, 0.001).isNullIsland())
    }

    @Test
    fun a_few_cities_are_plotted_whole_and_many_keep_the_busiest() {
        val few = (0 until 20).map { pin(it) }
        assertEquals(few, pinsInView(few) { true })

        // The pins arrive busiest city first, so the cap keeps the head of the list.
        val many = (0 until 1500).map { pin(it) }
        val kept = pinsInView(many) { true }
        assertEquals(MARKER_CAP, kept.size)
        assertEquals("p0", kept.first().id)
        assertEquals("p${MARKER_CAP - 1}", kept.last().id)
    }

    @Test
    fun a_city_past_the_cap_shows_once_the_view_narrows_to_it() {
        val many = (0 until 1500).map { pin(it) }
        assertTrue(pinsInView(many) { true }.none { it.id == "p1200" })

        // Zoomed in on latitudes 1190..1210: only the cities there compete for the pins.
        val zoomed = pinsInView(many) { it.latitude in 1190.0..1210.0 }
        assertEquals((1190..1210).map { "p$it" }, zoomed.map { it.id })
    }

    @Test
    fun pins_grow_from_country_to_street_level() {
        assertEquals(0.7f, pinScaleForZoom(5.0), 0f)
        assertEquals(0.7f, pinScaleForZoom(3.0), 0f)
        assertEquals(1f, pinScaleForZoom(12.0), 0f)
        assertEquals(1.3f, pinScaleForZoom(18.0), 0f)
        assertEquals(1.3f, pinScaleForZoom(20.0), 0f)
        // Never smaller on a deeper zoom.
        val steps = (50..180).map { pinScaleForZoom(it / 10.0) }
        assertEquals(steps.sorted(), steps)
    }
}
