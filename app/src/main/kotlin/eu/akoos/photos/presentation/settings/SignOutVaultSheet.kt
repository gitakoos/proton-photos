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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenRescue
import eu.akoos.photos.data.hidden.SignOutVaultChoice
import eu.akoos.photos.presentation.common.DestructiveButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg

/**
 * The Sign out confirmation for a user whose hidden vault holds photos with no Drive copy. Those files
 * are the only copy of their photos, so the sheet asks what to do with them instead of deleting them
 * on the strength of a single confirm: move them to the gallery, or delete them.
 *
 * Moving is the pre-selected answer, since it is the one that cannot lose a photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignOutVaultSheet(
    message: String,
    zipped: Boolean = false,
    onConfirm: (SignOutVaultChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var choice by remember { mutableStateOf(SignOutVaultChoice.MOVE_OUT) }
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
                stringResource(R.string.sign_out_dialog_title),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message, color = colors.fgDim, fontSize = 14.sp)
            Text(
                stringResource(R.string.sign_out_vault_heading),
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            VaultChoiceRow(
                selected = choice == SignOutVaultChoice.MOVE_OUT,
                title = stringResource(if (zipped) R.string.sign_out_vault_move_zip else R.string.sign_out_vault_move),
                description = if (zipped) {
                    stringResource(R.string.sign_out_vault_move_zip_desc)
                } else {
                    stringResource(R.string.sign_out_vault_move_desc, HiddenRescue.SIGN_OUT_FOLDER)
                },
                onClick = { choice = SignOutVaultChoice.MOVE_OUT },
            )
            VaultChoiceRow(
                selected = choice == SignOutVaultChoice.DELETE,
                title = stringResource(R.string.sign_out_vault_delete),
                description = stringResource(R.string.sign_out_vault_delete_desc),
                onClick = { choice = SignOutVaultChoice.DELETE },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    stringResource(R.string.sign_out_dialog_cancel),
                    onDismiss,
                    modifier = Modifier.weight(1f),
                )
                DestructiveButton(
                    stringResource(R.string.sign_out_dialog_confirm),
                    { onConfirm(choice) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VaultChoiceRow(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) colors.accent else colors.cardBorder),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.fgMute),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 12.sp)
        }
    }
}
