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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.usecase.LocatedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map pin grid both maps share, as the desktop map groups its pins: photos in one cell of the Web Mercator
 * grid at the zoom in view share a pin on their mean point, wearing the newest one. A world 360 pixels wide with
 * 10 pixel cells makes a cell exactly 10 degrees of longitude wide, and puts the equator on pixel row 180.
 */
class MapPinGridTest {

    private fun photo(id: String, lat: Double, lon: Double, takenMs: Long = 100L) = LocatedItem(
        id = id,
        latitude = lat,
        longitude = lon,
        item = GalleryItem.LocalOnly(LocalMediaItem("content://m/$id", takenMs, "$id.jpg", "image/jpeg", 1_000L, "Camera")),
    )

    private fun cloudPin(linkId: String, thumbnailUrl: String? = null) = MapPin(
        key = linkId,
        latitude = 0.0,
        longitude = 0.0,
        cover = LocatedItem(
            id = linkId,
            latitude = 0.0,
            longitude = 0.0,
            item = GalleryItem.CloudOnly(
                CloudPhoto(linkId, "share", "volume", 100L, "$linkId.jpg", "image/jpeg", 1_000L, thumbnailUrl, "rev"),
            ),
        ),
        photos = emptyList(),
    )

    @Test
    fun `a cloud cover with no thumbnail is asked for`() {
        val asked = HashSet<String>()

        val toAsk = unrequestedCloudCovers(listOf(cloudPin("c1")), emptyMap(), asked)

        assertEquals(listOf("c1"), toAsk)
    }

    @Test
    fun `a cloud cover is asked for once per map`() {
        val asked = HashSet<String>()
        unrequestedCloudCovers(listOf(cloudPin("c1")), emptyMap(), asked)

        val again = unrequestedCloudCovers(listOf(cloudPin("c1")), emptyMap(), asked)

        assertEquals(emptyList<String>(), again)
    }

    @Test
    fun `a cloud cover the store already decrypted is not asked for`() {
        val toAsk = unrequestedCloudCovers(listOf(cloudPin("c1")), mapOf("c1" to "file:///thumb_c1.jpg"), HashSet())

        assertEquals(emptyList<String>(), toAsk)
    }

    @Test
    fun `a cloud cover that carries its own thumbnail is not asked for`() {
        val toAsk = unrequestedCloudCovers(listOf(cloudPin("c1", thumbnailUrl = "file:///thumb_c1.jpg")), emptyMap(), HashSet())

        assertEquals(emptyList<String>(), toAsk)
    }

    @Test
    fun `a device photo cover is never asked for`() {
        val pin = gridPins(listOf(photo("a", 0.0, 1.0)), worldPx = 360.0, cellPx = 10.0).single()

        val toAsk = unrequestedCloudCovers(listOf(pin), emptyMap(), HashSet())

        assertEquals(emptyList<String>(), toAsk)
    }

    @Test
    fun `two photos in one cell share a pin of two`() {
        val pins = gridPins(listOf(photo("a", 0.0, 1.0), photo("b", 0.0, 2.0)), worldPx = 360.0, cellPx = 10.0)

        assertEquals(1, pins.size)
        assertEquals(2, pins[0].count)
        assertEquals(listOf("a", "b"), pins[0].photos.map { it.id })
    }

    @Test
    fun `the same two photos split into two pins at a deeper zoom`() {
        val pins = gridPins(listOf(photo("a", 0.0, 1.0), photo("b", 0.0, 2.0)), worldPx = 3600.0, cellPx = 10.0)

        assertEquals(listOf(listOf("a"), listOf("b")), pins.map { pin -> pin.photos.map { it.id } })
    }

    @Test
    fun `photos a street apart share a pin at city zoom and split at street zoom`() {
        val photos = listOf(photo("a", 38.98, 1.430), photo("b", 38.98, 1.432))

        val city = gridPins(photos, worldSizeAtZoom(12.0), PIN_CELL_DP.toDouble())
        val street = gridPins(photos, worldSizeAtZoom(19.0), PIN_CELL_DP.toDouble())

        assertEquals(1, city.size)
        assertEquals(2, street.size)
    }

    @Test
    fun `the cover is the newest photo of the pin`() {
        val photos = listOf(photo("old", 0.0, 1.0, takenMs = 100L), photo("new", 0.0, 2.0, takenMs = 300L), photo("mid", 0.0, 3.0, takenMs = 200L))

        val pin = gridPins(photos, worldPx = 360.0, cellPx = 10.0).single()

        assertEquals("new", pin.cover.id)
    }

    @Test
    fun `of two photos taken at the same time the first is the cover`() {
        val photos = listOf(photo("first", 0.0, 1.0, takenMs = 100L), photo("second", 0.0, 2.0, takenMs = 100L))

        val pin = gridPins(photos, worldPx = 360.0, cellPx = 10.0).single()

        assertEquals("first", pin.cover.id)
    }

    @Test
    fun `the pin sits on the mean of its photos`() {
        val photos = listOf(photo("a", 1.0, 1.0), photo("b", 3.0, 5.0))

        val pin = gridPins(photos, worldPx = 360.0, cellPx = 10.0).single()

        assertEquals(2.0, pin.latitude, 1e-9)
        assertEquals(3.0, pin.longitude, 1e-9)
    }

    @Test
    fun `no photos give no pins`() {
        assertTrue(gridPins(emptyList(), worldPx = 360.0, cellPx = 10.0).isEmpty())
    }

    @Test
    fun `a photo on a cell's left edge belongs to that cell`() {
        val pins = gridPins(listOf(photo("edge", 0.0, -170.0), photo("inside", 0.0, -161.0)), worldPx = 360.0, cellPx = 10.0)

        assertEquals(1, pins.size)
    }

    @Test
    fun `a photo just left of a cell's edge belongs to the cell before it`() {
        val pins = gridPins(listOf(photo("edge", 0.0, -170.0), photo("before", 0.0, -170.0001)), worldPx = 360.0, cellPx = 10.0)

        assertEquals(2, pins.size)
    }

    @Test
    fun `photos past the top of the square world share its top row`() {
        val pins = gridPins(listOf(photo("a", 89.0, 1.0), photo("b", 86.0, 2.0)), worldPx = 360.0, cellPx = 10.0)

        assertEquals(1, pins.size)
    }

    @Test
    fun `grouping the same photos at the same zoom keeps each pin's key`() {
        val photos = listOf(photo("a", 0.0, 1.0), photo("b", 40.0, 100.0), photo("c", -30.0, -60.0))

        val once = gridPins(photos, worldPx = 3600.0, cellPx = 10.0).map { it.key }
        val again = gridPins(photos, worldPx = 3600.0, cellPx = 10.0).map { it.key }

        assertEquals(once, again)
        assertEquals(3, once.toSet().size)
    }

    @Test
    fun `a pin of one photo has no count badge`() {
        assertNull(pinCountLabel(1))
    }

    @Test
    fun `a pin of many shows its count up to 999`() {
        assertEquals("2", pinCountLabel(2))
        assertEquals("999", pinCountLabel(999))
    }

    @Test
    fun `a pin of more than 999 shows 999+`() {
        assertEquals("999+", pinCountLabel(1000))
    }

    @Test
    fun `the world is 256 pixels wide at zoom 0 and doubles with each level`() {
        assertEquals(256.0, worldSizeAtZoom(0.0), 0.0)
        assertEquals(134_217_728.0, worldSizeAtZoom(19.0), 0.0)
        assertEquals(724.0773439350247, worldSizeAtZoom(1.5), 1e-9)
    }

    @Test
    fun `a globe matches the world as wide as its equator, narrower toward the poles`() {
        assertEquals(628.3185307179587, globeWorldSize(radius = 100.0, centerLat = 0.0), 1e-9)
        assertEquals(314.1592653589793, globeWorldSize(radius = 100.0, centerLat = 60.0), 1e-9)
    }
}
