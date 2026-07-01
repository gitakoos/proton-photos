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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.akoos.photos.presentation.theme.AppColors

/** Height of the floating pill header row, excluding the status-bar inset. */
val FloatingHeaderHeight: Dp = 72.dp

/**
 * Top content padding for a screen that draws a floating pill header (FloatingMemoriesHeader) over
 * its scrolling content: the status-bar inset plus the pill-row height, so the first row clears the
 * header and the rest scrolls under it. One source for the value every such screen used to reserve
 * by hand with a literal `statusBars + 72.dp`.
 */
@Composable
fun floatingHeaderContentTopPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FloatingHeaderHeight

/**
 * A top-down fade drawn UNDER a floating pill header: solid page background across the status bar and
 * header row, then fading to transparent at the bottom edge so scrolling content dissolves out behind
 * the pills instead of clashing with them. Place it as the first child of the header's container so
 * the back button / title pill draw on top.
 */
@Composable
fun FloatingHeaderScrim(modifier: Modifier = Modifier) {
    val pageBg = AppColors.current.pageBg
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(floatingHeaderContentTopPadding())
            .background(
                // Hold a soft veil behind the status bar and the pill, then fade only below the
                // pill's bottom edge so the title sits on a clean backdrop without a heavy band.
                Brush.verticalGradient(
                    0f to pageBg.copy(alpha = 0.65f),
                    0.72f to pageBg.copy(alpha = 0.6f),
                    1f to pageBg.copy(alpha = 0f),
                ),
            ),
    )
}
