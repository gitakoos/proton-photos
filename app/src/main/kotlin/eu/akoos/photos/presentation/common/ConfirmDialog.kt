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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor

/**
 * Single source of truth for simple confirmation dialogs across the app.
 *
 * Matches the styling the existing inline AlertDialogs converged on:
 *   - container = cardBg, title color = fgPrimary, text color = fgDim
 *   - title in normal weight (callers add SemiBold only where the original did)
 *   - body text at 13 sp
 *   - confirm + dismiss as plain TextButtons; confirm tinted red when destructive
 *
 * Behavior knobs:
 *   - `message = null` → title-only dialog (e.g. force-update modal).
 *   - `dismissLabel = null` → show only the confirm button (non-dismissible flow).
 *   - `destructive = true` → confirm label rendered in [ErrorColor] with SemiBold
 *     weight, matching the existing "delete forever / sign out / discard" pattern.
 *
 * For dialogs with custom content (text field, multi-step picker, etc.) keep
 * using the raw `AlertDialog` — this composable intentionally does not expose a
 * content slot to keep the call sites uniform.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    dismissLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = AppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = colors.cardBg,
        titleContentColor = colors.fgPrimary,
        textContentColor  = colors.fgDim,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = if (message != null) {
            { Text(message, color = colors.fgDim, fontSize = 13.sp) }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) ErrorColor else colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = if (dismissLabel != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel, color = colors.fgDim)
                }
            }
        } else null,
    )
}
