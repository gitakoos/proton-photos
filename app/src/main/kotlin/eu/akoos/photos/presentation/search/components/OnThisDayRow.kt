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

package eu.akoos.photos.presentation.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors
import java.util.Calendar

/**
 * Compact horizontal "On this day" row for the search page's empty state.
 *
 * Mirrors the gallery's full-width carousel but with a smaller header and tighter
 * tile size to coexist with the "Jump to month" grid below. Hidden entirely when
 * [yearGroups] is empty — the caller decides whether to render it.
 *
 * Per-tile click forwards to [onPhotoClick] with the year-group's items as the
 * viewer slice + the tapped item's index inside that slice (same contract the
 * gallery uses for its carousel).
 */
@Composable
fun OnThisDayRow(
    yearGroups: List<Pair<Int, List<GalleryItem>>>,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (yearGroups.isEmpty()) return
    val colors = AppColors.current
    val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
    // Total years covered drives the subtitle ("3 years ago" wins over "1, 2, 3 years").
    // We deliberately show the oldest gap so the user gets the most evocative number.
    val maxYearsAgo = remember(yearGroups, now) {
        yearGroups.maxOf { (year, _) -> (now - year).coerceAtLeast(1) }
    }
    val subtitle = if (maxYearsAgo == 1) {
        stringResource(R.string.search_section_on_this_day_years, maxYearsAgo)
    } else {
        stringResource(R.string.search_section_on_this_day_years_plural, maxYearsAgo)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.search_section_on_this_day),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                text = "  $subtitle",
                color = colors.fgMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            yearGroups.forEach { (year, yearItems) ->
                val yearsAgo = (now - year).coerceAtLeast(1)
                yearItems.forEachIndexed { index, photo ->
                    item(key = "otd_${year}_${keyOf(photo)}") {
                        OnThisDayTile(
                            item = photo,
                            yearsAgo = yearsAgo,
                            onClick = { onPhotoClick(yearItems, index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnThisDayTile(
    item: GalleryItem,
    yearsAgo: Int,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val imageModel: Any? = when (item) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }
    val mimeType = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
    val isVideo = mimeType.startsWith("video/")

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Text(
            text = "${yearsAgo}y",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun keyOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}
