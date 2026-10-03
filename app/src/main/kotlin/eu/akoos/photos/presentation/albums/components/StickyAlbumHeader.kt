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


package eu.akoos.photos.presentation.albums.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding

/**
 * Whether the album hero has scrolled up under the top of the page: the cover is gone and the title
 * row has reached where a floating header would sit. The cover is a 4:3 band the width of the screen.
 */
@Composable
internal fun rememberHeroScrolledAway(gridState: LazyGridState): State<Boolean> {
    val density = LocalDensity.current
    val coverHeightPx = LocalConfiguration.current.screenWidthDp * density.density * 3f / 4f
    val headerPx = with(density) { floatingHeaderContentTopPadding().toPx() }
    return remember(gridState, coverHeightPx, headerPx) {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > coverHeightPx - headerPx
        }
    }
}

/**
 * The page's common floating header, faded in over the grid once [rememberHeroScrolledAway] says the
 * hero's own title has left the screen, so the name of the album, folder or place stays in view with
 * the back button and the page's action at the ends.
 */
@Composable
internal fun StickyAlbumHeader(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        FloatingHeader(title = title, onBack = onBack, trailing = trailing)
    }
}
