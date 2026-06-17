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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.AppColors

// ── Shared action buttons ─────────────────────────────────────────────────────────
// One recipe per role so confirm / cancel / delete look the same in every sheet and
// dialog. All three share the same 12dp pill shape, 14sp SemiBold label, and height,
// differing only in colour: accent fill (primary), neutral outline (secondary), soft
// red (destructive). Pass `Modifier.fillMaxWidth()` for full-width footers.

private val ButtonShape = RoundedCornerShape(12.dp)

/** Filled accent action — Create, Save, Copy, Continue, Retry. The app's primary button. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .clip(ButtonShape)
            .background(colors.accent.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White,
            )
            icon != null -> Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Neutral / secondary action — Cancel, Later, or a finished (disabled) state. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .clip(ButtonShape)
            .background(colors.surfaceWeak)
            .border(0.5.dp, colors.cardBorder, ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = colors.fgPrimary, modifier = Modifier.size(16.dp))
        Text(label, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Destructive action — Remove, Delete. Soft red fill + red outline and label. */
@Composable
fun DestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Default.DeleteOutline,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .clip(ButtonShape)
            .background(colors.deleteTint)
            .border(0.5.dp, colors.errorColor.copy(alpha = 0.3f), ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = colors.errorColor, modifier = Modifier.size(16.dp))
        Text(label, color = colors.errorColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
