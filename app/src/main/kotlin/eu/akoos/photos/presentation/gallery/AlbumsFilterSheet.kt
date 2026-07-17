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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary

/** Bottom sheet for the Albums-tab view filter. Sets an optional default applied on open plus a
 *  remember-last toggle; the active All/Cloud/Local narrowing is cycled from the rail pill. While
 *  remember-last is on the default row is greyed and inert, since the last-used value takes over. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsFilterSheet(
    sheetState: SheetState,
    default: AlbumDisplayFilter,
    rememberLast: Boolean,
    onDefaultChange: (AlbumDisplayFilter) -> Unit,
    onRememberLastChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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

            // Default section: greyed and non-interactive while remember-last owns the value.
            val defaultAlpha = if (rememberLast) 0.38f else 1f
            Column(
                modifier = Modifier.alpha(defaultAlpha),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.albums_filter_default_heading),
                    color = FgMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
                AlbumFilterChipRow(
                    selected = default,
                    onSelected = onDefaultChange,
                    enabled = !rememberLast,
                )
            }

            ToggleRow(
                label = stringResource(R.string.albums_filter_remember_last),
                description = stringResource(R.string.albums_filter_remember_last_summary),
                checked = rememberLast,
                onCheckedChange = onRememberLastChange,
            )
        }
    }
}

/** Row of three selectable chips mapping the [AlbumDisplayFilter] values to their labels. When
 *  [enabled] is false the chips still render for context but ignore taps. */
@Composable
private fun AlbumFilterChipRow(
    selected: AlbumDisplayFilter,
    onSelected: (AlbumDisplayFilter) -> Unit,
    enabled: Boolean = true,
) {
    val options = listOf(
        AlbumDisplayFilter.All to stringResource(R.string.albums_filter_all),
        AlbumDisplayFilter.Cloud to stringResource(R.string.albums_filter_cloud),
        AlbumDisplayFilter.Local to stringResource(R.string.albums_filter_local),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (filter, label) ->
            FilterChip(
                label = label,
                selected = selected == filter,
                onClick = { if (enabled) onSelected(filter) },
            )
        }
    }
}
