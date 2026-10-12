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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A country or a province: its name, ISO A2 code (`--` when it has none) and a point inside its main part.
 * [cityLevel] marks a city carved out of the province around it (a Hungarian city with county rights), which
 * belongs to that province when the globe tints where the account has been.
 */
internal class DetailArea(
    val name: String,
    val iso2: String,
    val centreLat: Float,
    val centreLng: Float,
    val cityLevel: Boolean = false,
)

/**
 * One outline of a country or province. [lat] and [lng] are its points; [level] says from which detail level on
 * each point is drawn (see [DETAIL_LEVEL_TOLERANCE_DEG]). The box is for skipping rings that are off screen.
 */
internal class DetailRing(
    val owner: Int,
    val minLat: Float,
    val minLng: Float,
    val maxLat: Float,
    val maxLng: Float,
    val lat: FloatArray,
    val lng: FloatArray,
    val level: ByteArray,
) {
    /** The box's centre, as sine and cosine of its latitude and as a longitude, in radians. */
    val midLatSin: Double
    val midLatCos: Double
    val midLngRad: Double

    /** Half the box's diagonal as an angle in radians: how far from its centre the outline can reach. */
    val reachRad: Double

    private var trigCache: RingTrig? = null

    /**
     * The sine and cosine of every point's latitude and longitude, worked out the first time the outline is drawn,
     * so each frame projects a point with a few multiplications instead of four trigonometric calls. Outlines never
     * drawn never pay for it.
     */
    fun trig(): RingTrig = trigCache ?: RingTrig(lat, lng).also { trigCache = it }

    init {
        val midLat = Math.toRadians((minLat + maxLat) / 2.0)
        midLatSin = kotlin.math.sin(midLat)
        midLatCos = kotlin.math.cos(midLat)
        midLngRad = Math.toRadians((minLng + maxLng) / 2.0)
        reachRad = Math.toRadians(
            kotlin.math.hypot((maxLat - minLat).toDouble(), (maxLng - minLng) * midLatCos),
        ) / 2.0
    }
}

/** The sine and cosine of each point's latitude and longitude of one outline. */
internal class RingTrig(lat: FloatArray, lng: FloatArray) {
    val sinLat = FloatArray(lat.size) { kotlin.math.sin(Math.toRadians(lat[it].toDouble())).toFloat() }
    val cosLat = FloatArray(lat.size) { kotlin.math.cos(Math.toRadians(lat[it].toDouble())).toFloat() }
    val sinLng = FloatArray(lng.size) { kotlin.math.sin(Math.toRadians(lng[it].toDouble())).toFloat() }
    val cosLng = FloatArray(lng.size) { kotlin.math.cos(Math.toRadians(lng[it].toDouble())).toFloat() }
}

/** A populated place for the globe's labels: [labelZoom] is the map zoom from which its name is shown. */
internal class DetailPlace(val name: String, val lat: Float, val lng: Float, val population: Int, val labelZoom: Float)

/** The detailed base map: country and province outlines at several detail levels, and the populated places. */
internal class WorldDetail(
    val countries: List<DetailArea>,
    val provinces: List<DetailArea>,
    val countryRings: List<DetailRing>,
    val provinceRings: List<DetailRing>,
    val places: List<DetailPlace>,
)

/**
 * Reads the bundled `world_detail.bin`, built from Natural Earth 10m data (public domain) by
 * `tools/world_data/build_world_data.py`, which documents the format. Parsed once off the main thread and kept
 * for the life of the process.
 */
internal object WorldDetailData {

    @Volatile private var cached: WorldDetail? = null

    suspend fun load(context: Context): WorldDetail? = withContext(Dispatchers.Default) {
        cached?.let { return@withContext it }
        synchronized(this@WorldDetailData) {
            cached?.let { return@withContext it }
            val parsed = runCatching {
                context.applicationContext.assets.open("world_detail.bin").use { parse(it.readBytes()) }
            }.getOrNull()
            cached = parsed
            parsed
        }
    }

    internal fun parse(bytes: ByteArray): WorldDetail {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "not a world detail file" }
        val countries = readAreas(buffer)
        val provinces = readAreas(buffer)
        val countryRings = readRings(buffer)
        val provinceRings = readRings(buffer)
        val placeCount = buffer.int
        val places = ArrayList<DetailPlace>(placeCount)
        repeat(placeCount) {
            val lat = buffer.float
            val lng = buffer.float
            val population = buffer.int
            val labelZoom = buffer.float
            places += DetailPlace(readText(buffer), lat, lng, population, labelZoom)
        }
        return WorldDetail(countries, provinces, countryRings, provinceRings, places)
    }

    private fun readAreas(buffer: ByteBuffer): List<DetailArea> {
        val count = buffer.int
        val areas = ArrayList<DetailArea>(count)
        repeat(count) {
            val name = readText(buffer)
            val iso = String(CharArray(2) { buffer.get().toInt().toChar() })
            val flags = buffer.get().toInt()
            areas += DetailArea(name, iso, buffer.float, buffer.float, cityLevel = flags and 1 != 0)
        }
        return areas
    }

    private fun readRings(buffer: ByteBuffer): List<DetailRing> {
        val count = buffer.int
        val rings = ArrayList<DetailRing>(count)
        repeat(count) {
            val owner = buffer.int
            val n = buffer.int
            val minLat = buffer.float
            val minLng = buffer.float
            val maxLat = buffer.float
            val maxLng = buffer.float
            val lat = FloatArray(n)
            val lng = FloatArray(n)
            val level = ByteArray(n)
            for (i in 0 until n) lat[i] = buffer.float
            for (i in 0 until n) lng[i] = buffer.float
            buffer.get(level)
            rings += DetailRing(owner, minLat, minLng, maxLat, maxLng, lat, lng, level)
        }
        return rings
    }

    private fun readText(buffer: ByteBuffer): String {
        val length = buffer.short.toInt() and 0xFFFF
        val raw = ByteArray(length)
        buffer.get(raw)
        return String(raw, Charsets.UTF_8)
    }

    // "PWD2" read as a little-endian int.
    private const val MAGIC = 0x32445750
}
