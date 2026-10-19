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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.formatBytes

/**
 * Every file of an album or device folder with its size, the total of the known sizes at the top.
 * The rows are built by the caller ([albumContentsRowsOfCloud], [albumContentsRowsOfDevice]) and
 * listed lazily, so a large album lays out only what is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumContentsSheet(
    sheetState: SheetState,
    rows: List<AlbumContentsRow>,
    onDismiss: () -> Unit,
) {
    val summary = remember(rows) { albumContentsSummary(rows) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.album_contents),
                color = FgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                pluralStringResource(
                    R.plurals.album_contents_total,
                    rows.size,
                    rows.size,
                    formatBytes(summary.totalBytes),
                ),
                color = FgMute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (summary.unknownCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.album_contents_unknown_count,
                        summary.unknownCount,
                        summary.unknownCount,
                    ),
                    color = FgMute,
                    fontSize = 12.sp,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(top = 12.dp, bottom = 8.dp),
            ) {
                items(rows) { row -> AlbumContentsRowView(row) }
            }
        }
    }
}

@Composable
private fun AlbumContentsRowView(row: AlbumContentsRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.name,
            color = FgPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (row.sizeBytes > 0) formatBytes(row.sizeBytes) else stringResource(R.string.album_contents_size_unknown),
            color = FgMute,
            fontSize = 12.5.sp,
        )
    }
}
