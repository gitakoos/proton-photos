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

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.settings.components.SettingsPillHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary

private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun SyncFoldersScreen(
    onBack: () -> Unit,
    viewModel: SyncFoldersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTopPad = floatingHeaderContentTopPadding()

        if (state.isLoading) {
            // Skeleton folder rows so the layout previews itself before content arrives.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = contentTopPad)
                    .padding(horizontal = 20.dp)
                    .background(colors.cardBg, cardShape)
                    .border(0.5.dp, colors.cardBorder, cardShape),
            ) {
                repeat(5) { idx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        eu.akoos.photos.presentation.common.ShimmerBox(
                            modifier = Modifier.size(56.dp),
                            cornerRadius = 10.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            eu.akoos.photos.presentation.common.ShimmerTextLine(widthFraction = 0.55f, height = 14.dp)
                            Spacer(Modifier.height(6.dp))
                            eu.akoos.photos.presentation.common.ShimmerTextLine(widthFraction = 0.3f, height = 11.dp)
                        }
                    }
                    if (idx < 4) HorizontalDivider(
                        modifier = Modifier.padding(start = 86.dp),
                        thickness = 0.5.dp,
                        color = colors.cardBorder,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = contentTopPad, bottom = navBottom),
            ) {
                // ── Description ──────────────────────────────────────────────
                item {
                    Text(
                        stringResource(R.string.sync_folders_description),
                        color = FgMute,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                    )
                }

                // ── Select-all control row ────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.sync_folders_count_selected, state.selectedCount, state.folders.size),
                            color = FgDim, fontSize = 12.sp,
                        )
                        Text(
                            if (state.allSelected) stringResource(R.string.gallery_deselect_all) else stringResource(R.string.select_all),
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                if (state.allSelected) viewModel.deselectAll()
                                else viewModel.selectAll()
                            },
                        )
                    }
                }

                // ── Folder list card ──────────────────────────────────────────
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .background(colors.cardBg, cardShape)
                            .border(0.5.dp, colors.cardBorder, cardShape),
                    ) {
                        state.folders.forEachIndexed { i, folder ->
                            FolderRow(
                                folder   = folder,
                                onToggle = { viewModel.toggle(folder.name) },
                                onMirrorToggle = { viewModel.toggleMirror(folder.name) },
                                onRemove = { viewModel.removeManualFolder(folder.name) },
                            )
                            if (i < state.folders.lastIndex) {
                                HorizontalDivider(
                                    modifier  = Modifier.padding(start = 86.dp),
                                    thickness = 0.5.dp,
                                    color     = colors.cardBorder,
                                )
                            }
                        }

                        if (state.folders.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.settings_excluded_folders_empty),
                                    color = FgMute, fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                // ── Add custom folder action ──────────────────────────────────
                item {
                    var showAddDialog by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .clickable { showAddDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Accent.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, stringResource(R.string.sync_folders_add_action), tint = Accent, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sync_folders_add_row_title), color = FgPrimary, fontSize = 14.sp)
                            Text(
                                stringResource(R.string.sync_folders_add_row_subtitle),
                                color = FgMute, fontSize = 11.sp,
                            )
                        }
                    }
                    if (showAddDialog) {
                        var input by remember { mutableStateOf("") }
                        var nameError by remember { mutableStateOf(false) }
                        AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            title = { Text(stringResource(R.string.sync_folders_add_dialog_title), color = FgPrimary) },
                            text = {
                                Column {
                                    Text(
                                        stringResource(R.string.sync_folders_add_dialog_body),
                                        color = FgMute, fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = input,
                                        onValueChange = {
                                            input = it
                                            nameError = false
                                        },
                                        singleLine = true,
                                        placeholder = { Text(stringResource(R.string.sync_folders_add_dialog_name_hint)) },
                                        isError = nameError,
                                    )
                                    if (nameError) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            stringResource(R.string.sync_folders_add_dialog_name_invalid),
                                            color = FgMute, fontSize = 11.5.sp,
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (viewModel.addManualFolder(input)) showAddDialog = false
                                        else nameError = true
                                    },
                                ) { Text(stringResource(R.string.share_invite_add), color = Accent) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAddDialog = false }) {
                                    Text(stringResource(R.string.cancel), color = FgDim)
                                }
                            },
                            containerColor = colors.cardBg,
                        )
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        SettingsPillHeader(title = stringResource(R.string.sync_folders_title), onBack = onBack)
    }
}

@Composable
private fun FolderRow(
    folder: SyncFoldersViewModel.SyncFolder,
    onToggle: () -> Unit,
    onMirrorToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Thumbnail (fallback folder icon when empty)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverUri != null) {
                AsyncImage(
                    model             = Uri.parse(folder.coverUri),
                    contentDescription = null,
                    contentScale      = ContentScale.Crop,
                    modifier          = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Name + count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                color      = FgPrimary,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                pluralStringResource(R.plurals.count_photos_plural, folder.itemCount, folder.itemCount),
                color    = FgMute,
                fontSize = 12.sp,
            )
        }

        // Hand-added placeholder folders can be removed from the watch list entirely.
        if (folder.isManual) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_remove),
                tint = FgMute,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }

        // Two checkboxes side by side: back it up, and also surface it as a Drive album.
        FolderCheck(
            checked = folder.isSelected,
            label = stringResource(R.string.sync_folder_backup_label),
            onClick = onToggle,
        )
        FolderCheck(
            checked = folder.isMirrored,
            label = stringResource(R.string.sync_folder_album_label),
            onClick = onMirrorToggle,
        )
    }
}

/** One labelled checkbox in a [FolderRow] — an icon over a short caption so the two per-folder
 *  choices (back up / album) read clearly. */
@Composable
private fun FolderCheck(checked: Boolean, label: String, onClick: () -> Unit) {
    val colors = AppColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        if (checked) {
            Icon(Icons.Default.CheckCircle, null, tint = Accent, modifier = Modifier.size(22.dp))
        } else {
            Box(modifier = Modifier.size(22.dp).border(1.5.dp, colors.pillBorder, CircleShape))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (checked) Accent else FgMute,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
