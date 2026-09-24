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

package eu.akoos.photos.presentation.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ActionSheetSectionHeading
import eu.akoos.photos.presentation.gallery.FilterChip

/**
 * How an album or a device folder lays out its photos, as the chip rows that open its drawer: the
 * direction they run in and the span of dates each header gathers. Sits directly under the drawer's
 * title, so its first heading carries no gap of its own.
 *
 * A pick applies in place and leaves the drawer up, as the filter sheets' chips do, so both choices
 * can be made in one visit. The grouping row shows only where [onGroupingSelected] is given. Its
 * marked chip is the saved grouping, which a pinch on the grid can leave for the rest of a visit, so
 * a tap on the marked chip is passed on as well, for the screen to bring the grid back to it.
 */
@Composable
internal fun AlbumViewOptions(
    sortMode: AlbumPhotoSortMode,
    onSortSelected: (AlbumPhotoSortMode) -> Unit,
    grouping: AlbumPhotoGrouping = AlbumPhotoGrouping.Default,
    onGroupingSelected: ((AlbumPhotoGrouping) -> Unit)? = null,
) {
    Column {
        ActionSheetSectionHeading(stringResource(R.string.albums_sort_heading), first = true)
        ChoiceChipRow(
            options = listOf(
                AlbumPhotoSortMode.NewestFirst to stringResource(R.string.sort_newest_first),
                AlbumPhotoSortMode.OldestFirst to stringResource(R.string.sort_oldest_first),
            ),
            current = sortMode,
            onSelected = onSortSelected,
        )
        if (onGroupingSelected != null) {
            ActionSheetSectionHeading(stringResource(R.string.album_group_heading))
            ChoiceChipRow(
                options = listOf(
                    AlbumPhotoGrouping.Day to stringResource(R.string.album_group_day),
                    AlbumPhotoGrouping.Month to stringResource(R.string.album_group_month),
                    AlbumPhotoGrouping.Year to stringResource(R.string.album_group_year),
                ),
                current = grouping,
                onSelected = onGroupingSelected,
                reselectable = true,
            )
        }
    }
}

/** One choice as chips that wrap rather than clip, since a translated label can overrun a narrow
 *  screen. The chip already in force takes no action on a second tap unless [reselectable]. */
@Composable
private fun <T> ChoiceChipRow(
    options: List<Pair<T, String>>,
    current: T,
    onSelected: (T) -> Unit,
    reselectable: Boolean = false,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (option, label) ->
            val isCurrent = option == current
            FilterChip(
                label = label,
                selected = isCurrent,
                onClick = { if (reselectable || !isCurrent) onSelected(option) },
                modifier = Modifier.semantics { selected = isCurrent },
            )
        }
    }
}
