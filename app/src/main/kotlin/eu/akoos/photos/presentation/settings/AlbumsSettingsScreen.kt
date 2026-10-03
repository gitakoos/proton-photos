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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.gallery.AlbumDisplayFilter
import eu.akoos.photos.presentation.gallery.AlbumsViewMode
import eu.akoos.photos.presentation.gallery.albumDisplayFilterAt
import eu.akoos.photos.presentation.gallery.rememberAlbumColumns
import eu.akoos.photos.presentation.gallery.rememberAlbumsShowCollection
import eu.akoos.photos.presentation.gallery.rememberAlbumsViewMode
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import kotlinx.coroutines.flow.map
import eu.akoos.photos.presentation.albums.LibraryTileReorderList
import kotlinx.coroutines.launch

/** One row of the Albums settings page. */
internal enum class AlbumsSettingsRow { CoverSize, DefaultFilter, RememberLast, ShowCollection, Shortcuts }

/**
 * The rows the Albums settings page lays out, top to bottom, for the tab's [viewMode]. The cover size
 * is there for the grid alone: a list row's cover has one size.
 */
internal fun albumsSettingsRows(
    viewMode: AlbumsViewMode,
    shortcutsShown: Boolean = true,
): List<AlbumsSettingsRow> = buildList {
    if (viewMode == AlbumsViewMode.Grid) add(AlbumsSettingsRow.CoverSize)
    add(AlbumsSettingsRow.DefaultFilter)
    add(AlbumsSettingsRow.RememberLast)
    add(AlbumsSettingsRow.ShowCollection)
    if (shortcutsShown) add(AlbumsSettingsRow.Shortcuts)
}

/**
 * The Albums tab's settings page, reached from the More settings row of the tab's filter sheet and
 * from Settings > Appearance, in the order [albumsSettingsRows] gives: how many covers sit per row,
 * the filter the tab opens on and the switch that keeps the last one instead, and whether the
 * Collection card leads the tab. The view and the sort stay on the sheet, where a pick reshapes the
 * tab in sight. Every value is the stored key the tab itself reads, so both places show one state.
 */
@Composable
fun AlbumsSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewMode = rememberAlbumsViewMode()
    val columns = rememberAlbumColumns()
    val showCollection = rememberAlbumsShowCollection()
    val defaultFilter by remember {
        ctx.settingsDataStore.data.map { albumDisplayFilterAt(it[SettingsKeys.ALBUMS_DEFAULT_FILTER] ?: 0) }
    }.collectAsState(initial = AlbumDisplayFilter.All)
    val rememberLast by remember {
        ctx.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER] ?: false }
    }.collectAsState(initial = false)

    SettingsSubPageScaffold(title = stringResource(R.string.gallery_tab_albums), onBack = onBack) {
        albumsSettingsRows(viewMode, shortcutsShown = showCollection).forEachIndexed { index, row ->
            key(row) {
                if (index > 0) Spacer(Modifier.height(20.dp))
                when (row) {
                    AlbumsSettingsRow.CoverSize ->
                        CollapsibleSection(label = stringResource(R.string.albums_cover_size_heading)) {
                            // More columns, smaller covers. The labels are the counts themselves.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(2, 3, 4).forEach { count ->
                                    TimelinePill(
                                        label = count.toString(),
                                        selected = columns == count,
                                        onClick = {
                                            scope.launch {
                                                ctx.settingsDataStore.edit { it[SettingsKeys.ALBUM_GRID_COLUMNS] = count }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                    // Greyed and inert while remember-last owns the value. The Excluded view is left
                    // out: it is a place to put an album back, never a view to open on.
                    AlbumsSettingsRow.DefaultFilter -> {
                        val enabled = !rememberLast
                        CollapsibleSection(label = stringResource(R.string.albums_filter_default_heading)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    AlbumDisplayFilter.All to stringResource(R.string.albums_filter_all),
                                    AlbumDisplayFilter.Cloud to stringResource(R.string.albums_filter_cloud),
                                    AlbumDisplayFilter.Local to stringResource(R.string.albums_filter_local),
                                ).forEach { (filter, label) ->
                                    TimelinePill(
                                        label = label,
                                        selected = defaultFilter == filter,
                                        onClick = {
                                            if (enabled) {
                                                scope.launch {
                                                    ctx.settingsDataStore.edit {
                                                        it[SettingsKeys.ALBUMS_DEFAULT_FILTER] = filter.ordinal
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    AlbumsSettingsRow.RememberLast ->
                        SettingsCard {
                            ToggleRow(
                                label = stringResource(R.string.albums_filter_remember_last),
                                checked = rememberLast,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        ctx.settingsDataStore.edit { it[SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER] = on }
                                    }
                                },
                            )
                        }

                    AlbumsSettingsRow.Shortcuts ->
                        CollapsibleSection(label = stringResource(R.string.albums_shortcuts_heading)) {
                            SettingsCard { LibraryTileReorderList() }
                        }

                    AlbumsSettingsRow.ShowCollection ->
                        SettingsCard {
                            ToggleRow(
                                label = stringResource(R.string.albums_show_collection),
                                checked = showCollection,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        ctx.settingsDataStore.edit { it[SettingsKeys.ALBUMS_SHOW_COLLECTION] = on }
                                    }
                                },
                            )
                        }
                }
            }
        }
    }
}
