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

package eu.akoos.photos.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.LocalExifBackfillScheduler
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePlacesUseCase
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.domain.usecase.PlaceCountry
import eu.akoos.photos.util.retryOnDbTear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Backs the map page. Streams the persisted [PhotoLocationEntity] rows for the primary account
 * straight from [PhotoLocationDao] so the screen can plot every located photo, and kicks the
 * one-shot GPS backfill once the permission is in hand (see [startLocalBackfill]).
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val localExifBackfillScheduler: LocalExifBackfillScheduler,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val thumbnailUrlStore: ThumbnailUrlStore,
    observePlaces: ObservePlacesUseCase,
) : ViewModel() {

    /** Live stream of every located photo for the primary account — the map's marker source. */
    val locations: StateFlow<List<PhotoLocationEntity>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            // Read the local partition when signed out, so a guest's on-device GPS fixes plot too.
            photoLocationDao.observeForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER)
        }
        .retryOnDbTear("MapLocations")
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
            // The marker thumbnail is built imperatively (outside any Compose cell), so the
            // LocalThumbnailUrls CompositionLocal can't reach it. Fold the store map into the flow
            // and stamp each cloud pin's freshly-decrypted URL onto the item, so a cloud-only fix
            // has a thumbnail source again and a decrypt that lands later repaints the pin. The set
            // is bounded by the marker cap, so the extra re-emit per store change is cheap.
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(
                locations,
                libraryFlow,
                thumbnailUrlStore.urls,
            ) { locs, library, urls ->
                val itemByKey = itemsByKey(library)
                // Drop a fix whose photo no longer exists (deleted on the device or in the cloud):
                // with no library item there's nothing behind the pin, so it would otherwise linger
                // as an empty marker. The count + city list derive from this resolved set, so they
                // stay in step too.
                locs.mapNotNull { loc ->
                    itemByKey[loc.id]?.let { MapPin(loc.id, loc.latitude, loc.longitude, resolveThumbnail(it, urls)) }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val places = observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    init {
        // A local photo can sit in the library with no pin: a fresh capture, or one revealed from the
        // vault, which comes back under a NEW MediaStore id and leaves the old id's location fix
        // behind. A one-shot walk on first open misses it, so re-read EXIF every time the local photo
        // set changes and the new id gets its fix on this view instead of only after a restart. The
        // scheduler's skip-set keeps the walk to the newcomers, and this watches the library (never the
        // location table it writes) so a fix it stores cannot re-trigger the walk.
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .flatMapLatest { userId ->
                    (if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId))
                        .map { items ->
                            items.mapNotNullTo(HashSet()) { item ->
                                when (item) {
                                    is GalleryItem.LocalOnly -> item.local.uri
                                    is GalleryItem.Synced -> item.local.uri
                                    is GalleryItem.CloudOnly -> null
                                }
                            }
                        }
                }
                .distinctUntilChanged()
                .collect { startLocalBackfill() }
        }
    }

    /**
     * The distinct cities the account's located photos were taken in, from the shared places grouping,
     * backing the bottom place search. Reuses [ObservePlacesUseCase] so the map and the Places screen
     * search the identical set; empty when signed out or before the first group completes.
     */
    val cities: StateFlow<List<PlaceCity>> = places
        .map { it?.cities.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** True once the places query has produced its first result, so the map can hold its reveal until
     *  the camera is centred on real data and open already framed instead of jumping from the world view. */
    val placesLoaded: StateFlow<Boolean> = places
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** The distinct countries the account's located photos were taken in, highlighted on the globe. */
    val countries: StateFlow<List<PlaceCountry>> = places
        .map { it?.countries.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * On-device EXIF backfill — reads GPS from local photos, which needs the ACCESS_MEDIA_LOCATION
     * grant, so the screen calls this only once the permission is in hand. Writes `photo_location`
     * keyed by content URI; self-collapsing across overlapping calls. The same walk also corrects a
     * capture date the file's own EXIF disagrees with, which the map itself does not read.
     */
    fun startLocalBackfill() {
        viewModelScope.launch {
            // No account needed: the on-device EXIF-GPS walk reads local files and stores fixes under
            // the local partition, so a guest's map fills too. The screen secures ACCESS_MEDIA_LOCATION
            // before calling this.
            val userId = accountManager.getPrimaryUserId().first()
            // respectHealthGate = false: the user opened the map and wants their pins now, so the walk
            // runs even on a low battery instead of being deferred like the background sync's call.
            withContext(Dispatchers.IO) { localExifBackfillScheduler.backfillAll(userId, respectHealthGate = false) }
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

    /** Overlay the store's freshly-decrypted thumbnail URL onto a cloud-only item so its pin has an
     *  image source (the lite feed no longer carries the URL). A Local/Synced fix already paints from
     *  its local uri, so it is returned untouched. */
    private fun resolveThumbnail(item: GalleryItem, urls: Map<String, String>): GalleryItem =
        if (item is GalleryItem.CloudOnly) {
            val url = urls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
            if (url == item.cloud.thumbnailUrl) item
            else GalleryItem.CloudOnly(item.cloud.copy(thumbnailUrl = url))
        } else {
            item
        }

    /**
     * Index the merged library by the keys a `photo_location` id can carry (a local content uri and
     * a cloud linkId), so a located row resolves to its [GalleryItem] by id. A Synced item is reachable
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
