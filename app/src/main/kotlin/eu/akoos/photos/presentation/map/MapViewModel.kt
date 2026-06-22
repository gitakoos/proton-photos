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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.GpsBackfillScheduler
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.util.OfflineGeocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Backs the map page. Streams the persisted [PhotoLocationEntity] rows for the primary account
 * straight from [PhotoLocationDao] so the screen can plot every located photo, and kicks the
 * one-shot GPS backfill once the permission is in hand (see [startBackfill]).
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val gpsBackfillScheduler: GpsBackfillScheduler,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val getGalleryItems: GetGalleryItemsUseCase,
) : ViewModel() {

    /** Live stream of every located photo for the primary account — the map's marker source. */
    val locations: StateFlow<List<PhotoLocationEntity>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else photoLocationDao.observeForUser(userId.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The located rows enriched with the [GalleryItem] each id resolves to in the merged library, so
     * every marker has a thumbnail source — local fixes carry their content uri, cloud fixes their
     * cloud thumbnail, both loaded through the same Coil path as the gallery. Combines [locations]
     * with the shared library merge; an id with no library match yet yields a null item (placeholder
     * pin) until the merge catches up.
     */
    val pins: StateFlow<List<MapPin>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(locations, getGalleryItems.invoke(userId)) { locs, library ->
                val itemByKey = itemsByKey(library)
                // Drop a fix whose photo no longer exists (deleted on the device or in the cloud):
                // with no library item there's nothing behind the pin, so it would otherwise linger
                // as an empty marker. The count + city list derive from this resolved set, so they
                // stay in step too.
                locs.mapNotNull { loc ->
                    itemByKey[loc.id]?.let { MapPin(loc.id, loc.latitude, loc.longitude, it) }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The distinct cities the account's located photos were taken in — the source for the city
     * search overlay. Every [PhotoLocationEntity] is reverse-geocoded to a "City, Country" label off
     * the main thread (the geocoder caches its dataset after the first lookup), grouped by that label
     * into a [CityEntry] carrying a representative coordinate (the mean of the group's fixes) and the
     * photo count, sorted by count descending so the busiest places lead. Derived from [pins] (the
     * resolved set), so a deleted location drops out of the city list too; emits empty until the
     * first pass completes.
     */
    val cities: StateFlow<List<CityEntry>> = pins
        .mapLatest { pinList ->
            val byLabel = LinkedHashMap<String, MutableList<MapPin>>()
            for (pin in pinList) {
                val label = OfflineGeocoder.reverseGeocode(context, pin.latitude, pin.longitude)
                    ?: continue
                byLabel.getOrPut(label) { ArrayList() }.add(pin)
            }
            byLabel.map { (label, rows) ->
                CityEntry(
                    name = label,
                    latitude = rows.sumOf { it.latitude } / rows.size,
                    longitude = rows.sumOf { it.longitude } / rows.size,
                    count = rows.size,
                )
            }.sortedByDescending { it.count }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * On-device EXIF backfill — reads GPS from local photos, which needs the ACCESS_MEDIA_LOCATION
     * grant, so the screen calls this only once the permission is in hand. Writes `photo_location`
     * keyed by content URI; self-collapsing across overlapping calls.
     */
    fun startLocalBackfill() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            withContext(Dispatchers.IO) { gpsBackfillScheduler.backfillAll(userId) }
        }
    }

    /**
     * Cloud-photo backfill — the GPS comes from each photo's own encrypted XAttr, so it needs NO
     * Android permission and runs unconditionally on entry (even when ACCESS_MEDIA_LOCATION is
     * denied). Writes `photo_location` keyed by cloud linkId (never colliding with the local URIs);
     * self-collapsing across overlapping calls.
     */
    fun startCloudBackfill() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            withContext(Dispatchers.IO) { drivePhotoRepository.backfillCloudGps(userId) }
        }
    }

    /**
     * Index the merged library by the keys a `photo_location` id can carry — a local content uri and
     * a cloud linkId — so a located row resolves to its [GalleryItem] by id. A Synced item is reachable
     * by both keys; mirrors the resolver in the location-detail screen.
     */
    private fun itemsByKey(library: List<GalleryItem>): Map<String, GalleryItem> {
        val itemByKey = HashMap<String, GalleryItem>(library.size * 2)
        for (item in library) {
            when (item) {
                is GalleryItem.LocalOnly -> itemByKey[item.local.uri] = item
                is GalleryItem.Synced -> {
                    itemByKey[item.local.uri] = item
                    itemByKey[item.cloud.linkId] = item
                }
                is GalleryItem.CloudOnly -> itemByKey[item.cloud.linkId] = item
            }
        }
        return itemByKey
    }
}
