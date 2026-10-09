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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary

/** One row of the Albums filter sheet. */
internal enum class AlbumsSheetRow { Show, View, Sort, MoreSettings }

/**
 * The rows the Albums filter sheet lays out, top to bottom, the same in either layout: which albums
 * show, the view and the sort, since all three reshape the tab behind the sheet at once, then the More
 * settings row into the Albums settings page, which holds the rest
 * ([eu.akoos.photos.presentation.settings.albumsSettingsRows]).
 */
internal fun albumsSheetRows(): List<AlbumsSheetRow> =
    listOf(AlbumsSheetRow.Show, AlbumsSheetRow.View, AlbumsSheetRow.Sort, AlbumsSheetRow.MoreSettings)

/** Bottom sheet for the Albums tab's layout and order, in the order [albumsSheetRows] gives: the
 *  All/Cloud/On device/Excluded narrowing (the rail pill cycles the same choice), the grid-or-list
 *  choice and the sort, then a More settings row that closes the sheet and opens the Albums settings
 *  page through [onOpenMoreSettings], the way the timeline drawer opens its own page. Each pick lands
 *  on the tab behind the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsFilterSheet(
    sheetState: SheetState,
    displayFilter: AlbumDisplayFilter,
    onDisplayFilterChange: (AlbumDisplayFilter) -> Unit,
    viewMode: AlbumsViewMode,
    sortMode: AlbumSortMode,
    onViewModeChange: (AlbumsViewMode) -> Unit,
    onSortModeChange: (AlbumSortMode) -> Unit,
    onOpenMoreSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped + scroll so a short device or a landscape screen never clips the last row.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.albums_filter_sheet_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            albumsSheetRows().forEach { row ->
                key(row) {
                    when (row) {
                        AlbumsSheetRow.Show ->
                            AlbumsSheetSection(stringResource(R.string.albums_show_heading)) {
                                AlbumDisplayFilterChips(selected = displayFilter, onSelected = onDisplayFilterChange)
                            }

                        AlbumsSheetRow.View ->
                            AlbumsSheetSection(stringResource(R.string.albums_view_heading)) {
                                AlbumViewChipRow(selected = viewMode, onSelected = onViewModeChange)
                            }

                        AlbumsSheetRow.Sort ->
                            AlbumsSheetSection(stringResource(R.string.albums_sort_heading)) {
                                AlbumSortChipRow(selected = sortMode, onSelected = onSortModeChange)
                            }

                        AlbumsSheetRow.MoreSettings ->
                            SheetSettingsPageRow(
                                label = stringResource(R.string.albums_more_settings),
                                onClick = {
                                    onDismiss()
                                    onOpenMoreSettings()
                                },
                            )
                    }
                }
            }
        }
    }
}

/** A small muted heading over the control it names. */
@Composable
private fun AlbumsSheetSection(
    heading: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = heading,
            color = FgMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        content()
    }
}

/** Chips for the [AlbumDisplayFilter] values, two by two so four labels in any language keep a readable
 *  width. The chosen one is accented, as the rail pill is when off its default. */
@Composable
private fun AlbumDisplayFilterChips(
    selected: AlbumDisplayFilter,
    onSelected: (AlbumDisplayFilter) -> Unit,
) {
    val options = listOf(
        AlbumDisplayFilter.All to stringResource(R.string.albums_filter_all),
        AlbumDisplayFilter.Cloud to stringResource(R.string.albums_filter_cloud),
        AlbumDisplayFilter.Local to stringResource(R.string.albums_filter_local),
        AlbumDisplayFilter.Excluded to stringResource(R.string.albums_filter_excluded),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { (filter, label) ->
                    FilterChip(
                        label = label,
                        selected = selected == filter,
                        onClick = { onSelected(filter) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Grid and List chips for the [AlbumsViewMode], sharing the sheet's width between them. */
@Composable
private fun AlbumViewChipRow(
    selected: AlbumsViewMode,
    onSelected: (AlbumsViewMode) -> Unit,
) {
    val options = listOf(
        Triple(AlbumsViewMode.Grid, stringResource(R.string.albums_view_grid), Icons.Default.GridView),
        Triple(AlbumsViewMode.List, stringResource(R.string.albums_view_list), Icons.AutoMirrored.Filled.ViewList),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (mode, label, icon) ->
            FilterChip(
                label = label,
                selected = selected == mode,
                onClick = { onSelected(mode) },
                leadingIcon = { tint -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Chips for the [AlbumSortMode] values, in the order they are offered, on one row that scrolls
 *  sideways when the labels overrun a narrow screen. */
@Composable
private fun AlbumSortChipRow(
    selected: AlbumSortMode,
    onSelected: (AlbumSortMode) -> Unit,
) {
    val options = listOf(
        AlbumSortMode.Custom to stringResource(R.string.albums_sort_custom),
        AlbumSortMode.NameAsc to stringResource(R.string.albums_sort_name),
        AlbumSortMode.LastActivity to stringResource(R.string.albums_sort_activity),
        AlbumSortMode.PhotoCount to stringResource(R.string.albums_sort_count),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (mode, label) ->
            FilterChip(
                label = label,
                selected = selected == mode,
                onClick = { onSelected(mode) },
            )
        }
    }
}
