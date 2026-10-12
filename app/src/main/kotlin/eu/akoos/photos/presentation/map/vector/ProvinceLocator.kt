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

import kotlin.math.floor

/**
 * Finds which provinces (counties, states, regions) the account's photos were taken in, so the globe can tint
 * those and leave the rest of the country plain. Outlines are indexed by the degree cell their box touches, so a
 * point is tested against the few outlines near it instead of all of them.
 */
internal class ProvinceLocator(private val detail: WorldDetail) {

    private val cells = HashMap<Int, MutableList<Int>>()
    private val ringsByOwner = HashMap<Int, MutableList<DetailRing>>()
    private val cityUnitsByIso = HashMap<String, MutableList<Int>>()

    init {
        detail.provinces.forEachIndexed { index, area ->
            if (area.cityLevel) cityUnitsByIso.getOrPut(area.iso2) { ArrayList() } += index
        }
        detail.provinceRings.forEachIndexed { index, ring ->
            ringsByOwner.getOrPut(ring.owner) { ArrayList() } += ring
            for (latCell in cellOf(ring.minLat)..cellOf(ring.maxLat)) {
                for (lngCell in cellOf(ring.minLng)..cellOf(ring.maxLng)) {
                    cells.getOrPut(key(latCell, lngCell)) { ArrayList() } += index
                }
            }
        }
    }

    /**
     * One entry per province: true where at least one of [points] (latitude to longitude) lies inside it. A city
     * carved out of a province counts as part of it: a photo in the city marks the province around it, and a marked
     * province marks the cities inside it, so the whole province is tinted without a hole where its city is.
     */
    fun visited(points: Collection<Pair<Float, Float>>): BooleanArray {
        val result = BooleanArray(detail.provinces.size)
        for ((lat, lng) in points) {
            val owner = ownerAt(lat, lng)
            if (owner >= 0) result[owner] = true
        }
        for (index in result.indices) {
            if (!result[index] || !detail.provinces[index].cityLevel) continue
            enclosingProvince(index)?.let { result[it] = true }
        }
        for (index in result.indices) {
            if (!result[index] || detail.provinces[index].cityLevel) continue
            for (city in cityUnitsByIso[detail.provinces[index].iso2].orEmpty()) {
                if (!result[city] && isInHoleOf(index, detail.provinces[city])) result[city] = true
            }
        }
        return result
    }

    /** The province whose hole holds the city-level unit [city], if there is one. */
    private fun enclosingProvince(city: Int): Int? {
        val area = detail.provinces[city]
        val cell = key(cellOf(area.centreLat), cellOf(area.centreLng))
        for (ringIndex in cells[cell].orEmpty()) {
            val owner = detail.provinceRings[ringIndex].owner
            if (owner == city || detail.provinces[owner].iso2 != area.iso2 || detail.provinces[owner].cityLevel) continue
            if (isInHoleOf(owner, area)) return owner
        }
        return null
    }

    /** Whether [area]'s centre lies in a hole of province [owner]: inside two or more of its outlines, an even number. */
    private fun isInHoleOf(owner: Int, area: DetailArea): Boolean {
        var containing = 0
        for (ring in ringsByOwner[owner].orEmpty()) {
            if (area.centreLat < ring.minLat || area.centreLat > ring.maxLat ||
                area.centreLng < ring.minLng || area.centreLng > ring.maxLng
            ) continue
            if (pointInDetailRing(area.centreLat.toDouble(), area.centreLng.toDouble(), ring, LOCATE_LEVEL)) containing++
        }
        return containing >= 2 && containing % 2 == 0
    }

    /** The province a point lies in; one a little off the coast, outside the simplified outline, joins the nearest. */
    private fun ownerAt(lat: Float, lng: Float): Int {
        val latCell = cellOf(lat)
        val lngCell = cellOf(lng)
        val parity = HashMap<Int, Boolean>()
        for (index in cells[key(latCell, lngCell)].orEmpty()) {
            val ring = detail.provinceRings[index]
            if (lat < ring.minLat || lat > ring.maxLat || lng < ring.minLng || lng > ring.maxLng) continue
            if (pointInDetailRing(lat.toDouble(), lng.toDouble(), ring, LOCATE_LEVEL)) {
                parity[ring.owner] = !(parity[ring.owner] ?: false)
            }
        }
        parity.entries.firstOrNull { it.value }?.let { return it.key }
        return nearestWithin(lat, lng, latCell, lngCell)
    }

    private fun nearestWithin(lat: Float, lng: Float, latCell: Int, lngCell: Int): Int {
        var best = -1
        var bestSquared = NEAR_COAST_DEG * NEAR_COAST_DEG
        for (dLat in -1..1) {
            for (dLng in -1..1) {
                for (index in cells[key(latCell + dLat, lngCell + dLng)].orEmpty()) {
                    val ring = detail.provinceRings[index]
                    var previous = -1
                    for (i in ring.lat.indices) {
                        if (ring.level[i] > LOCATE_LEVEL) continue
                        if (previous >= 0) {
                            val squared = segmentDistanceSquared(
                                lat, lng, ring.lat[previous], ring.lng[previous], ring.lat[i], ring.lng[i],
                            )
                            if (squared < bestSquared) {
                                bestSquared = squared
                                best = ring.owner
                            }
                        }
                        previous = i
                    }
                }
            }
        }
        return best
    }

    /** The squared distance, in degrees, from a point to the segment between two points. */
    private fun segmentDistanceSquared(py: Float, px: Float, ay: Float, ax: Float, by: Float, bx: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    private fun cellOf(degrees: Float): Int = floor(degrees.toDouble()).toInt()

    private fun key(latCell: Int, lngCell: Int): Int = (latCell + 91) * 400 + (lngCell + 181)

    private companion object {
        // Detail level whose simplification is a few hundred metres, close enough to tell neighbouring provinces apart.
        const val LOCATE_LEVEL = 5

        // How far (in degrees) from an outline a point still counts as being in that province.
        const val NEAR_COAST_DEG = 0.15f
    }
}
