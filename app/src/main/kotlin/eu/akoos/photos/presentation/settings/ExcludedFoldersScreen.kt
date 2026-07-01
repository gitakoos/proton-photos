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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Carve-out picker for "Back up everything" mode. Each row is a MediaStore bucket;
 * tapping it toggles whether the bucket is excluded from auto-upload. The screen
 * is only reachable while [SettingsKeys.BACKUP_EVERYTHING] is on — when the user
 * flips backup-everything off, the row that links here disappears entirely.
 *
 * Visual language mirrors [SyncFoldersScreen] (same card, same thumbnail tiles)
 * but the selection glyph swaps to a red "blocked" icon to make clear that
 * checked = excluded (the inverse semantic of the include picker).
 */
@Composable
fun ExcludedFoldersScreen(
    onBack: () -> Unit,
    viewModel: ExcludedFoldersViewModel = hiltViewModel(),
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
                // ── Intro text ───────────────────────────────────────────────
                item {
                    Text(
                        stringResource(R.string.settings_excluded_folders_intro),
                        color = FgMute,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                    )
                }

                // ── Counter + include/exclude-all toggle ──────────────────────
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
                            stringResource(
                                R.string.settings_excluded_folders_count_label,
                                state.excludedCount,
                                state.folders.size,
                            ),
                            color = FgDim, fontSize = 12.sp,
                        )
                        Text(
                            stringResource(
                                if (state.allExcluded)
                                    R.string.settings_excluded_folders_deselect_all
                                else
                                    R.string.settings_excluded_folders_select_all
                            ),
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                if (state.allExcluded) viewModel.includeAll()
                                else viewModel.excludeAll()
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
                            ExcludeRow(
                                folder   = folder,
                                onToggle = { viewModel.toggle(folder.name) },
                                onMirrorToggle = { viewModel.toggleMirror(folder.name) },
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

                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        SettingsPillHeader(
            title = stringResource(R.string.settings_excluded_folders_title),
            onBack = onBack,
        )
    }
}

@Composable
private fun ExcludeRow(
    folder: ExcludedFoldersViewModel.ExcludedFolder,
    onToggle: () -> Unit,
    onMirrorToggle: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Thumbnail tile (folder icon fallback for empty buckets, dimmed when the folder is
        // excluded from backup so the eye reads "this one is off").
        val tileAlpha = if (folder.isExcluded) 0.55f else 1f
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverUri != null) {
                AsyncImage(
                    model              = Uri.parse(folder.coverUri),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    alpha              = tileAlpha,
                    modifier           = Modifier.fillMaxSize(),
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
                color      = if (folder.isExcluded) FgMute else FgPrimary,
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

        // Same two checkboxes as the include picker: back up (checked = not excluded) + album.
        ExcludeFolderCheck(
            checked = !folder.isExcluded,
            label = stringResource(R.string.sync_folder_backup_label),
            onClick = onToggle,
        )
        ExcludeFolderCheck(
            checked = folder.isMirrored,
            label = stringResource(R.string.sync_folder_album_label),
            onClick = onMirrorToggle,
        )
    }
}

/** One labelled checkbox in an [ExcludeRow] — matches the include picker's per-folder toggles. */
@Composable
private fun ExcludeFolderCheck(checked: Boolean, label: String, onClick: () -> Unit) {
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
