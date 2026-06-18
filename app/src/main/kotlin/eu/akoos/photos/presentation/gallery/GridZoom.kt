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

package eu.akoos.photos.presentation.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.map

/**
 * Shared zoom ladder for the photo grid. The timeline pinch-zooms through [LEVELS] (a column
 * count + date grouping per step); the album / device-folder / hidden grids use a fixed column
 * count from the grid-layout preference. One source of truth so GalleryGrid (pinch),
 * GalleryViewModel (opening level + persistence) and the grid-layout settings page all agree.
 */
object GridZoom {
    /** (columns, grouping) per level — mirrors the pinch ladder, densest (most columns) first. */
    val LEVELS: List<Pair<Int, TimelineGrouping>> = listOf(
        5 to TimelineGrouping.Year,
        4 to TimelineGrouping.Month,
        3 to TimelineGrouping.Month,
        2 to TimelineGrouping.Day,
        1 to TimelineGrouping.Day,
    )

    /** Columns per row on first launch — the 3-col month baseline. */
    const val DEFAULT_COLUMNS = 3

    /** Column counts offered as a fixed default. 1-col is an extreme zoom, not a sensible default. */
    val COLUMN_OPTIONS: List<Int> = listOf(2, 3, 4, 5)

    fun groupingForLevel(level: Int): TimelineGrouping = LEVELS[level.coerceIn(0, LEVELS.lastIndex)].second

    fun columnsForLevel(level: Int): Int = LEVELS[level.coerceIn(0, LEVELS.lastIndex)].first

    /** Index of the level whose column count is [cols]; falls back to [DEFAULT_LEVEL]. */
    fun levelForColumns(cols: Int): Int = LEVELS.indexOfFirst { it.first == cols }.takeIf { it >= 0 } ?: DEFAULT_LEVEL

    /** Level index that yields [DEFAULT_COLUMNS] (3-col month). */
    val DEFAULT_LEVEL: Int get() = LEVELS.indexOfFirst { it.first == DEFAULT_COLUMNS }.takeIf { it >= 0 } ?: 2
}

/**
 * Live fixed-default column count for the album / device-folder / hidden grids. These grids have
 * no pinch zoom, so they always follow the grid-layout default rather than a remembered level.
 */
@Composable
fun rememberDefaultGridColumns(): Int {
    val context = LocalContext.current
    val cols by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.GRID_DEFAULT_COLUMNS] ?: GridZoom.DEFAULT_COLUMNS }
    }.collectAsStateWithLifecycle(initialValue = GridZoom.DEFAULT_COLUMNS)
    return cols
}
