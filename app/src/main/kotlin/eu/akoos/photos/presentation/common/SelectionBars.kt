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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * The photo-selection overlay shared by the album and device-folder detail screens: those two
 * screens are the same bar apart from a few actions, so the layout, styling and behaviour live here
 * once. The Photos timeline keeps its own dock.
 *
 * Two surfaces:
 *   - [SelectionTopBar] + [SelectionTopButton]: the floating top pill — exit, a media-typed count,
 *     and a trailing row of icon-only actions. These are the self-explanatory ones (select-all,
 *     share, hide, delete), so they carry no text label.
 *   - [SelectionBottomDock] + [SelectionDockItem]: the floating bottom pill for the secondary, less
 *     obvious actions, each an icon over a short caption the caller can hide.
 */

/** The floating top pill: exit control + selection count + a caller-supplied trailing action row. */
@Composable
fun SelectionTopBar(
    onCancel: () -> Unit,
    countText: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.bg0.copy(alpha = 0.95f))
            .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.gallery_cancel_selection),
            onClick = onCancel,
            diameter = 40.dp,
            iconSize = 18.dp,
            background = PillBg,
            borderColor = PillBorder,
            tint = colors.fgPrimary,
        )
        Text(
            countText,
            color = colors.fgPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )
        trailing()
    }
}

/**
 * One icon-only action in the [SelectionTopBar] trailing row: a 40 dp circle. [active] fills it with
 * the accent colour (select-all once everything is chosen); [working] swaps the icon for a spinner,
 * determinate when [progress] is supplied.
 */
@Composable
fun SelectionTopButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Accent,
    active: Boolean = false,
    enabled: Boolean = true,
    working: Boolean = false,
    progress: Float? = null,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Accent else PillBg, CircleShape)
            .border(0.5.dp, PillBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            working && progress != null -> CircularProgressIndicator(
                progress = { progress },
                color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
            )
            working -> CircularProgressIndicator(
                color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
            )
            else -> Icon(
                icon, contentDescription,
                tint = if (active) Color.White else tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The floating bottom pill that holds the [SelectionDockItem]s. */
@Composable
fun BoxScope.SelectionBottomDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
            .background(PillBgOpaque, RoundedCornerShape(999.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One action in the [SelectionBottomDock]: a compact icon over an optional one-line caption, styled
 * as a single tap unit so the label reads as part of the button. Deliberately smaller than a full
 * navigation-bar item — this is a floating dock, not a primary bar — with the caption kept to one
 * line and ellipsized inside a bounded width so a longer translation can't stretch or wrap the dock.
 * While [working], the icon becomes a spinner (determinate when [progress] is set), optionally
 * overlaid with [workingIcon], and the caption switches to [workingLabel].
 */
@Composable
fun SelectionDockItem(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Accent,
    enabled: Boolean = true,
    working: Boolean = false,
    progress: Float? = null,
    workingIcon: ImageVector? = null,
    workingLabel: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(min = 52.dp, max = 82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (working) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        color = tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
                    )
                } else {
                    CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
                if (workingIcon != null) {
                    Icon(workingIcon, null, tint = ErrorColor, modifier = Modifier.size(10.dp))
                }
            } else {
                Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                if (working && workingLabel != null) workingLabel else label,
                color = tint,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                letterSpacing = 0.1.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
