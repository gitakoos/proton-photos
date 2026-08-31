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
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.OfflineGeocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import me.proton.core.accountmanager.domain.AccountManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Streams the account's photos grouped into countries and cities for the Places surfaces, reusing the
 * same location rows and merged library the map plots from. Every located photo resolves to its
 * [GalleryItem] (so a place card has a cover and a "newest" order) and is reverse-geocoded off the main
 * thread to a city + country; the grouping runs in [groupPlaces].
 *
 * A per-location label cache makes a re-emit cheap: the coordinate is stable for a `photo_location` id,
 * so once a row is geocoded a later emit (a library or thumbnail change) reuses the label instead of
 * rescanning the dataset. A signed-out session has no location rows, so Places is simply empty.
 */
class ObservePlacesUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val thumbnailUrlStore: ThumbnailUrlStore,
) {
    private val labelCache = ConcurrentHashMap<String, PlaceLabelParts>()

    operator fun invoke(): Flow<PlacesData> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            val locationFlow = if (userId == null) flowOf(emptyList())
            else photoLocationDao.observeForUser(userId.id)
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
            else getGalleryItems.invoke(userId)
            combine(locationFlow, libraryFlow, thumbnailUrlStore.urls) { locations, library, urls ->
                val itemByKey = itemsByKey(library)
                val located = locations.mapNotNull { loc ->
                    itemByKey[loc.id]?.let {
                        LocatedItem(loc.id, loc.latitude, loc.longitude, resolveThumbnail(it, urls))
                    }
                }
                val labels = HashMap<String, PlaceLabelParts?>(located.size)
                for (item in located) labels[item.id] = labelFor(item)
                groupPlaces(located) { labels[it.id] }
            }
        }
        .flowOn(Dispatchers.Default)

    private suspend fun labelFor(item: LocatedItem): PlaceLabelParts? {
        labelCache[item.id]?.let { return it }
        val geo = OfflineGeocoder.reverseGeocodeDetailed(context, item.latitude, item.longitude)
            ?: return null
        return PlaceLabelParts(geo.city, geo.countryCode, geo.countryName).also { labelCache[item.id] = it }
    }

    /** Overlay the store's freshly-decrypted thumbnail URL onto a cloud-only item so its cover has an
     *  image source; a Local/Synced item already paints from its local uri. */
    private fun resolveThumbnail(item: GalleryItem, urls: Map<String, String>): GalleryItem =
        if (item is GalleryItem.CloudOnly) {
            val url = urls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
            if (url == item.cloud.thumbnailUrl) item
            else GalleryItem.CloudOnly(item.cloud.copy(thumbnailUrl = url))
        } else {
            item
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
