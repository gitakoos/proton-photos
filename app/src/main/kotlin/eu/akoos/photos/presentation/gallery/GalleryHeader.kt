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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
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
    onFilterSelected: (GalleryFilter) -> Unit,
    onSearchClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onClearContentFilter: () -> Unit,
    onHiddenAlbumClick: () -> Unit,
    /** Opens the content-filter drawer from the Photos-tab filter icon. */
    onShowAlbumsFilterSheet: () -> Unit,
    /** Opens the create-album dialog from the Albums-tab "New album" pill. */
    onNewAlbumClick: () -> Unit = {},
    /** Logged-out only: opens the "New folder" picker from the Albums-tab pill in the same spot. */
    onNewLocalFolder: () -> Unit = {},
    albumFilter: AlbumDisplayFilter = AlbumDisplayFilter.All,
    /** Cycles the Albums-tab narrowing (All to Cloud to Local) from the pill label. */
    onAlbumFilterSelected: (AlbumDisplayFilter) -> Unit = {},
    /** Opens the Albums-tab view-filter sheet from its filter icon. */
    onOpenAlbumsFilterSheet: () -> Unit = {},
    /** Albums-tab inline search: the query, whether the rail's search bar is open, and their setters. */
    albumQuery: String = "",
    onAlbumQueryChange: (String) -> Unit = {},
    albumSearchActive: Boolean = false,
    onAlbumSearchActiveChange: (Boolean) -> Unit = {},
    /** True while the Albums page is in arrange mode, which hides the search entry in the rail. */
    albumReorderActive: Boolean = false,
    onSharedFilterSelected: (SharedFilter) -> Unit,
    onShowSharedEmailSheet: () -> Unit,
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
                    FilterRail(
                        totalCount = galleryState.items.size,
                        contentFilter = galleryState.contentFilter,
                        onSearchClick = onSearchClick,
                        onCalendarClick = onCalendarClick,
                        onClearContentFilter = onClearContentFilter,
                        onOpenTimelineFilter = onShowAlbumsFilterSheet,
                        modifier = Modifier.weight(1f),
                    )
                }
                1 -> {
                    AlbumsFilterRail(
                        onHiddenAlbumClick = onHiddenAlbumClick,
                        onNewAlbumClick = onNewAlbumClick,
                        onNewLocalFolder = onNewLocalFolder,
                        selectedFilter = albumFilter,
                        onFilterSelected = onAlbumFilterSelected,
                        onOpenSheet = onOpenAlbumsFilterSheet,
                        isSignedIn = galleryState.isSignedIn,
                        searchQuery = albumQuery,
                        onSearchQueryChange = onAlbumQueryChange,
                        searchActive = albumSearchActive,
                        onSearchActiveChange = onAlbumSearchActiveChange,
                        reorderActive = albumReorderActive,
                        modifier = Modifier.weight(1f),
                    )
                }
                2 -> {
                    SharedFilterRail(
                        selectedFilter = sharedFilter,
                        onFilterSelected = onSharedFilterSelected,
                        activeEmailFilter = activeEmailFilter,
                        onFilterClick = onShowSharedEmailSheet,
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
        // Category rail (Photos tab only). No expand/shrink animation so a tab swipe or entering
        // selection mode doesn't run a janky vertical reveal.
        if (selectedTab == 0) {
            CategoryRail(
                selectedFilter = galleryState.selectedFilter,
                onFilterSelected = onFilterSelected,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
}
