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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.db.entity.isNullIsland
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.OfflineGeocoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import me.proton.core.accountmanager.domain.AccountManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams the account's photos grouped into countries and cities for the Places surfaces, reusing the
 * same location rows and merged library the map plots from. Every located photo resolves to its
 * [GalleryItem] (so a place card has a cover and a "newest" order) and is reverse-geocoded off the main
 * thread to a city + country; the grouping runs in [groupPlaces].
 *
 * A per-location label cache makes a re-emit cheap: the coordinate is stable for a `photo_location` id,
 * so once a row is geocoded a later emit (a library or thumbnail change) reuses the label instead of
 * rescanning the dataset. A signed-out session has no location rows, so Places is simply empty.
 *
 * One instance and one grouping for the whole app: the Collection, Places and the map read the same
 * stream, and the label cache outlives the screens, so opening one of them again reuses every label.
 */
@Singleton
class ObservePlacesUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val thumbnailUrlStore: ThumbnailUrlStore,
    @AppScope private val appScope: CoroutineScope,
) {
    private val labelCache = ConcurrentHashMap<String, PlaceLabelParts>()

    /** The locale the cached labels' country names are in; a language change empties the cache. */
    @Volatile private var labelLocale: Locale? = null

    operator fun invoke(): Flow<PlacesData> = shared

    // Dropped a few seconds after the last screen leaves, and nothing replayed after that, so a later
    // open (or another account) groups the current rows; the labels stay in [labelCache].
    private val shared: Flow<PlacesData> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            // Read the local partition when signed out, so a guest's places populate too.
            val locationFlow = photoLocationDao.observeForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER)
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
            else getGalleryItems.invoke(userId)
            combine(locationFlow, libraryFlow) { locations, library ->
                val locale = Locale.getDefault()
                if (labelLocale != locale) {
                    labelCache.clear()
                    labelLocale = locale
                }
                val itemByKey = itemsByKey(library)
                val located = locations.filterNot { it.isNullIsland() }.mapNotNull { loc ->
                    itemByKey[loc.id]?.let { LocatedItem(loc.id, loc.latitude, loc.longitude, it) }
                }
                val labels = HashMap<String, PlaceLabelParts?>(located.size)
                for (item in located) labels[item.id] = labelFor(item)
                groupPlaces(located) { labels[it.id] }
            }
                // The store's URLs go onto the covers only, one per city and country, after the grouping: a
                // thumbnail that lands no longer regroups every located photo of the library. A cover is
                // picked by capture time, which a URL does not change, so the result is the same.
                .combine(thumbnailUrlStore.urls) { grouped, urls -> grouped.withCoverUrls(urls) }
        }
        .flowOn(Dispatchers.Default)
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    private suspend fun labelFor(item: LocatedItem): PlaceLabelParts? {
        labelCache[item.id]?.let { return it }
        val geo = OfflineGeocoder.reverseGeocodeDetailed(context, item.latitude, item.longitude)
            ?: return null
        return PlaceLabelParts(geo.city, geo.countryCode, geo.countryName).also { labelCache[item.id] = it }
    }

    /** Index the merged library by the keys a `photo_location` id can carry (a local content uri and a
     *  cloud linkId), so a located row resolves to its item by id. */
    private fun itemsByKey(library: List<GalleryItem>): Map<String, GalleryItem> {
        val byKey = HashMap<String, GalleryItem>(library.size * 2)
        for (item in library) {
            when (item) {
                is GalleryItem.LocalOnly -> byKey[item.local.uri] = item
                is GalleryItem.Synced -> {
                    byKey[item.local.uri] = item
                    byKey[item.cloud.linkId] = item
                }
                is GalleryItem.CloudOnly -> byKey[item.cloud.linkId] = item
            }
        }
        return byKey
    }
}

/**
 * These places with the store's freshly-decrypted thumbnail URL set on each cloud-only cover, so the cover
 * has an image source; a device or synced cover already paints from its local uri. A cover with no new
 * URL is handed back as it is.
 */
internal fun PlacesData.withCoverUrls(urls: Map<String, String>): PlacesData = PlacesData(
    countries = countries.map { country ->
        val cover = country.cover?.withStoreUrl(urls)
        if (cover === country.cover) country else country.copy(cover = cover)
    },
    cities = cities.map { city ->
        val cover = city.cover?.withStoreUrl(urls)
        if (cover === city.cover) city else city.copy(cover = cover)
    },
)

private fun GalleryItem.withStoreUrl(urls: Map<String, String>): GalleryItem {
    if (this !is GalleryItem.CloudOnly) return this
    val url = urls[cloud.linkId] ?: cloud.thumbnailUrl
    return if (url == cloud.thumbnailUrl) this else GalleryItem.CloudOnly(cloud.copy(thumbnailUrl = url))
}
