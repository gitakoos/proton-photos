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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.albums.AlbumsUiState

/**
 * Top-of-screen rail for normal (non-selection) mode: the per-tab filter rail (Photos / Albums /
 * Shared) and the Avatar/Settings button.
 *
 * @param onHeaderMeasured invoked with the header's pixel height on each layout, so the caller can
 *   offset the photo grid by the same amount.
 */
@Composable
internal fun GalleryHeader(
    selectedTab: Int,
    galleryState: GalleryUiState,
    albumsState: AlbumsUiState,
    sharedFilter: SharedFilter,
    activeEmailFilter: String?,
    isOnlineNow: Boolean,
    /** Photos-tab search: the active terms shown in the bar, opening the search page, and clearing
     *  every term at once. */
    photosSearchTerms: List<SearchTerm> = emptyList(),
    onOpenPhotosSearch: () -> Unit = {},
    onClearPhotosSearch: () -> Unit = {},
    /** The ⋮ menus' settings pages, the same ones Settings → Library opens. */
    onOpenTimelineSettings: () -> Unit = {},
    onOpenAlbumsSettings: () -> Unit = {},
    onHiddenAlbumClick: () -> Unit,
    /** Albums-tab inline search: the query, whether the rail's search bar is open, and their setters. */
    albumQuery: String = "",
    onAlbumQueryChange: (String) -> Unit = {},
    albumSearchActive: Boolean = false,
    onAlbumSearchActiveChange: (Boolean) -> Unit = {},
    /** True while the Albums page is in arrange mode, which hides the search entry in the rail. */
    albumReorderActive: Boolean = false,
    onSharedFilterSelected: (SharedFilter) -> Unit,
    onShowSharedEmailSheet: () -> Unit,
    onClearSharedEmailFilter: () -> Unit,
    onSettingsClick: () -> Unit,
    onHeaderMeasured: (Int) -> Unit,
    updateAvailable: Boolean = false,
    newsUnread: Boolean = false,
    onUpdateClick: () -> Unit = {},
    onOpenUploads: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeaderMeasured(it.size.height) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    // A search icon on the right, like the Albums rail; it becomes a bar only while
                    // terms are active, so an active search is always visible.
                    if (photosSearchTerms.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                        SearchIconButton(
                            label = stringResource(R.string.search_photos_hint),
                            onClick = onOpenPhotosSearch,
                        )
                    } else {
                        SearchTermsBar(
                            terms = photosSearchTerms,
                            placeholder = stringResource(R.string.search_photos_hint),
                            onOpen = onOpenPhotosSearch,
                            onClearAll = onClearPhotosSearch,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TabOverflowMenu(
                        listOf(
                            stringResource(R.string.menu_timeline_settings) to onOpenTimelineSettings,
                            stringResource(R.string.gallery_filter_hidden) to onHiddenAlbumClick,
                        ),
                    )
                }
                1 -> {
                    AlbumsFilterRail(
                        searchQuery = albumQuery,
                        onSearchQueryChange = onAlbumQueryChange,
                        searchActive = albumSearchActive,
                        onSearchActiveChange = onAlbumSearchActiveChange,
                        reorderActive = albumReorderActive,
                        modifier = Modifier.weight(1f),
                    )
                    if (!albumSearchActive) {
                        TabOverflowMenu(
                            listOf(
                                stringResource(R.string.menu_album_settings) to onOpenAlbumsSettings,
                                stringResource(R.string.gallery_filter_hidden) to onHiddenAlbumClick,
                            ),
                        )
                    }
                }
                2 -> {
                    SharedFilterRail(
                        selectedFilter = sharedFilter,
                        onFilterSelected = onSharedFilterSelected,
                        activeEmailFilter = activeEmailFilter,
                        onFilterClick = onShowSharedEmailSheet,
                        onClearEmailFilter = onClearSharedEmailFilter,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AvatarButton(
                // A local-only session has no account, so the avatar reads as a plain settings
                // button: a blank initial (a space renders nothing; an empty string would show a
                // "?" placeholder) and no storage ring.
                initial         = if (galleryState.isSignedIn) galleryState.userInitial else " ",
                storageFraction = if (galleryState.isSignedIn) galleryState.storageFraction else 0f,
                // The avatar ring means real backup work is running. A plain cloud-listing refresh
                // (fired on every foreground and on pull-to-refresh) is not backup, so isRefreshing is
                // deliberately left out here, it kept the ring spinning for a long check with no upload.
                isSyncing       = galleryState.isSyncing || albumsState.isLoading,
                hasActiveUpload   = galleryState.hasActiveUpload,
                hasActiveDownload = galleryState.hasActiveDownload,
                isOffline       = !isOnlineNow,
                updateAvailable = updateAvailable,
                newsUnread      = newsUnread,
                onClick         = onSettingsClick,
                onUpdateClick   = onUpdateClick,
                onUploadClick   = onOpenUploads,
                onDownloadClick = onOpenDownloads,
            )
        }
    }
}
