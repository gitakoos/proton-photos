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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.LocatedItem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The photos of one grid cell at the current zoom, plotted as one pin: it sits on the mean point of its
 * [photos] and wears the newest of them, [cover], as its thumbnail. [key] names the cell, so it is stable
 * for as long as the zoom stays.
 */
data class MapPin(
    val key: String,
    val latitude: Double,
    val longitude: Double,
    val cover: LocatedItem,
    val photos: List<LocatedItem>,
) {
    val count: Int get() = photos.size
}

/** How close, in screen pixels (as dp) at the current zoom, two photos are to share a pin. */
internal const val PIN_CELL_DP = 76f

/** How long after a zoom's last step the pins are grouped again for the new zoom. */
internal const val REGROUP_AFTER_MS = 180L

/** The width in pixels of one map tile, the size of the whole world at zoom 0. */
private const val TILE_PX = 256.0

/** The latitude where the square Web Mercator world ends. */
private const val MAX_MERCATOR_LAT = 85.05112878

/** The width of the whole Web Mercator world, in pixels, at a [zoom] level (fractional zooms included). */
internal fun worldSizeAtZoom(zoom: Double): Double = TILE_PX * 2.0.pow(zoom)

/**
 * The Web Mercator world size that matches a globe of [radius] pixels looked at around [centerLat]: one
 * Mercator pixel there covers as much ground as one pixel at the middle of the globe, so a cell is as wide on
 * the globe as on the tile map.
 */
internal fun globeWorldSize(radius: Double, centerLat: Double): Double =
    2.0 * PI * radius * cos(Math.toRadians(centerLat)).coerceAtLeast(MIN_LATITUDE_SCALE)

private const val MIN_LATITUDE_SCALE = 0.1

/**
 * The located [photos] grouped into pins: each is projected to the Web Mercator world [worldPx] pixels wide
 * and dropped into a grid of [cellPx] square cells, one pin per cell that holds a photo. The grid is fixed to
 * the world, not the screen, so a pan never regroups, and a zoom in splits a pin into the cells its photos
 * fall in. Every pin sits on the mean of its photos and wears the newest one (the first of a tie).
 */
internal fun gridPins(photos: List<LocatedItem>, worldPx: Double, cellPx: Double): List<MapPin> {
    val cells = LinkedHashMap<Long, MutableList<LocatedItem>>()
    for (photo in photos) {
        val column = floor(mercatorX(photo.longitude, worldPx) / cellPx).toInt()
        val row = floor(mercatorY(photo.latitude, worldPx) / cellPx).toInt()
        val key = (column.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)
        cells.getOrPut(key) { ArrayList() }.add(photo)
    }
    return cells.map { (key, members) ->
        var latitude = 0.0
        var longitude = 0.0
        var newest = members[0]
        for (photo in members) {
            latitude += photo.latitude
            longitude += photo.longitude
            if (photo.item.captureTimeMs > newest.item.captureTimeMs) newest = photo
        }
        MapPin(
            key = "${(key shr 32).toInt()}:${key.toInt()}",
            latitude = latitude / members.size,
            longitude = longitude / members.size,
            cover = newest,
            photos = members,
        )
    }
}

/** The count a pin's badge shows, or null for a pin of one photo, which wears none. */
internal fun pinCountLabel(count: Int): String? = when {
    count <= 1 -> null
    count > 999 -> "999+"
    else -> count.toString()
}

internal fun mercatorX(longitude: Double, worldPx: Double): Double = (longitude + 180.0) / 360.0 * worldPx

internal fun mercatorY(latitude: Double, worldPx: Double): Double {
    val sinLat = sin(Math.toRadians(latitude.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)))
    return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * worldPx
}

/**
 * The cloud-only covers of [pins] with no decrypted thumbnail in [thumbnailUrls] that [asked] does not hold yet,
 * each added to [asked] so a cover is asked for once per map.
 */
internal fun unrequestedCloudCovers(
    pins: List<MapPin>,
    thumbnailUrls: Map<String, String>,
    asked: MutableSet<String>,
): List<String> = pins.mapNotNull { pin ->
    val cloud = (pin.cover.item as? GalleryItem.CloudOnly)?.cloud ?: return@mapNotNull null
    cloud.linkId.takeIf { it !in thumbnailUrls && cloud.thumbnailUrl == null && asked.add(it) }
}
