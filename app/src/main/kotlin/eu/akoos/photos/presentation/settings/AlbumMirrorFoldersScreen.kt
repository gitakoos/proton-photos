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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.theme.AppColors

private val cardShape = RoundedCornerShape(12.dp)

/**
 * Per-folder opt-in picker for "mirror as cloud album". A row's tick reflects
 * [AlbumMirrorFolder.isOptedIn]; the upload pipeline pairs this set with the
 * existing backup selection to decide whether each bucket should also surface as
 * a Drive album. Device folders stay in the list whether ticked or not (so the
 * user can flip them back on later); custom entries — names the user typed in
 * that don't match a real bucket — only exist while opted in, untick removes
 * the row.
 */
@Composable
fun AlbumMirrorFoldersScreen(
    onBack: () -> Unit,
    vm: AlbumMirrorFoldersViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(
        title = stringResource(R.string.album_mirror_folders_title),
        onBack = onBack,
    ) {
        Text(
            stringResource(R.string.album_mirror_folders_subtitle),
            color = colors.fgMute,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (state.isLoading) {
            SettingsCard {
                repeat(4) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ShimmerBox(modifier = Modifier.size(40.dp), cornerRadius = 10.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            ShimmerTextLine(widthFraction = 0.55f, height = 14.dp)
                            Spacer(Modifier.height(6.dp))
                            ShimmerTextLine(widthFraction = 0.3f, height = 11.dp)
                        }
                    }
                }
            }
        } else {
            // ── Device folders ─────────────────────────────────────────────────
            SettingsCard {
                if (state.deviceFolders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No folders found on this device",
                            color = colors.fgMute,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    state.deviceFolders.forEach { folder ->
                        DeviceFolderRow(folder = folder, onToggle = { vm.toggle(folder.name) })
                    }
                }
            }

            // ── Custom names ───────────────────────────────────────────────────
            if (state.customFolders.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SettingsCard {
                    state.customFolders.forEach { folder ->
                        CustomFolderRow(
                            folder = folder,
                            onToggle = { vm.toggle(folder.name) },
                            onRemove = { vm.removeCustomFolder(folder.name) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Add custom name action ─────────────────────────────────────────
            AddCustomRow(onClick = { vm.openAddDialog() })
        }
    }

    if (state.isAddDialogOpen) {
        AddCustomDialog(
            text = state.addDialogText,
            error = state.addDialogError,
            onTextChange = vm::updateAddDialogText,
            onConfirm = vm::submitCustomFolder,
            onDismiss = vm::closeAddDialog,
        )
    }
}

@Composable
private fun DeviceFolderRow(
    folder: AlbumMirrorFolder,
    onToggle: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.surfaceWeak, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = colors.fgDim,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            val count = folder.itemCount
            if (count != null) {
                Text(
                    "$count photos",
                    color = colors.fgMute,
                    fontSize = 12.sp,
                )
            }
        }
        if (folder.isOptedIn) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Opted in",
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(1.5.dp, colors.pillBorder, CircleShape),
            )
        }
    }
}

@Composable
private fun CustomFolderRow(
    folder: AlbumMirrorFolder,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.surfaceWeak, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = colors.fgDim,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Pending — applies when a folder with this name appears",
                color = colors.fgMute,
                fontSize = 11.5.sp,
            )
        }
        IconBubble(
            icon = Icons.Default.Close,
            contentDescription = "Remove",
            onClick = onRemove,
            diameter = 28.dp,
            iconSize = 14.dp,
            background = colors.surfaceWeak,
            borderColor = colors.pillBorder,
            tint = colors.fgDim,
        )
    }
}

@Composable
private fun AddCustomRow(onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBg, cardShape)
            .border(0.5.dp, colors.cardBorder, cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(colors.accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(R.string.album_mirror_add_custom),
            color = colors.fgPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AddCustomDialog(
    text: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_mirror_custom_dialog_title), color = colors.fgPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.album_mirror_custom_dialog_hint)) },
                    isError = error != null,
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = colors.fgMute, fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Add", color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.fgDim)
            }
        },
        containerColor = colors.cardBg,
    )
}
