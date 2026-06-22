/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import java.util.Locale
import kotlin.math.cos

/**
 * On-device reverse geocoder. Turns GPS coordinates into a coarse "City, Country" label using a
 * bundled GeoNames cities15000 dataset (`assets/cities15000.tsv`, ~34k towns over 15k people). It
 * is shipped uncompressed because the asset packager strips a `.gz` suffix and inflates the file.
 * Everything happens locally — a photo's coordinates are never sent to any server — which keeps the
 * location lookup consistent with the app's no-third-party stance.
 *
 * The dataset is parsed once, lazily, off the main thread and cached for the process lifetime.
 */
object OfflineGeocoder {

    private class Db(
        val lat: FloatArray,
        val lon: FloatArray,
        val name: Array<String>,
        val country: Array<String>,
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
            // ISO country code → localised country name via the platform (no extra dataset needed).
            val countryName = Locale("", d.country[best])
                .getDisplayCountry(Locale.getDefault())
                .ifBlank { d.country[best] }
            "${d.name[best]}, $countryName"
        }

    private fun ensureLoaded(context: Context): Db? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            return runCatching { load(context) }.getOrNull()?.also { db = it }
        }
    }

    private fun load(context: Context): Db {
        val names = ArrayList<String>(34_000)
        val countries = ArrayList<String>(34_000)
        val lats = ArrayList<Float>(34_000)
        val lons = ArrayList<Float>(34_000)
        context.assets.open("cities15000.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                // Each row is "name \t latitude \t longitude \t country-code".
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val la = parts[1].toFloatOrNull()
                    val lo = parts[2].toFloatOrNull()
                    if (la != null && lo != null) {
                        names.add(parts[0])
                        lats.add(la)
                        lons.add(lo)
                        countries.add(parts[3])
                    }
                }
            }
        }
        return Db(
            lat = lats.toFloatArray(),
            lon = lons.toFloatArray(),
            name = names.toTypedArray(),
            country = countries.toTypedArray(),
        )
    }
}
