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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the bundled world detail: the file format the build script writes is read back as written, a point is
 * found inside a ring only at the detail level that keeps its corners, and the detail level and the label zoom
 * follow the size of the globe.
 */
class WorldDetailTest {

    private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fill(buffer)
        return buffer.array()
    }

    private fun text(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return le(2 + raw.size) { it.putShort(raw.size.toShort()); it.put(raw) }
    }

    /** One country "Hungary" (HU) drawn as a box, one province "Pest" and one place "Budapest". */
    private fun sampleFile(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PWD2".toByteArray(Charsets.US_ASCII))
        // countries
        out.write(le(4) { it.putInt(1) })
        out.write(text("Hungary")); out.write("HU".toByteArray()); out.write(byteArrayOf(0)); out.write(le(8) { it.putFloat(47f); it.putFloat(19f) })
        // provinces
        out.write(le(4) { it.putInt(1) })
        out.write(text("Pest")); out.write("HU".toByteArray()); out.write(byteArrayOf(1)); out.write(le(8) { it.putFloat(47.4f); it.putFloat(19.2f) })
        // country rings: a box whose corners are level 0 and whose mid-edge point is level 3
        val lat = floatArrayOf(46f, 46f, 48f, 48f, 46f)
        val lng = floatArrayOf(16f, 22f, 22f, 16f, 16f)
        val level = byteArrayOf(0, 0, 0, 0, 0)
        out.write(le(4) { it.putInt(1) })
        out.write(le(8 + 16 + 5 * 4 * 2 + 5) {
            it.putInt(0); it.putInt(5)
            it.putFloat(46f); it.putFloat(16f); it.putFloat(48f); it.putFloat(22f)
            lat.forEach { v -> it.putFloat(v) }
            lng.forEach { v -> it.putFloat(v) }
            it.put(level)
        })
        // province rings: none
        out.write(le(4) { it.putInt(0) })
        // places
        out.write(le(4) { it.putInt(1) })
        out.write(le(16) { it.putFloat(47.5f); it.putFloat(19.04f); it.putInt(1_750_000); it.putFloat(2.5f) })
        out.write(text("Budapest"))
        return out.toByteArray()
    }

    @Test
    fun the_file_is_read_back_as_the_build_script_writes_it() {
        val detail = WorldDetailData.parse(sampleFile())
        assertEquals(1, detail.countries.size)
        assertEquals("Hungary", detail.countries[0].name)
        assertEquals("HU", detail.countries[0].iso2)
        assertEquals(47f, detail.countries[0].centreLat, 0f)
        assertEquals("Pest", detail.provinces[0].name)
        assertTrue(detail.provinces[0].cityLevel)
        assertFalse(detail.countries[0].cityLevel)
        assertEquals(1, detail.countryRings.size)
        assertEquals(5, detail.countryRings[0].lat.size)
        assertEquals(22f, detail.countryRings[0].maxLng, 0f)
        assertEquals("Budapest", detail.places[0].name)
        assertEquals(1_750_000, detail.places[0].population)
        assertEquals(2.5f, detail.places[0].labelZoom, 0f)
    }

    @Test
    fun a_file_with_the_wrong_header_is_refused() {
        val bad = sampleFile().also { it[0] = 'X'.code.toByte() }
        val failed = runCatching { WorldDetailData.parse(bad) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun a_point_is_inside_the_ring_only_when_it_lies_within_the_box() {
        val ring = WorldDetailData.parse(sampleFile()).countryRings[0]
        assertTrue(pointInDetailRing(47.0, 19.0, ring, maxLevel = 4))
        assertFalse(pointInDetailRing(49.0, 19.0, ring, maxLevel = 4))
        assertFalse(pointInDetailRing(47.0, 25.0, ring, maxLevel = 4))
    }

    @Test
    fun a_bigger_globe_draws_a_finer_detail_level() {
        assertTrue(detailLevelFor(500.0) < detailLevelFor(5_000.0))
        assertEquals(DETAIL_LEVEL_TOLERANCE_DEG.lastIndex, detailLevelFor(5_000_000.0))
        assertTrue(detailLevelFor(100.0) <= DETAIL_LEVEL_TOLERANCE_DEG.lastIndex)
    }

    @Test
    fun the_label_zoom_grows_by_one_each_time_the_globe_doubles() {
        val small = labelZoomFor(500.0, 2f)
        val doubled = labelZoomFor(1_000.0, 2f)
        assertEquals(1.0, doubled - small, 0.0001)
    }

    @Test
    fun an_outline_on_the_far_side_is_skipped_and_one_in_view_is_kept() {
        val ring = WorldDetailData.parse(sampleFile()).countryRings[0]
        val towards = GlobeView(cx = 400f, cy = 600f, radius = 500.0, cLat = 47.0, cLng = 19.0, width = 800f, height = 1200f)
        val away = GlobeView(cx = 400f, cy = 600f, radius = 500.0, cLat = -47.0, cLng = -161.0, width = 800f, height = 1200f)
        assertTrue(ringMayShow(ring, towards))
        assertFalse(ringMayShow(ring, away))
    }

    @Test
    fun an_outline_beside_a_far_zoomed_screen_is_skipped() {
        val ring = WorldDetailData.parse(sampleFile()).countryRings[0]
        // Zoomed in on Rome the screen shows well under a degree, so a box over Hungary is not in reach.
        val rome = GlobeView(cx = 400f, cy = 600f, radius = 200_000.0, cLat = 41.9, cLng = 12.5, width = 800f, height = 1200f)
        assertFalse(ringMayShow(ring, rome))
        val budapest = GlobeView(cx = 400f, cy = 600f, radius = 200_000.0, cLat = 47.5, cLng = 19.0, width = 800f, height = 1200f)
        assertTrue(ringMayShow(ring, budapest))
    }

    @Test
    fun smaller_towns_get_a_name_as_the_globe_zooms_in_and_fewer_towards_the_edge() {
        assertTrue(populationForLabel(2.0) > populationForLabel(5.0))
        assertTrue(populationForLabel(5.0) > populationForLabel(9.0))
        val centre = populationForLabelAt(6.0, 0.0)
        val edge = populationForLabelAt(6.0, 1.0)
        assertEquals(populationForLabel(6.0), centre, 0.0001)
        assertTrue(edge > centre * 5.0)
        // From far out only the very biggest cities are named.
        assertTrue(populationForLabel(2.2) > 1_000_000.0)
    }

    @Test
    fun the_bundled_file_reads_and_places_budapest_inside_hungary() {
        val file = java.io.File("src/main/assets/world_detail.bin")
        assertTrue("run from the app module", file.exists())
        val detail = WorldDetailData.parse(file.readBytes())
        assertTrue(detail.countries.size > 200)
        assertTrue(detail.provinceRings.size > 5_000)
        val hungary = detail.countries.indexOfFirst { it.iso2 == "HU" }
        assertTrue(hungary >= 0)
        val inside = detail.countryRings.filter { it.owner == hungary }
            .any { pointInDetailRing(47.4979, 19.0402, it, maxLevel = 3) }
        assertTrue(inside)
        assertTrue(detail.places.any { it.name == "Budapest" })
        val france = detail.countries.firstOrNull { it.name == "France" }
        assertEquals("FR", france?.iso2)
        val norway = detail.countries.firstOrNull { it.name == "Norway" }
        assertEquals("NO", norway?.iso2)
    }
}
