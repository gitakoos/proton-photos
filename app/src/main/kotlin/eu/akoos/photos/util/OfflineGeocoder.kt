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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos

/**
 * On-device geocoder over a bundled GeoNames cities15000 dataset (`assets/cities15000.tsv`, ~34k towns
 * over 15k people). It turns coordinates into a coarse "City, Country" label ([reverseGeocode]) and,
 * for the metadata editor, resolves a place name back to coordinates ([searchPlaces] / [countries] /
 * [countryPoint]). Everything happens locally, so a photo's coordinates never reach any server, which
 * keeps the location lookup consistent with the app's no-third-party stance. The asset ships
 * uncompressed because the asset packager strips a `.gz` suffix and inflates the file.
 *
 * The dataset is parsed once, lazily, off the main thread and cached for the process lifetime.
 */
object OfflineGeocoder {

    /** A city match from [searchPlaces]: its display name, ISO country code, and coordinates. */
    data class GeoPlace(
        val name: String,
        val countryCode: String,
        val latitude: Double,
        val longitude: Double,
    )

    /** An ISO country present in the dataset paired with its localized display name (see [countries]). */
    data class GeoCountry(val code: String, val displayName: String)

    /** A single parsed city. The forward-lookup helpers (search / centroid / country list) run over
     *  these rows; the reverse scan keeps a parallel [FloatArray] of the coordinates so its hot
     *  nearest-neighbour loop stays on primitives. */
    internal data class CityRow(
        val name: String,
        val countryCode: String,
        val latitude: Double,
        val longitude: Double,
    )

    private class Db(
        val lat: FloatArray,
        val lon: FloatArray,
        val rows: List<CityRow>,
    )

    @Volatile private var db: Db? = null

    /**
     * Nearest city to [latitude]/[longitude] as "Name, Country", or null if the dataset can't be
     * loaded. Suspends: the first call reads + parses the bundled dataset on [Dispatchers.Default];
     * subsequent calls are a pure in-memory scan.
     */
    suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.Default) {
            val d = ensureLoaded(context.applicationContext) ?: return@withContext null
            // Equirectangular nearest-neighbour: a degree of longitude shrinks toward the poles, so
            // scale the longitude delta by cos(latitude) before comparing. Squared distance is enough
            // to rank; ~34k points is a sub-millisecond linear scan.
            val cosLat = cos(Math.toRadians(latitude))
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in d.lat.indices) {
                val dLat = d.lat[i] - latitude
                val dLon = (d.lon[i] - longitude) * cosLat
                val dist = dLat * dLat + dLon * dLon
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            if (best < 0) return@withContext null
            val row = d.rows[best]
            // ISO country code → localised country name via the platform (no extra dataset needed).
            val countryName = Locale("", row.countryCode)
                .getDisplayCountry(Locale.getDefault())
                .ifBlank { row.countryCode }
            "${row.name}, $countryName"
        }

    /**
     * The distinct countries present in the dataset, each with its localized display name, sorted by
     * that name. Empty when the dataset can't be loaded. Suspends on [Dispatchers.Default] like
     * [reverseGeocode] and reuses the same cached dataset.
     */
    suspend fun countries(context: Context): List<GeoCountry> =
        withContext(Dispatchers.Default) {
            val d = ensureLoaded(context.applicationContext) ?: return@withContext emptyList()
            distinctCountries(d.rows) { code ->
                Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
            }
        }

    /**
     * Cities whose name matches [query], case- and diacritic-insensitive, prefix matches ranked ahead
     * of substring ones. A non-null [countryCode] restricts the result to that country; [limit] caps
     * it. Empty when the dataset can't be loaded. Suspends on [Dispatchers.Default] and reuses the
     * cached dataset.
     */
    suspend fun searchPlaces(
        context: Context,
        query: String,
        countryCode: String? = null,
        limit: Int = 30,
    ): List<GeoPlace> =
        withContext(Dispatchers.Default) {
            val d = ensureLoaded(context.applicationContext) ?: return@withContext emptyList()
            searchCities(d.rows, query, countryCode, limit)
        }

    /**
     * A coarse representative coordinate for a country-only choice: the centroid (mean latitude and
     * mean longitude) of that country's cities, or null when [countryCode] has none in the dataset.
     * Suspends on [Dispatchers.Default] and reuses the cached dataset.
     */
    suspend fun countryPoint(context: Context, countryCode: String): Pair<Double, Double>? =
        withContext(Dispatchers.Default) {
            val d = ensureLoaded(context.applicationContext) ?: return@withContext null
            centroidOf(d.rows, countryCode)
        }

    private fun ensureLoaded(context: Context): Db? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            return runCatching { load(context) }.getOrNull()?.also { db = it }
        }
    }

    private fun load(context: Context): Db {
        val rows = context.assets.open("cities15000.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
            parseCityRows(reader.lineSequence())
        }
        val lats = FloatArray(rows.size)
        val lons = FloatArray(rows.size)
        for (i in rows.indices) {
            lats[i] = rows[i].latitude.toFloat()
            lons[i] = rows[i].longitude.toFloat()
        }
        return Db(lat = lats, lon = lons, rows = rows)
    }

    /**
     * Parses TSV [lines] ("name \t latitude \t longitude \t country-code") into [CityRow]s, skipping
     * blank / short / non-numeric rows. Pure and asset-free so the forward-lookup logic can be pinned
     * on a synthetic dataset without reading the bundled file.
     */
    internal fun parseCityRows(lines: Sequence<String>): List<CityRow> {
        val rows = ArrayList<CityRow>(34_000)
        for (line in lines) {
            // Each row is "name \t latitude \t longitude \t country-code".
            val parts = line.split('\t')
            if (parts.size >= 4) {
                val la = parts[1].toDoubleOrNull()
                val lo = parts[2].toDoubleOrNull()
                if (la != null && lo != null) {
                    rows.add(CityRow(name = parts[0], countryCode = parts[3], latitude = la, longitude = lo))
                }
            }
        }
        return rows
    }

    /**
     * Ranks [rows] against [query] by folded (case- and diacritic-insensitive) city name: exact match
     * first, then prefix, then substring; rows that match none are dropped. A non-blank [countryCode]
     * restricts the result to that country. Ties break by name so the order is stable. Caps at [limit].
     * Pure, so a plain JVM test pins the ranking on a synthetic dataset.
     */
    internal fun searchCities(
        rows: List<CityRow>,
        query: String,
        countryCode: String?,
        limit: Int,
    ): List<GeoPlace> {
        val q = fold(query.trim())
        if (q.isEmpty() || limit <= 0) return emptyList()
        val cc = countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        val ranked = ArrayList<Pair<Int, CityRow>>()
        for (row in rows) {
            if (cc != null && !row.countryCode.equals(cc, ignoreCase = true)) continue
            val name = fold(row.name)
            val rank = when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                else -> continue
            }
            ranked.add(rank to row)
        }
        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.name }))
            .take(limit)
            .map { (_, row) -> GeoPlace(row.name, row.countryCode, row.latitude, row.longitude) }
    }

    /**
     * Mean latitude / longitude of every [rows] entry in [countryCode] (a coarse country point), or
     * null when the dataset has no city for that country. Pure.
     */
    internal fun centroidOf(rows: List<CityRow>, countryCode: String): Pair<Double, Double>? {
        var sumLat = 0.0
        var sumLon = 0.0
        var count = 0
        for (row in rows) {
            if (row.countryCode.equals(countryCode, ignoreCase = true)) {
                sumLat += row.latitude
                sumLon += row.longitude
                count++
            }
        }
        return if (count == 0) null else (sumLat / count) to (sumLon / count)
    }

    /**
     * The distinct ISO country codes in [rows], each mapped to a display name via [displayNameFor] and
     * sorted by that name. [displayNameFor] is injected (production passes the platform locale lookup)
     * so the mapping is pinned in tests without depending on the JVM's locale data. Pure.
     */
    internal fun distinctCountries(
        rows: List<CityRow>,
        displayNameFor: (String) -> String,
    ): List<GeoCountry> =
        rows.asSequence()
            .map { it.countryCode }
            .filter { it.isNotBlank() }
            .distinct()
            .map { code -> GeoCountry(code, displayNameFor(code)) }
            .sortedBy { it.displayName }
            .toList()

    /** Lower-cases [value] and strips diacritics so "Zürich" and "zurich" compare equal. */
    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

    private val DIACRITICS = Regex("\\p{Mn}+")
}
