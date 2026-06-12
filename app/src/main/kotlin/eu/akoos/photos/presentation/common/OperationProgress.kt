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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.AppColors

/**
 * One app-wide representation of a running bulk operation's progress (back up, copy to library,
 * download, share, add to album). Every screen maps its own work to this so the user sees the
 * same shape of feedback everywhere instead of a different idiom per entry point.
 *
 * [done]/[total] drive a determinate ring; set [indeterminate] when the operation can't report a
 * count yet (e.g. preparing). [label] is the already-localised, ready-to-show line.
 */
data class OperationProgress(
    val done: Int,
    val total: Int,
    val label: String,
    val indeterminate: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * The canonical progress surface: a floating pill with a ring and a label, themed to match the
 * selection bars and hero headers. Renders nothing when [progress] is null, so callers can pass
 * a nullable state straight through. The owner positions it (typically `Alignment.TopCenter`
 * under the status bar) and is responsible for clearing the state when the work finishes.
 */
@Composable
fun OperationProgressPill(
    progress: OperationProgress?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) return
    val colors = AppColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.bg0.copy(alpha = 0.95f))
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress.indeterminate || progress.total <= 0) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.fraction },
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            progress.label,
            color = colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
