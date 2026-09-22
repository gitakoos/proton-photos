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

import eu.akoos.photos.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The map routes carry an optional focus as `lat` / `lon` query arguments: [mapFocusRoute] writes them and
 * [parseMapFocus] reads them back. A bad value has to open the plain map rather than a wrong place, and a
 * decimal-comma locale must never put a comma into the route. Pure, no Android runtime.
 */
class MapFocusTest {

    /** The query of [route] as name to value, split the way the route carries it. */
    private fun queryOf(route: String): Map<String, String> =
        route.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun roundTrip(focus: MapFocus): MapFocus? {
        val query = queryOf(mapFocusRoute(Screen.Map.route, focus))
        return parseMapFocus(query[MAP_FOCUS_LAT_ARG], query[MAP_FOCUS_LON_ARG])
    }

    @Test
    fun `a focus survives the route round trip`() {
        val back = roundTrip(MapFocus(47.4979, 19.0402))!!
        assertEquals(47.4979, back.latitude, 1e-6)
        assertEquals(19.0402, back.longitude, 1e-6)
    }

    @Test
    fun `negative coordinates survive the round trip`() {
        val south = roundTrip(MapFocus(-33.8688, 151.2093))!!
        assertEquals(-33.8688, south.latitude, 1e-6)
        assertEquals(151.2093, south.longitude, 1e-6)
        val west = roundTrip(MapFocus(40.7128, -74.006))!!
        assertEquals(40.7128, west.latitude, 1e-6)
        assertEquals(-74.006, west.longitude, 1e-6)
    }

    @Test
    fun `the route keeps a dot decimal under a comma locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("hu-HU"))
            // The locale really formats with a comma, so the assertions below prove the route ignores it.
            assertEquals("47,497900", String.format("%.6f", 47.4979))
            val route = mapFocusRoute(Screen.Map.route, MapFocus(47.4979, 19.0402))
            assertEquals("map?lat=47.497900&lon=19.040200", route)
            assertFalse(route.contains(','))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `no focus leaves the plain route`() {
        assertEquals("map", mapFocusRoute(Screen.Map.route, null))
        assertEquals("custom_map", mapFocusRoute(Screen.CustomMap.route, null))
    }

    @Test
    fun `an out of range or non finite focus leaves the plain route`() {
        assertEquals("map", mapFocusRoute("map", MapFocus(90.5, 0.0)))
        assertEquals("map", mapFocusRoute("map", MapFocus(0.0, -180.5)))
        assertEquals("map", mapFocusRoute("map", MapFocus(Double.NaN, 0.0)))
        assertEquals("map", mapFocusRoute("map", MapFocus(0.0, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `parsing rejects missing, malformed and out of range values`() {
        assertNull(parseMapFocus(null, null))
        assertNull(parseMapFocus("47.5", null))
        assertNull(parseMapFocus(null, "19.0"))
        assertNull(parseMapFocus("", ""))
        assertNull(parseMapFocus("abc", "19.0"))
        assertNull(parseMapFocus("47,5", "19,0"))
        assertNull(parseMapFocus("{lat}", "{lon}"))
        assertNull(parseMapFocus("90.1", "0"))
        assertNull(parseMapFocus("0", "-180.1"))
        assertNull(parseMapFocus("NaN", "0"))
        assertNull(parseMapFocus("0", "Infinity"))
    }

    @Test
    fun `parsing accepts the range boundaries`() {
        assertEquals(MapFocus(-90.0, -180.0), parseMapFocus("-90", "-180"))
        assertEquals(MapFocus(90.0, 180.0), parseMapFocus("90.000000", "180.000000"))
    }

    @Test
    fun `the focused route fills exactly the arguments each map pattern declares`() {
        val focus = MapFocus(1.5, 2.5)
        val maps = listOf(
            Screen.Map.route to Screen.Map.PATTERN,
            Screen.CustomMap.route to Screen.CustomMap.PATTERN,
        )
        for ((base, pattern) in maps) {
            val route = mapFocusRoute(base, focus)
            assertEquals(pattern.substringBefore('?'), route.substringBefore('?'))
            val declared = queryOf(pattern)
            assertEquals(declared.keys, queryOf(route).keys)
            // Each declared value is the nav placeholder of its own name.
            declared.forEach { (name, placeholder) -> assertEquals("{$name}", placeholder) }
        }
    }
}
