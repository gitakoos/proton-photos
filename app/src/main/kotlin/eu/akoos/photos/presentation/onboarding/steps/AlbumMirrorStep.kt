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

package eu.akoos.photos.presentation.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Onboarding step that lets the user opt specific local folder names into the
 * cloud album-mirror feature. Two flavours of rows are rendered: rows backed by
 * a currently-present MediaStore bucket (the bulk of the list), and rows for
 * forward-declared custom names the user typed in earlier (or just now). The
 * caller owns both sets — this step is presentation only.
 */
@Composable
internal fun AlbumMirrorStep(
    availableBuckets: List<String>,
    selected: Set<String>,
    customNames: Set<String>,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    onRemoveCustom: (String) -> Unit,
) {
    val colors = AppColors.current
    var showAddDialog by remember { mutableStateOf(false) }

    // Bucket rows already account for any custom name that also happens to be a
    // present bucket — we de-dupe by removing those from the custom list before
    // rendering, so the user never sees the same name twice.
    val customOnly = remember(customNames, availableBuckets) {
        customNames - availableBuckets.toSet()
    }
    val selectedCount = selected.size

    Column {
        StepHeader(
            icon = Icons.Default.PhotoLibrary,
            title = stringResource(R.string.onboarding_album_mirror_title),
            subtitle = stringResource(R.string.onboarding_album_mirror_subtitle),
        )
        Spacer(Modifier.height(16.dp))

        if (availableBuckets.isEmpty() && customOnly.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_folders_empty),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
            ) {
                val totalRows = availableBuckets.size + customOnly.size
                var renderedIndex = 0

                availableBuckets.forEach { name ->
                    val checked = name in selected
                    AlbumMirrorRow(
                        name = name,
                        checked = checked,
                        onToggle = { onToggle(name) },
                        onRemove = null,
                    )
                    renderedIndex++
                    if (renderedIndex < totalRows) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .height(0.5.dp)
                                .background(colors.cardBorder),
                        )
                    }
                }

                customOnly.forEach { name ->
                    val checked = name in selected
                    AlbumMirrorRow(
                        name = name,
                        checked = checked,
                        onToggle = { onToggle(name) },
                        onRemove = { onRemoveCustom(name) },
                    )
                    renderedIndex++
                    if (renderedIndex < totalRows) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .height(0.5.dp)
                                .background(colors.cardBorder),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Add-custom row mirrors the SyncFolders "Add folder by name" affordance
        // so the two surfaces feel consistent.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                .clickable { showAddDialog = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(colors.accent.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.onboarding_album_mirror_add_custom),
                color = colors.fgPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }

        if (selectedCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "$selectedCount",
                color = colors.fgMute,
                fontSize = 11.5.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showAddDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    stringResource(R.string.album_mirror_custom_dialog_title),
                    color = colors.fgPrimary,
                )
            },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.album_mirror_custom_dialog_hint))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = input.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddCustom(trimmed)
                        }
                        showAddDialog = false
                    },
                ) {
                    Text(
                        stringResource(R.string.onboarding_continue),
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(
                        stringResource(R.string.onboarding_back),
                        color = colors.fgDim,
                    )
                }
            },
            containerColor = colors.cardBg,
        )
    }
}

@Composable
private fun AlbumMirrorRow(
    name: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = colors.fgPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    null,
                    tint = colors.fgMute,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (checked) colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                )
                .border(
                    width = if (checked) 0.dp else 1.5.dp,
                    color = colors.cardBorder,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
