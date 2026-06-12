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
import androidx.compose.material.icons.filled.Block
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.StatusError

private val cardShape = RoundedCornerShape(12.dp)

/**
 * Show/hide picker for the main Photos timeline. Each row is a MediaStore bucket;
 * tapping it toggles whether that folder is hidden from the timeline. Purely a display
 * filter — a hidden folder's photos stay on the device, remain browsable, and keep
 * backing up; they're just dropped from every timeline tab.
 *
 * Visual language mirrors [ExcludedFoldersScreen] (same card, same thumbnail tiles, same
 * red "blocked" selection glyph where checked = hidden) so the two carve-out pickers read
 * the same even though one is display-only and the other is a backup carve-out.
 */
@Composable
fun TimelineFilterScreen(
    onBack: () -> Unit,
    viewModel: TimelineFilterViewModel = hiltViewModel(),
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
                stringResource(R.string.timeline_filter_title),
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp))
        }

        if (state.isLoading) {
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
                // ── Intro text ───────────────────────────────────────────────
                item {
                    Text(
                        stringResource(R.string.timeline_filter_subtitle),
                        color = FgMute,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                    )
                }

                // ── Albums section — master toggle to hide every album photo ──
                item {
                    SectionHeader(stringResource(R.string.timeline_filter_albums_header))
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 8.dp)
                            .background(colors.cardBg, cardShape)
                            .border(0.5.dp, colors.cardBorder, cardShape),
                    ) {
                        ToggleRow(
                            label = stringResource(R.string.settings_hide_album_photos),
                            description = stringResource(R.string.settings_hide_album_photos_desc),
                            checked = state.hideAlbumPhotos,
                            onCheckedChange = viewModel::setHideAlbumPhotos,
                        )
                    }
                }

                // ── Device-folders section ────────────────────────────────────
                item {
                    SectionHeader(stringResource(R.string.device_folders_section))
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
                            TimelineFolderRow(
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
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = FgDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun TimelineFolderRow(
    folder: TimelineFilterViewModel.TimelineFolder,
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
        // Thumbnail tile (folder icon fallback for empty buckets, dimmed to 0.55 alpha
        // when hidden so the eye instantly reads "this one is greyed out").
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
            )
            Text(
                "${folder.itemCount} photos",
                color    = FgMute,
                fontSize = 12.sp,
            )
        }

        // Selection indicator — red Block icon when hidden, hollow circle when shown.
        if (folder.isExcluded) {
            Icon(
                Icons.Default.Block, stringResource(R.string.cd_status_excluded),
                tint     = StatusError,
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
