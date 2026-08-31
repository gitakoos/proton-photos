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

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/** Height of the floating pill header row, excluding the status-bar inset. */
val FloatingHeaderHeight: Dp = 72.dp

/**
 * Top content padding for a screen that draws a floating pill header ([FloatingHeader]) over
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

/**
 * The one floating page header: a circular back button pinned left, the page title centered in a
 * pill, and optional [trailing] controls at the end. It draws over the page's scrolling content with a
 * [FloatingHeaderScrim] behind it, so the body slides underneath; reserve [floatingHeaderContentTopPadding]
 * as the content's top padding. When [menuItems] is non-empty the title pill carries a caret and grows
 * downward into that menu instead of popping a separate one (used to cross-navigate Calendar/Map). The
 * back tap is debounced so a fast double tap cannot pop the back stack twice. This is the single header
 * every content and settings page uses so they read as one component.
 */
@Composable
fun FloatingHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    menuItems: List<Pair<String, () -> Unit>>? = null,
    showScrim: Boolean = true,
) {
    val colors = AppColors.current
    val debouncedBack = rememberDebouncedBack(onBack)
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = !menuItems.isNullOrEmpty()
    val titleShape = RoundedCornerShape(20.dp)
    Box(modifier = modifier.fillMaxWidth()) {
        if (showScrim) FloatingHeaderScrim()
        // Top alignment (not center) so a title pill that expands into a menu grows downward while the
        // back button and trailing controls stay pinned to the top of the row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = debouncedBack,
                diameter = 40.dp,
                iconSize = 18.dp,
                background = colors.pillBg,
                borderColor = colors.pillBorder,
                tint = colors.fgPrimary,
            )
            // Weight spacers on both sides keep the title optically centered; a trailing control
            // replaces the right balance spacer so the title stays centered when one is present.
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .clip(titleShape)
                    .background(colors.pillBg, titleShape)
                    .border(0.5.dp, colors.pillBorder, titleShape)
                    .animateContentSize(),
            ) {
                Row(
                    modifier = Modifier
                        .then(if (hasMenu) Modifier.clickable { menuExpanded = !menuExpanded } else Modifier)
                        .padding(start = 16.dp, end = if (hasMenu) 8.dp else 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (hasMenu) {
                        Icon(
                            if (menuExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.fgPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (hasMenu && menuExpanded) {
                    menuItems?.forEach { (label, action) ->
                        Text(
                            text = label,
                            color = colors.fgPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { menuExpanded = false; action() }
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) trailing() else Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * Debounces the back tap so a rapid double tap cannot pop the back stack twice (which could crash on
 * an already-departed screen). Fires at most once per [intervalMs]; a single tap feels unchanged.
 */
@Composable
private fun rememberDebouncedBack(onBack: () -> Unit, intervalMs: Long = 600L): () -> Unit {
    val lastFireMs = remember { mutableLongStateOf(0L) }
    return remember(onBack, intervalMs) {
        {
            val now = SystemClock.uptimeMillis()
            if (now - lastFireMs.longValue >= intervalMs) {
                lastFireMs.longValue = now
                onBack()
            }
        }
    }
}
