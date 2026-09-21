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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Target picker for the timeline's "Move to folder": a top "New folder" row, then every existing
 * device folder the selection could move into (cover thumb + name + count, tap to move there). It
 * carries no folder-creation of its own — [onNewFolder] hands off to [NewFolderNameDialog] for a
 * typed name so a fresh folder and an existing one both resolve to a single [onPick]/name path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToFolderSheet(
    folders: List<DeviceFolderChoice>,
    onPick: (String) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.sheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.move_to_folder_title),
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNewFolder() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    stringResource(R.string.move_to_folder_new),
                    color = colors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (folders.isNotEmpty()) {
                HorizontalDivider(
                    color = colors.line2,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(folders, key = { it.name }) { folder ->
                        FolderRow(folder = folder, onClick = { onPick(folder.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: DeviceFolderChoice, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bg0),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverUri != null) {
                AsyncImage(
                    model = folder.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = colors.fgMute,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.count_photos_plural, folder.count, folder.count),
                color = colors.fgMute,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Single-field sheet for typing a new folder name, reusing the app's shared [EditFieldSheet] so it
 * matches every other text sheet. Confirm stays disabled until the name is non-blank; the raw name
 * is trimmed before it reaches [onConfirm] (the use case sanitizes it further).
 */
@Composable
fun NewFolderNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    EditFieldSheet(
        title = stringResource(R.string.move_to_folder_new),
        hint = stringResource(R.string.move_to_folder_name_hint),
        initialValue = "",
        singleLine = true,
        confirmLabel = stringResource(R.string.move_to_folder),
        onDismiss = onDismiss,
        onSave = { onConfirm(it.trim()) },
        canConfirm = { it.isNotBlank() },
    )
}
