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

package eu.akoos.photos.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The gentle inward scale a photo tile eases into while it is selected, so the accent frame around it
 * has room to read. Shared so every grid's selection feels the same as the timeline's; only a tile
 * whose [selected] flag actually flips animates. Placed after the tile's aspect ratio and before its
 * clip so the frame scales with the photo.
 */
@Composable
fun Modifier.selectPressScale(selected: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.92f else 1f,
        label = "cellSelect",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Pops a tile's selection tick in and out the way the timeline does: a scale-and-fade on select, the
 * same on deselect, so the tick never simply appears. [content] is whatever tick a grid already
 * draws, so each keeps its own look and only gains the motion.
 */
@Composable
fun SelectionCheckPop(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        content()
    }
}
