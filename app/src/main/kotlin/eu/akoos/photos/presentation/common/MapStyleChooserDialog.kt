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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg

/**
 * One-time chooser shown the first time a world-map entry point is opened, so the pick between the
 * modern world map (a spinnable globe) and the classic OpenStreetMap tile map is offered where it
 * matters rather than buried in Settings. [onChoose] passes true for the classic map, false for the
 * modern one; [onDismiss] covers a tap-outside or back press. The caller mirrors the pick into the
 * Settings map-style toggle and marks the prompt answered so it never returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStyleChooserDialog(
    onChoose: (classic: Boolean) -> Unit,
    onDismiss: () -> Unit,
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
            Text(
                stringResource(R.string.map_style_prompt_title),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionSheetRow(
                    icon = Icons.Default.Public,
                    title = stringResource(R.string.map_style_modern),
                    onClick = { onChoose(false) },
                    subtitle = stringResource(R.string.map_style_modern_summary),
                )
                ActionSheetRow(
                    icon = Icons.Default.Map,
                    title = stringResource(R.string.settings_map_style),
                    onClick = { onChoose(true) },
                    subtitle = stringResource(R.string.settings_map_style_summary),
                )
            }
        }
    }
}
