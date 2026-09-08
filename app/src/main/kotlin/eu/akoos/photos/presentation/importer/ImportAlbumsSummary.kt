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

package eu.akoos.photos.presentation.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.ImportAlbumCount
import eu.akoos.photos.presentation.theme.AppColors

/** One labelled figure in an import summary: which glyph, what it is, and its value as text. */
data class ImportStat(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * The albums an import built, listed by name under a section label so the result is concrete rather than
 * a bare total. Each album is its own soft-filled row: an album glyph, the name, and, when [showCounts] is
 * on, how many photos went into it. Shared by the finished-run card and the Recent-imports detail so the
 * two read the same. Renders nothing when [albums] is empty, so a photos-only run adds no section.
 *
 * [showCounts] is off for an empty-albums run, where a per-album photo count would misread the shells as
 * filled; it is on when the run actually filed photos into the albums.
 */
@Composable
fun ImportAlbumsSummary(
    albums: List<ImportAlbumCount>,
    showCounts: Boolean,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    val colors = AppColors.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.import_albums_section),
            color = colors.fgMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(8.dp))
        albums.forEachIndexed { index, album ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceWeak, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoAlbum,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = album.name,
                    color = colors.fgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showCounts) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.import_album_photo_count,
                            album.count,
                            album.count,
                        ),
                        color = colors.fgDim,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}

/**
 * The run's figures as a column of soft-filled rows in the same style as [ImportAlbumsSummary], so the
 * date and per-outcome counts read as one consistent summary on both the finished-run card and the
 * Recent-imports detail. Renders nothing for an empty list.
 */
@Composable
fun ImportStatsColumn(stats: List<ImportStat>, modifier: Modifier = Modifier) {
    if (stats.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        stats.forEachIndexed { index, stat ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            ImportStatRow(stat)
        }
    }
}

/** One figure: an accent glyph, its label, and the value on the trailing edge; the soft fill and radius
 *  match an album row so a summary of stats and albums reads as one design. */
@Composable
private fun ImportStatRow(stat: ImportStat) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceWeak, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = stat.icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stat.label,
            color = colors.fgPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stat.value,
            color = colors.fgDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
