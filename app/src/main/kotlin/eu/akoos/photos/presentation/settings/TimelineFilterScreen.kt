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
import androidx.compose.ui.draw.alpha
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
import eu.akoos.photos.presentation.gallery.GridZoom
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
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
 * The Photos timeline settings hub — a menu of focused sub-pages so each concern lives on its
 * own short screen instead of one long mixed scroll: Layout (grid + display), Categories,
 * Albums, and Device folders. Every filter under here is display-only — hidden albums/folders
 * keep their photos on the device and on Drive, they are just dropped from the timeline tabs.
 */
@Composable
fun TimelineFilterScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit = {},
    onOpenAlbums: () -> Unit = {},
    onOpenDeviceFolders: () -> Unit = {},
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_timeline), onBack = onBack) {
        SettingsCard {
            NavRow(label = stringResource(R.string.timeline_filter_categories_header), onClick = onOpenCategories)
            RowDivider()
            NavRow(label = stringResource(R.string.timeline_filter_albums_header), onClick = onOpenAlbums)
            RowDivider()
            NavRow(label = stringResource(R.string.device_folders_section), onClick = onOpenDeviceFolders)
        }
    }
}

/** Layout + display: grid columns, remember-last-zoom, scroll-date label, and "On this day". */
@Composable
fun TimelineLayoutScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val showOnThisDay by remember {
        ctx.settingsDataStore.data.map { it[SettingsKeys.SHOW_ON_THIS_DAY] ?: true }
    }.collectAsState(initial = true)

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_timeline_section_layout),
        onBack = onBack,
    ) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.grid_remember_last),
                description = stringResource(R.string.grid_remember_last_desc),
                checked = settings.gridRememberLast,
                onCheckedChange = settingsViewModel::setGridRememberLast,
            )
        }
        Spacer(Modifier.height(16.dp))
        // Fixed default columns — greyed out while "remember last used" is on, since that
        // overrides the default with whatever zoom the user last pinched to.
        val defaultEnabled = !settings.gridRememberLast
        CollapsibleSection(label = stringResource(R.string.grid_default_columns_section)) {
            Row(
                modifier = Modifier.fillMaxWidth().alpha(if (defaultEnabled) 1f else 0.4f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GridZoom.COLUMN_OPTIONS.forEach { count ->
                    TimelinePill(
                        label = count.toString(),
                        selected = settings.gridDefaultColumns == count,
                        onClick = { if (defaultEnabled) settingsViewModel.setGridDefaultColumns(count) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.settings_timeline_section_display))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_show_scroll_date),
                description = stringResource(R.string.settings_show_scroll_date_desc),
                checked = settings.showScrollDate,
                onCheckedChange = settingsViewModel::setShowScrollDate,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_reverse_timeline),
                description = stringResource(R.string.settings_reverse_timeline_desc),
                checked = settings.reverseTimelineOrder,
                onCheckedChange = settingsViewModel::setReverseTimelineOrder,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_mosaic_grid),
                description = stringResource(R.string.settings_mosaic_grid_desc),
                checked = settings.mosaicGrid,
                onCheckedChange = settingsViewModel::setMosaicGrid,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_show_selection_labels),
                description = stringResource(R.string.settings_show_selection_labels_desc),
                checked = settings.showSelectionLabels,
                onCheckedChange = settingsViewModel::setShowSelectionLabels,
            )
            RowDivider()
            // "On this day" memories carousel on the Photos tab — display toggle, default on.
            ToggleRow(
                label = stringResource(R.string.gallery_on_this_day),
                checked = showOnThisDay,
                onCheckedChange = { on ->
                    scope.launch { ctx.settingsDataStore.edit { it[SettingsKeys.SHOW_ON_THIS_DAY] = on } }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Category bar order on the Photos tab. */
@Composable
fun TimelineCategoriesScreen(onBack: () -> Unit) {
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

/** Album visibility on the timeline (display-only: hidden albums keep their photos). */
@Composable
fun TimelineAlbumsScreen(
    onBack: () -> Unit,
    filterViewModel: TimelineFilterViewModel = hiltViewModel(),
) {
    val filterState by filterViewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(
        title = stringResource(R.string.timeline_filter_albums_header),
        onBack = onBack,
    ) {
        Text(
            stringResource(R.string.timeline_filter_subtitle),
            color = FgMute, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_hide_album_photos),
                description = stringResource(R.string.settings_hide_album_photos_desc),
                checked = filterState.allAlbumsExcluded,
                onCheckedChange = { if (it) filterViewModel.excludeAllAlbums() else filterViewModel.includeAllAlbums() },
            )
        }
        if (filterState.albums.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.timeline_filter_per_album_hint),
                color = FgMute, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsCard {
                filterState.albums.forEachIndexed { i, album ->
                    AlbumFilterRow(album = album, onToggle = { filterViewModel.toggleAlbum(album.linkId) })
                    if (i < filterState.albums.lastIndex) {
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

/** Device-folder visibility on the timeline (display-only: hidden folders keep their photos). */
@Composable
fun TimelineDeviceFoldersScreen(
    onBack: () -> Unit,
    filterViewModel: TimelineFilterViewModel = hiltViewModel(),
) {
    val filterState by filterViewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(
        title = stringResource(R.string.device_folders_section),
        onBack = onBack,
    ) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_hide_all_folders),
                description = stringResource(R.string.settings_hide_all_folders_desc),
                checked = filterState.allExcluded,
                onCheckedChange = { if (it) filterViewModel.excludeAll() else filterViewModel.includeAll() },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(
                    R.string.settings_excluded_folders_count_label,
                    filterState.excludedCount, filterState.folders.size,
                ),
                color = FgDim, fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.settings_excluded_folders_select_all),
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { filterViewModel.excludeAll() },
            )
        }
        SettingsCard {
            if (filterState.folders.isEmpty()) {
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
                filterState.folders.forEachIndexed { i, folder ->
                    TimelineFolderRow(folder = folder, onToggle = { filterViewModel.toggle(folder.name) })
                    if (i < filterState.folders.lastIndex) {
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

/** Selectable pill used by the default-columns row, matching the Appearance theme pills. */
@Composable
private fun TimelinePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Box(
        modifier = modifier
            .height(44.dp)
            .background(
                color = if (selected) colors.accent.copy(alpha = 0.22f) else colors.cardBg,
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) colors.accent else colors.cardBorder,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
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
