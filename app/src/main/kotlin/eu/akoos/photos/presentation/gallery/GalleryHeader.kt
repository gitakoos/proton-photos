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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import eu.akoos.photos.presentation.common.OfflineBanner
import eu.akoos.photos.presentation.common.UpdateBanner
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
    /** Opens the Timeline filter screen from the Albums tab. */
    onShowAlbumsFilterSheet: () -> Unit,
    /** Opens the create-album dialog from the Albums-tab "New album" pill. */
    onNewAlbumClick: () -> Unit = {},
    albumFilter: AlbumDisplayFilter = AlbumDisplayFilter.All,
    onAlbumFilterSelected: (AlbumDisplayFilter) -> Unit = {},
    onSharedFilterSelected: (SharedFilter) -> Unit,
    onShowSharedEmailSheet: () -> Unit,
    onSettingsClick: () -> Unit,
    onHeaderMeasured: (Int) -> Unit,
    updateBannerVersion: String? = null,
    onUpdateBannerOpen: () -> Unit = {},
    onUpdateBannerDismiss: () -> Unit = {},
) {
    // Per-offline-session dismissal: reset on reconnect so the next disconnect re-surfaces it.
    // rememberSaveable so a config change doesn't re-pop the banner the user just hid.
    var offlineBannerDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isOnlineNow) {
        if (isOnlineNow) offlineBannerDismissed = false
    }
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
                        selectedFilter = albumFilter,
                        onFilterSelected = onAlbumFilterSelected,
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
                initial         = galleryState.userInitial,
                storageFraction = galleryState.storageFraction,
                isSyncing       = galleryState.isSyncing || albumsState.isLoading,
                isOffline       = !isOnlineNow,
                onClick         = onSettingsClick,
            )
        }
        // Category rail (Photos tab only). No expand/shrink animation so a tab swipe or entering
        // selection mode doesn't run a janky vertical reveal.
        if (selectedTab == 0) {
            CategoryRail(
                selectedFilter = galleryState.selectedFilter,
                onFilterSelected = onFilterSelected,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        if (!isOnlineNow && !offlineBannerDismissed) {
            OfflineBanner(onDismiss = { offlineBannerDismissed = true })
        }
        updateBannerVersion?.let { version ->
            UpdateBanner(
                versionName = version,
                onOpen = onUpdateBannerOpen,
                onDismiss = onUpdateBannerDismiss,
            )
        }
    }
}
