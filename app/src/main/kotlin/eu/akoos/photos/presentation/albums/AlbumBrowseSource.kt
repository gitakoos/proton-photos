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

package eu.akoos.photos.presentation.albums

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.domain.usecase.decodeAlbumOrder
import eu.akoos.photos.presentation.search.SearchAlbums
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.retryOnDbTear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The albums and device folders the Albums tab lays out, as one stream every other album surface draws
 * from: Search's Albums row, and the photo picker's Albums view. The user's own cloud albums come from
 * the album cache alone, the device folders from the shared device listing, and the album settings
 * (hidden albums, grid exclusions, sort and custom order) as one snapshot, so a write to an unrelated
 * setting re-emits nothing.
 */
@Singleton
class AlbumBrowseSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudAlbumDao: CloudAlbumDao,
    private val driveRepo: DrivePhotoRepository,
    private val localMediaRepo: LocalMediaRepository,
) {

    /**
     * The user's own cloud albums, and the ones shared with them, as the album cache holds them. Cache only: the Albums tab keeps it
     * fresh, so a surface drawing from here adds no network call. Read again whenever that cache
     * changes, and whenever the hidden cloud photos change, which re-resolves a cover that just became
     * hidden. A guest has no cloud albums, and a later sign-in re-subscribes this, so the rows gain them
     * in place.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedCloudAlbums: Flow<Pair<List<Album>, List<Album>>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList<Album>() to emptyList())
            else combine(cloudAlbumDao.observeOwned(), driveRepo.observeHiddenAlbumMemberLinkIds()) { _, _ -> }
                .mapLatest { driveRepo.loadAlbumsCached() to driveRepo.loadSharedWithMeAlbumsCached() }
        }
        .retryOnDbTear("AlbumBrowseSource")

    /** The device folders as the Albums tab builds them, from the shared device listing rather than a
     *  scan of their own, with the vaulted photos left out and each folder's pinned cover. A guest has
     *  these too. */
    private val deviceFolders: Flow<List<DeviceFolder>> = combine(
        localMediaRepo.observeLocalMedia(),
        context.settingsDataStore.data
            .map { prefs ->
                (prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) to
                    FolderCoverMap.parse(prefs[SettingsKeys.FOLDER_COVER_URI_MAP])
            }
            .distinctUntilChanged(),
    ) { items, (hiddenUris, pinnedCovers) ->
        DeviceFolderCards.build(items, hiddenUris, pinnedCovers)
    }
        // Grouping the whole device library into folders is CPU work, kept off the main thread.
        .flowOn(Dispatchers.Default)
        // No folders until the device listing lands, so the cloud albums never wait on a scan.
        .onStart { emit(emptyList()) }
        .catch { emit(emptyList()) }

    /** What the album surfaces are drawn from. */
    internal val source: Flow<SearchAlbums.Source> = combine(
        cachedCloudAlbums,
        deviceFolders,
        context.settingsDataStore.data
            .map { prefs ->
                SearchAlbums.Source(
                    hiddenAlbumIds = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet(),
                    excluded = prefs[SettingsKeys.ALBUMS_GRID_EXCLUDED] ?: emptySet(),
                    sortMode = AlbumSortMode.fromOrdinal(prefs[SettingsKeys.ALBUMS_SORT_MODE]),
                    customOrder = decodeAlbumOrder(prefs[SettingsKeys.ALBUMS_CUSTOM_ORDER]),
                )
            }
            .distinctUntilChanged(),
    ) { (cloudAlbums, sharedAlbums), folders, settings ->
        settings.copy(cloudAlbums = cloudAlbums, sharedAlbums = sharedAlbums, deviceFolders = folders)
    }
        .catch { emit(SearchAlbums.Source()) }
}
