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

package eu.akoos.photos.presentation.places

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.location.LocationDetailViewModel
import eu.akoos.photos.presentation.location.LocationPhotosContent
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Full-screen page for one place: every photo taken in a city, reached by a coordinate. Opens like
 * the person and memory detail pages rather than as a bottom sheet, and shows no map. The photo grid,
 * hero header and selection come from the shared [LocationPhotosContent]; the shell wraps it in the
 * app's floating pill header carrying a back button and the resolved place name, with the content
 * scrolling underneath. A photo tap calls [onPhotoClick]; the caller opens the viewer.
 */
@Composable
fun PlaceCityScreen(
    latitude: Double,
    longitude: Double,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    onBack: () -> Unit,
    /** Opens the date + place editor for the current selection, matching the timeline's entry. */
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    viewModel: LocationDetailViewModel = hiltViewModel(),
) {
    // Resolve the coordinate to its place and photos on entry; keyed on the coords so a fresh place
    // reloads cleanly, matching the sheet host.
    LaunchedEffect(latitude, longitude) {
        viewModel.clearSelection()
        viewModel.load(latitude, longitude)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appColors = AppColors.current

    LocationPhotosContent(
        onPhotoClick = onPhotoClick,
        modifier = Modifier.fillMaxSize(),
        backgroundColor = appColors.bg0,
        // No top inset, so the cover fills to the very top and the pill header floats over it, the way
        // the album and person detail pages open (rather than sitting below a solid header band).
        contentTopPadding = 0.dp,
        onEditMetadata = onEditMetadata,
        headerOverlay = {
            FloatingHeader(
                title = state.placeName,
                onBack = onBack,
            )
        },
        viewModel = viewModel,
    )
}
