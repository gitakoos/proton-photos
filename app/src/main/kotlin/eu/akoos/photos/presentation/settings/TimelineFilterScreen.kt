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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import eu.akoos.photos.presentation.gallery.CategoryReorderList
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.StatusError

/**
 * Hub for the main Photos timeline filters. Each concern (category bar order, album
 * visibility, device folders) opens its own sub-page so the page reads like the other
 * Settings hubs. Every filter here is display-only: hidden folders/albums keep their
 * photos on the device and on Drive, they are just dropped from the timeline tabs.
 */
@Composable
fun TimelineFilterScreen(
    onBack: () -> Unit,
    onCategoryOrderClick: () -> Unit = {},
    onAlbumsClick: () -> Unit = {},
    onDeviceFoldersClick: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val showOnThisDay by remember {
        ctx.settingsDataStore.data.map { it[SettingsKeys.SHOW_ON_THIS_DAY] ?: true }
    }.collectAsState(initial = true)
    SettingsSubPageScaffold(
        title = stringResource(R.string.timeline_filter_title),
        onBack = onBack,
    ) {
        Text(
            stringResource(R.string.timeline_filter_subtitle),
            color = FgMute, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        // Simple display toggles live in their own card, kept apart from the drill-down menu rows.
        SettingsCard {
            // "On this day" memories carousel on the Photos tab — display toggle, default on.
            ToggleRow(
                label = stringResource(R.string.gallery_on_this_day),
                checked = showOnThisDay,
                onCheckedChange = { on ->
                    scope.launch { ctx.settingsDataStore.edit { it[SettingsKeys.SHOW_ON_THIS_DAY] = on } }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            NavRow(
                label = stringResource(R.string.timeline_filter_categories_header),
                onClick = onCategoryOrderClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.timeline_filter_albums_header),
                onClick = onAlbumsClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.device_folders_section),
                onClick = onDeviceFoldersClick,
            )
        }
    }
}

// ── Category bar order sub-page ───────────────────────────────────────────────

@Composable
fun TimelineCategoryOrderScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(
        title = stringResource(R.string.timeline_filter_categories_header),
        onBack = onBack,
    ) {
        Text(
            stringResource(R.string.timeline_filter_reorder_hint),
            color = FgMute, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsCard {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                CategoryReorderList()
            }
        }
    }
}

// ── Album visibility sub-page ─────────────────────────────────────────────────

@Composable
fun TimelineAlbumsFilterScreen(
    onBack: () -> Unit,
    viewModel: TimelineFilterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(
        title = stringResource(R.string.timeline_filter_albums_header),
        onBack = onBack,
    ) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_hide_album_photos),
                description = stringResource(R.string.settings_hide_album_photos_desc),
                checked = state.hideAlbumPhotos,
                onCheckedChange = viewModel::setHideAlbumPhotos,
            )
        }
        // Per-album show/hide — only shown while the master "hide all" switch is off, since
        // that switch already hides every album photo regardless of the individual ones.
        if (!state.hideAlbumPhotos && state.albums.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.timeline_filter_per_album_hint),
                color = FgMute, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsCard {
                state.albums.forEachIndexed { i, album ->
                    AlbumFilterRow(album = album, onToggle = { viewModel.toggleAlbum(album.linkId) })
                    if (i < state.albums.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 86.dp),
                            thickness = 0.5.dp, color = colors.cardBorder,
                        )
                    }
                }
            }
        }
    }
}

// ── Device folders sub-page ───────────────────────────────────────────────────

@Composable
fun TimelineDeviceFoldersScreen(
    onBack: () -> Unit,
    viewModel: TimelineFilterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(
        title = stringResource(R.string.device_folders_section),
        onBack = onBack,
    ) {
        // Master switch — hide (exclude) or show every folder at once, like the album screen.
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_hide_all_folders),
                description = stringResource(R.string.settings_hide_all_folders_desc),
                checked = state.allExcluded,
                onCheckedChange = { if (it) viewModel.excludeAll() else viewModel.includeAll() },
            )
        }
        // Per-folder pickers hide entirely while "hide all" is on, like the album visibility screen.
        if (!state.allExcluded) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(
                        R.string.settings_excluded_folders_count_label,
                        state.excludedCount, state.folders.size,
                    ),
                    color = FgDim, fontSize = 12.sp,
                )
                Text(
                    stringResource(R.string.settings_excluded_folders_select_all),
                    color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { viewModel.excludeAll() },
                )
            }
            SettingsCard {
                if (state.folders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.settings_excluded_folders_empty),
                            color = FgMute, fontSize = 13.sp,
                        )
                    }
                } else {
                    state.folders.forEachIndexed { i, folder ->
                        TimelineFolderRow(folder = folder, onToggle = { viewModel.toggle(folder.name) })
                        if (i < state.folders.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 86.dp),
                                thickness = 0.5.dp, color = colors.cardBorder,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
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
                pluralStringResource(R.plurals.count_photos_plural, folder.itemCount, folder.itemCount),
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

@Composable
private fun AlbumFilterRow(
    album: TimelineFilterViewModel.TimelineAlbum,
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
        val tileAlpha = if (album.isExcluded) 0.55f else 1f
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            if (album.coverUrl != null) {
                AsyncImage(
                    model              = album.coverUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    alpha              = tileAlpha,
                    modifier           = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PhotoAlbum,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.name,
                color      = if (album.isExcluded) FgMute else FgPrimary,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                pluralStringResource(R.plurals.count_photos_plural, album.itemCount, album.itemCount),
                color    = FgMute,
                fontSize = 12.sp,
            )
        }

        if (album.isExcluded) {
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
