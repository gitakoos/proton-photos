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

package eu.akoos.photos.presentation.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.countryName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import me.proton.core.accountmanager.domain.AccountManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The place names a typed word is matched against, per photo: the city the details sheet shows for the
 * photo's stored coordinates and its country, folded for matching. Built from the same `photo_location`
 * rows the map plots and the same bundled offline geocoder the details sheet and Places use, so nothing
 * new is stored and no coordinate leaves the device.
 *
 * Photos taken within the same few hundred metres share one geocode (a [cell]), and the answer of a cell
 * is kept for the process, so a library of tens of thousands of photos costs one scan per distinct spot
 * the first time and a lookup after. A signed-out session reads the local partition, so a guest finds
 * places too.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoPlaceIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
) {
    private val cellLabels = ConcurrentHashMap<Long, Pair<String, String>>()

    /**
     * Each located photo's folded place text (city, country in [locale] and in English) by its
     * `photo_location` id, a device uri or a cloud linkId. Re-emits when the stored coordinates change,
     * as the background location read fills them in.
     */
    fun observe(locale: Locale): Flow<Map<String, String>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId -> photoLocationDao.observeForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER) }
        .mapLatest { rows -> textFor(rows, locale) }
        .flowOn(Dispatchers.Default)

    private suspend fun textFor(rows: List<PhotoLocationEntity>, locale: Locale): Map<String, String> {
        val textByCell = HashMap<Long, String>()
        val out = HashMap<String, String>(rows.size)
        for (row in rows) {
            val key = cell(row.latitude, row.longitude)
            var text = textByCell[key]
            if (text == null) {
                var label = cellLabels[key]
                if (label == null) {
                    val geo = OfflineGeocoder.reverseGeocodeDetailed(context, row.latitude, row.longitude)
                        ?: return out
                    label = geo.city to geo.countryCode
                    cellLabels[key] = label
                }
                text = placeText(label.first, label.second, locale)
                textByCell[key] = text
            }
            out[row.id] = text
        }
        return out
    }

    internal companion object {
        /** Cells of 0.005 degrees, about 500 m: finer than the cities the geocoder names, coarse enough
         *  that a burst of photos from one spot is geocoded once. */
        private const val CELLS_PER_DEGREE = 200

        fun cell(latitude: Double, longitude: Double): Long {
            val la = Math.round((latitude + 90.0) * CELLS_PER_DEGREE)
            val lo = Math.round((longitude + 180.0) * CELLS_PER_DEGREE)
            return la * 1_000_000L + lo
        }

        /** [city] and the country of [code] named in [locale] and in English, folded and joined, so
         *  "budapest", "hungary" and "magyarorszag" all reach a photo taken there. */
        fun placeText(city: String, code: String, locale: Locale): String {
            val local = countryName(code, locale)
            val english = countryName(code, Locale.ENGLISH)
            return listOf(city, local, english)
                .map { SearchFilter.fold(it.trim()) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
        }
    }
}
