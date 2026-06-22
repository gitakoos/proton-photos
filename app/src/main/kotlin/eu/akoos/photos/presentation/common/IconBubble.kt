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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Circular icon button used across header rails (viewer back, editor close, gallery
 * selection actions, settings back, album hero edit, etc.). The same recipe — fill +
 * thin border + CircleShape — surfaced in over a dozen files as hand-rolled `Box`
 * blocks before being centralised here.
 *
 * Default style matches the dominant variant in the viewer / editor / selection
 * header (40.dp pill on [PillBg] with a 0.5.dp [PillBorder] outline and an 18.dp
 * icon tinted [FgPrimary]). Callers tune the four common knobs:
 *
 *  - **diameter** — 40.dp on header / back buttons, 28-32.dp in smaller
 *    overlays.
 *  - **iconSize** — 16-20.dp depending on the icon's visual weight.
 *  - **background** — pass `SurfaceWeak` for settings rows, `Color(0x99000000)`
 *    for darkened-overlay variants on top of imagery, or `Color.Transparent` for
 *    chromeless headers (calendar / search).
 *  - **borderColor** — pass `null` to drop the outline (the chromeless variants
 *    do this).
 *
 * The button passes its full click area as the tap target — no inner ripple
 * padding, no `IconButton` wrapper — so multi-button rows stay visually compact.
 *
 * @param contentDescription accessibility label, forwarded to the inner [Icon].
 *   Pass `null` only when the surrounding context already names the action
 *   (e.g. when the bubble sits next to a label that announces the same thing).
 */
@Composable
fun IconBubble(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    background: Color = PillBg,
    borderColor: Color? = PillBorder,
    tint: Color = FgPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .background(background, CircleShape)
            .let { m -> if (borderColor != null) m.border(0.5.dp, borderColor, CircleShape) else m }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
