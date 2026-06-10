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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder

private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun SyncFoldersScreen(
    onBack: () -> Unit,
    viewModel: SyncFoldersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = onBack,
                diameter = 36.dp,
                iconSize = 18.dp,
                background = colors.surfaceWeak,
                borderColor = PillBorder,
                tint = FgDim,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Backup Folders",
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Mirror of back button to keep title centred
            Box(Modifier.size(36.dp))
        }

        if (state.isLoading) {
            // Skeleton folder rows so the layout previews itself before content arrives.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ── Description ──────────────────────────────────────────────
                item {
                    Text(
                        "Choose which folders to back up to Proton Drive. " +
                            "Selected folders will be uploaded automatically when syncing.",
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
                            "${state.selectedCount} of ${state.folders.size} folders selected",
                            color = FgDim, fontSize = 12.sp,
                        )
                        Text(
                            if (state.allSelected) "Deselect all" else "Select all",
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
                                    "No folders found on this device",
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
                            Icon(Icons.Default.Add, "Add folder", tint = Accent, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sync_folders_add_row_title), color = FgPrimary, fontSize = 14.sp)
                            Text(
                                "Pre-declare a folder so future photos in it back up automatically",
                                color = FgMute, fontSize = 11.sp,
                            )
                        }
                    }
                    if (showAddDialog) {
                        var input by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            title = { Text(stringResource(R.string.sync_folders_add_dialog_title), color = FgPrimary) },
                            text = {
                                Column {
                                    Text(
                                        "Enter the exact folder name (e.g. \"Trip 2026\"). " +
                                            "Once a photo lands in a matching folder it will " +
                                            "back up automatically.",
                                        color = FgMute, fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        singleLine = true,
                                        placeholder = { Text(stringResource(R.string.sync_folders_add_dialog_name_hint)) },
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.addManualFolder(input)
                                        showAddDialog = false
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
    }
}

@Composable
private fun FolderRow(
    folder: SyncFoldersViewModel.SyncFolder,
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
            )
            Text(
                "${folder.itemCount} photos",
                color    = FgMute,
                fontSize = 12.sp,
            )
        }

        // Selection indicator
        if (folder.isSelected) {
            Icon(
                Icons.Default.CheckCircle, "Selected",
                tint     = Accent,
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
