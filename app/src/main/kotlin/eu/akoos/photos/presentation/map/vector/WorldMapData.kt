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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A geographic coordinate in degrees. */
data class GeoCoord(val lat: Double, val lng: Double)

/** One country's outline: its ISO A2 code, display name, a representative centre (for a label and for
 *  culling the far hemisphere), and every linear ring (outer boundaries and holes together). */
data class CountryShape(
    val iso2: String,
    val name: String,
    val centroid: GeoCoord,
    val rings: List<List<GeoCoord>>,
)

/** The parsed base map: just the country outlines the globe draws. Which of them are highlighted, and
 *  every city label, come from the account's own data, not from here. */
data class WorldMap(val countries: List<CountryShape>)

/**
 * Parses the bundled Natural Earth country GeoJSON (public domain, `world_countries.geojson`) into plain
 * coordinate lists the globe draws from. Parsed once off the main thread and cached for the process
 * lifetime. GeoJSON stores coordinates as [longitude, latitude], unpacked here into [GeoCoord]; points
 * closer than [MIN_STEP_DEG] to the previous one are dropped to thin dense coastlines.
 */
object WorldMapData {

    @Volatile private var cached: WorldMap? = null

    suspend fun load(context: Context): WorldMap = withContext(Dispatchers.Default) {
        cached?.let { return@withContext it }
        synchronized(this) {
            cached?.let { return@withContext it }
            val app = context.applicationContext
            val world = runCatching {
                WorldMap(parseCountries(readAsset(app, "world_countries.geojson")))
            }.getOrDefault(WorldMap(emptyList()))
            cached = world
            world
        }
    }

    /** The parsed map if it is already cached, else null; lets a preview draw the globe on its first
     *  frame instead of an asynchronous load flashing it in after the screen appears. */
    fun cachedOrNull(): WorldMap? = cached

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parseCountries(json: String): List<CountryShape> {
        val features = JSONObject(json).getJSONArray("features")
        val out = ArrayList<CountryShape>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val props = feature.getJSONObject("properties")
            val iso2 = props.optString("ISO_A2_EH").takeIf { it.isNotBlank() && it != "-99" }
                ?: props.optString("ISO_A2")
            val name = props.optString("NAME").ifBlank { props.optString("ADMIN") }
            val geometry = feature.optJSONObject("geometry") ?: continue
            val rings = ArrayList<List<GeoCoord>>()
            when (geometry.optString("type")) {
                "Polygon" -> addPolygon(geometry.getJSONArray("coordinates"), rings)
                "MultiPolygon" -> {
                    val polys = geometry.getJSONArray("coordinates")
                    for (p in 0 until polys.length()) addPolygon(polys.getJSONArray(p), rings)
                }
            }
            if (rings.isNotEmpty()) {
                out.add(CountryShape(iso2.uppercase(), name, centroidOf(rings), rings))
            }
        }
        return out
    }

    /** A GeoJSON polygon is an array of rings; each ring is an array of [lng, lat] pairs. */
    private fun addPolygon(polygon: org.json.JSONArray, into: MutableList<List<GeoCoord>>) {
        for (r in 0 until polygon.length()) {
            val ring = polygon.getJSONArray(r)
            val last = ring.length() - 1
            val points = ArrayList<GeoCoord>(ring.length())
            var keptLat = Double.NaN
            var keptLng = Double.NaN
            for (i in 0 until ring.length()) {
                val pt = ring.getJSONArray(i)
                val lat = pt.getDouble(1)
                val lng = pt.getDouble(0)
                if (points.isNotEmpty() && i != last) {
                    val dLat = lat - keptLat
                    val dLng = lng - keptLng
                    if (dLat * dLat + dLng * dLng < MIN_STEP_SQ) continue
                }
                points.add(GeoCoord(lat, lng))
                keptLat = lat
                keptLng = lng
            }
            if (points.size >= 2) into.add(points)
        }
    }

    private fun centroidOf(rings: List<List<GeoCoord>>): GeoCoord {
        val ring = rings.maxByOrNull { it.size } ?: return GeoCoord(0.0, 0.0)
        var lat = 0.0
        var lng = 0.0
        for (p in ring) { lat += p.lat; lng += p.lng }
        return GeoCoord(lat / ring.size, lng / ring.size)
    }

    private const val MIN_STEP_DEG = 0.12
    private val MIN_STEP_SQ = MIN_STEP_DEG * MIN_STEP_DEG
}
