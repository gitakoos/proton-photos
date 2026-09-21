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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.SheetBg

/**
 * Single source of truth for simple confirmation dialogs across the app, shown as a bottom drawer (a
 * [ModalBottomSheet]) rather than a centred dialog, so a confirm / warning / discard slides up from the
 * edge and matches the app's other sheets. Every existing caller keeps working unchanged.
 *
 * Behavior knobs:
 *   - `message = null` → title-only.
 *   - `dismissLabel = null` → show only the confirm button (a flow that must be answered).
 *   - `destructive = true` → confirm rendered as the red [DestructiveButton] ("delete forever / sign
 *     out / discard"), otherwise the accent [PrimaryButton]; cancel is the [SecondaryButton].
 *
 * For dialogs with custom content (text field, multi-step picker, etc.) keep the raw component; this
 * one intentionally has no content slot so the call sites stay uniform.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (message != null) {
                Text(message, color = colors.fgDim, fontSize = 14.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (dismissLabel != null) {
                    SecondaryButton(dismissLabel, onDismiss, modifier = Modifier.weight(1f))
                }
                if (destructive) {
                    DestructiveButton(confirmLabel, onConfirm, modifier = Modifier.weight(1f), icon = null)
                } else {
                    PrimaryButton(confirmLabel, onConfirm, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
