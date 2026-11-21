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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Pins [OfflineGeocoder.NearestCityIndex] to the scan of every city: the same index for every query,
 * on the bundled dataset and on synthetic sets built to tie, near the poles and at the date line.
 */
class OfflineGeocoderNearestTest {

    private fun assertSameAsScan(lat: FloatArray, lon: FloatArray, queries: List<Pair<Double, Double>>) {
        val index = OfflineGeocoder.NearestCityIndex(lat, lon)
        for ((qLat, qLon) in queries) {
            assertEquals(
                "query $qLat,$qLon",
                OfflineGeocoder.nearestByScan(lat, lon, qLat, qLon),
                index.nearest(qLat, qLon),
            )
        }
    }

    @Test
    fun bundledDatasetMatchesTheScan() {
        val file = File("src/main/assets/cities15000.tsv")
        assertTrue("dataset at ${file.absolutePath}", file.exists())
        val rows = OfflineGeocoder.parseCityRows(file.readLines(Charsets.UTF_8).asSequence())
        val lat = FloatArray(rows.size) { rows[it].latitude.toFloat() }
        val lon = FloatArray(rows.size) { rows[it].longitude.toFloat() }
        val random = Random(7)
        // Uniform over the globe, plus points right on a city and a little off one.
        val queries = ArrayList<Pair<Double, Double>>()
        repeat(4_000) { queries += random.nextDouble(-90.0, 90.0) to random.nextDouble(-180.0, 180.0) }
        repeat(4_000) {
            val i = random.nextInt(rows.size)
            queries += lat[i].toDouble() to lon[i].toDouble()
            queries += (rows[i].latitude + random.nextDouble(-0.05, 0.05)) to (rows[i].longitude + random.nextDouble(-0.05, 0.05))
        }
        assertSameAsScan(lat, lon, queries)
    }

    @Test
    fun tiesGoToTheLowestIndexLikeTheScan() {
        // Duplicates and points the same distance away in every direction.
        val lat = floatArrayOf(10f, 10f, 11f, 9f, 10f, 10f, 11f, 9f, 10f)
        val lon = floatArrayOf(20f, 20f, 20f, 20f, 21f, 19f, 20f, 20f, 20f)
        val queries = listOf(10.0 to 20.0, 10.5 to 20.0, 9.5 to 20.0, 10.0 to 20.5, 10.0 to 19.5, 12.0 to 20.0, 8.0 to 20.0)
        assertSameAsScan(lat, lon, queries)
    }

    @Test
    fun polesDateLineAndGridsMatchTheScan() {
        val random = Random(11)
        // A coarse grid (many equal latitudes) and clusters near the poles and the date line.
        val points = ArrayList<Pair<Float, Float>>()
        for (a in -8..8) for (b in -17..17) points += (a * 10f) to (b * 10f)
        repeat(500) { points += random.nextDouble(84.0, 90.0).toFloat() to random.nextDouble(-180.0, 180.0).toFloat() }
        repeat(500) { points += random.nextDouble(-90.0, -84.0).toFloat() to random.nextDouble(-180.0, 180.0).toFloat() }
        repeat(500) { points += random.nextDouble(-60.0, 60.0).toFloat() to random.nextDouble(175.0, 180.0).toFloat() }
        val lat = FloatArray(points.size) { points[it].first }
        val lon = FloatArray(points.size) { points[it].second }
        val queries = ArrayList<Pair<Double, Double>>()
        repeat(3_000) { queries += random.nextDouble(-90.0, 90.0) to random.nextDouble(-180.0, 180.0) }
        for (a in -9..9) for (b in -18..18) queries += (a * 10.0) to (b * 10.0)
        queries += 90.0 to 0.0
        queries += -90.0 to 179.9
        assertSameAsScan(lat, lon, queries)
    }

    @Test
    fun emptyAndNonFiniteGiveNothing() {
        assertEquals(-1, OfflineGeocoder.NearestCityIndex(FloatArray(0), FloatArray(0)).nearest(1.0, 2.0))
        val index = OfflineGeocoder.NearestCityIndex(floatArrayOf(1f), floatArrayOf(2f))
        assertEquals(-1, index.nearest(Double.NaN, 2.0))
        assertEquals(-1, index.nearest(1.0, Double.POSITIVE_INFINITY))
        assertEquals(0, index.nearest(1.0, 2.0))
    }
}
