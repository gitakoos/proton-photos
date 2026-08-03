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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * One action pill in a bottom-sheet drawer: leading glyph (or a ring while [busy]), label, an
 * optional second line, and an optional trailing tick. Shared by every action sheet and by the
 * selection drawer, so every drawer in the app reads as one family instead of a per-screen variant.
 *
 * [showCheck] marks the active choice in a group of alternatives; [subtitle] is for a row whose
 * outcome needs a word of explanation and is omitted everywhere else.
 *
 * Weight decides colour: an ordinary row is [Accent], a [destructive] one is ErrorColor, and an
 * explicit [tint] overrides both for a caller that already carries the colour it wants. The ring a
 * busy row wears takes that same colour, and [busyIcon] marks it with a small glyph for work whose
 * own row is what stops it.
 *
 * A busy row stops taking taps, so a second tap cannot land on work already under way. A row whose
 * tap is that work's cancel control sets [clickableWhileBusy] and stays live throughout.
 *
 * [modifier] is for a caller that has to pin the row's box, such as a drawer whose open height is
 * derived from how many rows it holds; [titleMaxLines] caps a label there for the same reason.
 */
@Composable
internal fun ActionSheetRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    tint: Color = Color.Unspecified,
    busy: Boolean = false,
    busyFraction: Float? = null,
    busyIcon: ImageVector? = null,
    clickableWhileBusy: Boolean = false,
    showCheck: Boolean = false,
    subtitle: String? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
) {
    val alpha = if (enabled) 1f else 0.5f
    val glyphTint = tint.takeOrElse { if (destructive) ErrorColor else Accent }
    val labelTint = tint.takeOrElse { if (destructive) ErrorColor else FgPrimary }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PillBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled && (!busy || clickableWhileBusy), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                busy && busyFraction != null -> CircularProgressIndicator(
                    progress = { busyFraction },
                    color = glyphTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                busy -> CircularProgressIndicator(
                    color = glyphTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                else -> Icon(
                    icon,
                    contentDescription = null,
                    tint = glyphTint.copy(alpha = alpha),
                    modifier = Modifier.size(22.dp),
                )
            }
            if (busy && busyIcon != null) {
                Icon(
                    busyIcon,
                    contentDescription = null,
                    tint = ErrorColor,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = labelTint.copy(alpha = alpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = FgMute.copy(alpha = alpha),
                    fontSize = 12.sp,
                )
            }
        }
        if (showCheck) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
