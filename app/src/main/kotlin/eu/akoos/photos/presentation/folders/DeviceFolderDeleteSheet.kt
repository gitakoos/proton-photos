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

package eu.akoos.photos.presentation.folders

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.formatBytes

/**
 * The confirmation a folder delete goes through, from the Albums grid and from the folder's own screen
 * alike. Built like the selection's delete sheet: the same kinds of row in the same colours, each one's
 * tap being the delete it names.
 *
 * What it adds is the folder as a whole: the title asks about the folder, and the message says that
 * everything in it leaves this device, with the count and the name. Where the device has no trash it
 * also says the delete is permanent, and the row follows suit ([DeviceFolderDeletion.choice]). Drive
 * copies are never part of it.
 *
 * While the folder is still being read the sheet says so and offers only Cancel, since agreeing to a
 * count that is not on screen yet is agreeing to nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceFolderDeleteSheet(
    request: DeviceFolderDeleteRequest,
    /** False in a local-only session, which leaves the Drive copies out of the choice. */
    isSignedIn: Boolean,
    /** Runs the device delete of every photo the sheet named. */
    onDelete: () -> Unit,
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.device_folder_delete_confirm_title),
                color = colors.fgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            val items = request.items
            if (items == null) {
                Text(stringResource(R.string.hide_confirm_counting), color = colors.fgDim, fontSize = 14.sp)
            } else {
                Text(
                    pluralStringResource(
                        R.plurals.device_folder_delete_confirm_body, items.size, items.size, request.bucketName,
                    ),
                    color = colors.fgDim, fontSize = 14.sp,
                )
                val sdkInt = Build.VERSION.SDK_INT
                if (!DeviceFolderDeletion.hasDeviceTrash(sdkInt)) {
                    Text(stringResource(R.string.device_folder_delete_no_trash), color = colors.fgDim, fontSize = 14.sp)
                }
                val choice = remember(items, isSignedIn) {
                    DeviceFolderDeletion.choice(items, isSignedIn, sdkInt)
                }
                if (choice != null) FolderDeleteChoiceRow(choice, onDelete)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.cancel), color = colors.fgDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The row as the selection's delete sheet draws its rows, with the freed-space note after the
 *  description, or alone where the row carries none. */
@Composable
private fun FolderDeleteChoiceRow(choice: DeviceFolderDeletion.Choice, onClick: () -> Unit) {
    val colors = AppColors.current
    val destructive = choice.row.destructive
    val shape = RoundedCornerShape(12.dp)
    val description = listOfNotNull(
        choice.descRes?.let { stringResource(it) },
        choice.row.freedBytes?.let { stringResource(R.string.delete_frees_note, formatBytes(it)) },
    ).joinToString(" ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (destructive) DeleteTint else colors.cardBg, shape)
            .border(0.5.dp, if (destructive) ErrorColor.copy(alpha = 0.3f) else colors.cardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            stringResource(choice.titleRes),
            color = if (destructive) ErrorColor else colors.fgPrimary,
            fontSize = 15.sp, fontWeight = FontWeight.Medium,
        )
        if (description.isNotEmpty()) {
            Text(description, color = colors.fgMute, fontSize = 12.sp)
        }
    }
}
