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
import eu.akoos.photos.data.repository.LocalExifBackfillScheduler
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePlacesUseCase
import eu.akoos.photos.domain.usecase.LocatedItem
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.domain.usecase.PlaceCountry
import eu.akoos.photos.domain.usecase.PlacesData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * Backs the map page. Hands both maps every located photo as a point to group into pins, the places for the
 * search and the globe's countries, and kicks the one-shot GPS backfill once the permission is in hand (see
 * [startLocalBackfill]).
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val localExifBackfillScheduler: LocalExifBackfillScheduler,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val getGalleryItems: GetGalleryItemsUseCase,
    thumbnailUrlStore: ThumbnailUrlStore,
    observePlaces: ObservePlacesUseCase,
) : ViewModel() {

    private val places = observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Every located photo of the account, one point each, null until the first result. Both maps group them
     * into pins for the zoom in view ([gridPins]); fixes at 0, 0 and rows with no library item are left out,
     * as for the places.
     */
    val points: StateFlow<List<LocatedItem>?> = observePlaces.located()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * The store's freshly decrypted cloud thumbnail URLs. A pin's thumbnail is built outside any Compose cell,
     * where the LocalThumbnailUrls CompositionLocal can't reach, so the maps read the store here and a decrypt
     * that lands later repaints the pin.
     */
    val thumbnailUrls: StateFlow<Map<String, String>> = thumbnailUrlStore.urls

    /**
     * The places the globe reads, taken from ONE emission so "loaded" and the city and country lists always agree.
     * Reading them from separate flows let the globe see "loaded" before the cities arrived and never turn to them.
     */
    internal val mapPlaces: StateFlow<MapPlaces> = places
        .map { it.toMapPlaces() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MapPlaces(false, emptyList(), emptyList()))

    /**
     * The distinct cities the account's located photos were taken in, from the shared places grouping,
     * backing the bottom place search. Reuses [ObservePlacesUseCase] so the map and the Places screen
     * search the identical set; empty when signed out or before the first group completes.
     */
    val cities: StateFlow<List<PlaceCity>> = places
        .map { it?.cities.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** The distinct countries the account's located photos were taken in, highlighted on the globe. */
    val countries: StateFlow<List<PlaceCountry>> = places
        .map { it?.countries.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

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
                // A set of every device photo, built and compared on each library change: off the main thread.
                .flowOn(Dispatchers.Default)
                .collect { startLocalBackfill() }
        }
    }

    /**
     * Decrypts the cloud thumbnails of pin covers that have none yet, at viewport priority: a cover outside the
     * warmed part of a large library is never shown by a grid, so nothing else would ask for it. The store's
     * next publish repaints those pins. A guest has no cloud photos, so nothing to ask for.
     */
    fun requestCoverThumbnails(linkIds: List<String>) {
        if (linkIds.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            drivePhotoRepository.requestThumbnailDecrypt(userId, linkIds)
        }
    }

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
     * Cloud-photo backfill: the GPS comes from each photo's own encrypted XAttr (or, lacking one, its
     * file), so it needs NO Android permission and runs unconditionally on entry (even when
     * ACCESS_MEDIA_LOCATION is denied). Writes `photo_location` keyed by cloud linkId (never colliding
     * with the local URIs); self-collapsing across overlapping calls. The walk runs on its own scope, so
     * closing the map does not stop it, and skips the health gate like the local walk: the user opened
     * the map and wants their pins now.
     */
    fun startCloudBackfill() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            drivePhotoRepository.startCloudGpsBackfill(userId, respectHealthGate = false)
        }
    }
}

/** The places grouping as the globe consumes it: [loaded] is false until the first result, when the lists are empty. */
internal data class MapPlaces(val loaded: Boolean, val cities: List<PlaceCity>, val countries: List<PlaceCountry>)

internal fun PlacesData?.toMapPlaces(): MapPlaces =
    MapPlaces(loaded = this != null, cities = this?.cities.orEmpty(), countries = this?.countries.orEmpty())
