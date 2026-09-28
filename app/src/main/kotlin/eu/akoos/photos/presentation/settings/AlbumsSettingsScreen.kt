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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.domain.usecase.AlbumSortMode
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.albums.AlbumPhotoSortMode
import eu.akoos.photos.presentation.gallery.AlbumDisplayFilter
import eu.akoos.photos.presentation.gallery.albumDisplayFilterLabel
import eu.akoos.photos.presentation.gallery.rememberAlbumColumns
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything about how the Albums tab looks, on one page: which albums it shows, their order, the
 * cover size, and the order photos are listed in inside an album or a device folder. Reached from the
 * tab's ⋮ menu and from Settings → Library, the same way the Photos tab's layout lives on the
 * Timeline page, so neither tab spreads its view settings over a sheet and a menu.
 */
@Composable
fun AlbumsSettingsScreen(
    onBack: () -> Unit,
    /** Signed out there are no cloud albums: Show and the album photo order would have nothing to
     *  apply to, so only the rows for device folders remain. */
    isSignedIn: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun <T> write(key: Preferences.Key<T>, value: T) {
        scope.launch { context.settingsDataStore.edit { it[key] = value } }
    }
    val prefs = remember { context.settingsDataStore.data }
    val show by remember {
        prefs.map {
            AlbumDisplayFilter.entries[
                (it[SettingsKeys.ALBUMS_DEFAULT_FILTER] ?: 0).coerceIn(0, AlbumDisplayFilter.entries.lastIndex)
            ]
        }
    }.collectAsState(initial = AlbumDisplayFilter.All)
    val sortMode by remember {
        prefs.map { AlbumSortMode.fromOrdinal(it[SettingsKeys.ALBUMS_SORT_MODE]) }
    }.collectAsState(initial = AlbumSortMode.Default)
    val albumPhotoOrder by remember {
        prefs.map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.ALBUM_PHOTO_SORT_MODE]) }
    }.collectAsState(initial = AlbumPhotoSortMode.Default)
    val folderPhotoOrder by remember {
        prefs.map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE]) }
    }.collectAsState(initial = AlbumPhotoSortMode.Default)
    val columns = rememberAlbumColumns()

    SettingsSubPageScaffold(title = stringResource(R.string.gallery_tab_albums), onBack = onBack) {
        // ── Show ──────────────────────────────────────────────────────────────
        if (isSignedIn) {
            SectionLabel(stringResource(R.string.albums_show_heading))
            Spacer(Modifier.height(8.dp))
            PillRow {
                AlbumDisplayFilter.entries.forEach { filter ->
                    TimelinePill(
                        label = albumDisplayFilterLabel(filter),
                        selected = show == filter,
                        onClick = { write(SettingsKeys.ALBUMS_DEFAULT_FILTER, filter.ordinal) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Sort by ───────────────────────────────────────────────────────────
        SectionLabel(stringResource(R.string.albums_sort_heading))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            val options = listOf(
                AlbumSortMode.LastActivity to R.string.albums_sort_activity,
                AlbumSortMode.NameAsc to R.string.albums_sort_name,
                AlbumSortMode.NameDesc to R.string.albums_sort_name_desc,
                AlbumSortMode.PhotoCount to R.string.albums_sort_count,
                AlbumSortMode.Custom to R.string.albums_sort_custom,
            )
            options.forEachIndexed { index, (mode, labelRes) ->
                ChoiceRow(
                    label = stringResource(labelRes),
                    selected = sortMode == mode,
                    onClick = { write(SettingsKeys.ALBUMS_SORT_MODE, mode.ordinal) },
                )
                if (index < options.lastIndex) RowDivider()
            }
        }

        // ── Cover size ────────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.albums_cover_size_heading))
        Spacer(Modifier.height(8.dp))
        PillRow {
            listOf(2, 3, 4).forEach { count ->
                TimelinePill(
                    label = count.toString(),
                    selected = columns == count,
                    onClick = { write(SettingsKeys.ALBUM_GRID_COLUMNS, count) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Photo order ───────────────────────────────────────────────────────
        // One global direction each, which is why they are set here rather than from a single
        // album's long-press menu, where "Sort by" read as ordering the Albums grid.
        if (isSignedIn) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_albums_photo_order))
            Spacer(Modifier.height(8.dp))
            PhotoOrderRow(albumPhotoOrder) { write(SettingsKeys.ALBUM_PHOTO_SORT_MODE, it.ordinal) }
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_folders_photo_order))
        Spacer(Modifier.height(8.dp))
        PhotoOrderRow(folderPhotoOrder) { write(SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE, it.ordinal) }
    }
}

/** A single-choice row: the label, and a filled check while it is the one picked. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (selected) {
            Box(
                modifier = Modifier.size(20.dp).background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun PhotoOrderRow(selected: AlbumPhotoSortMode, onSelected: (AlbumPhotoSortMode) -> Unit) {
    PillRow {
        AlbumPhotoSortMode.entries.forEach { mode ->
            TimelinePill(
                label = stringResource(
                    when (mode) {
                        AlbumPhotoSortMode.NewestFirst -> R.string.sort_newest_first
                        AlbumPhotoSortMode.OldestFirst -> R.string.sort_oldest_first
                    },
                ),
                selected = selected == mode,
                onClick = { onSelected(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
