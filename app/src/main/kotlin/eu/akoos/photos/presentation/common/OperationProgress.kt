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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onCancel: (() -> Unit)? = null,
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
        // Optional cancel affordance — the background back-up passes this so the user can stop it
        // from the pill instead of digging into the notification.
        if (onCancel != null) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = colors.fgMute,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
            )
        }
    }
}

/**
 * A blocking, non-dismissible drawer for foreground bulk operations the user should wait on —
 * deleting, hiding, or moving a selection. Where [OperationProgressPill] is a passive top pill for
 * background work the user can keep scrolling past, this raises a bottom sheet over a scrim so a
 * second destructive tap can't land on a half-finished one. Swipe-down and scrim-tap are both
 * blocked. Renders nothing when [progress] is null; the owner clears the driving state when the
 * work finishes and the drawer dismisses itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingOperationSheet(progress: OperationProgress?) {
    if (progress == null) return
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        containerColor = colors.bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress.indeterminate || progress.total <= 0) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress.fraction },
                    color = colors.accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                progress.label,
                color = colors.fgPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!progress.indeterminate && progress.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${progress.done} / ${progress.total}",
                    color = colors.fgMute,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
