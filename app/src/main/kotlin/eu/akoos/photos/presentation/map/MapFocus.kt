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

import java.util.Locale

/**
 * A place a map opens centred on, with a focus marker on the point. It rides the optional [MAP_FOCUS_LAT_ARG]
 * / [MAP_FOCUS_LON_ARG] query arguments of both map routes: [mapFocusRoute] writes them and [parseMapFocus]
 * reads them back.
 */
data class MapFocus(val latitude: Double, val longitude: Double)

/** Nav argument names of the optional focus both map destinations register. */
const val MAP_FOCUS_LAT_ARG = "lat"
const val MAP_FOCUS_LON_ARG = "lon"

/**
 * [baseRoute] with [focus] appended as its query arguments, or [baseRoute] unchanged when there is no focus
 * or its coordinate is out of range. The numbers are written with [Locale.ROOT], so a device whose locale
 * uses a decimal comma still puts "47.497900" into the route, never "47,497900".
 */
fun mapFocusRoute(baseRoute: String, focus: MapFocus?): String {
    if (focus == null || !isValidCoordinate(focus.latitude, focus.longitude)) return baseRoute
    return "$baseRoute?$MAP_FOCUS_LAT_ARG=${routeNumber(focus.latitude)}" +
        "&$MAP_FOCUS_LON_ARG=${routeNumber(focus.longitude)}"
}

/**
 * The focus read back from the two nav argument values, or null (the plain map) when either is missing, is
 * not a number, or lies outside the valid latitude / longitude range.
 */
fun parseMapFocus(latitude: String?, longitude: String?): MapFocus? {
    val lat = latitude?.toDoubleOrNull() ?: return null
    val lon = longitude?.toDoubleOrNull() ?: return null
    return if (isValidCoordinate(lat, lon)) MapFocus(lat, lon) else null
}

private fun isValidCoordinate(lat: Double, lon: Double): Boolean =
    lat in -90.0..90.0 && lon in -180.0..180.0

private fun routeNumber(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
