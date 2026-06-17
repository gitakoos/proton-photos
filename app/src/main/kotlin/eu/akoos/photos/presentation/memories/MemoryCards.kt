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

package eu.akoos.photos.presentation.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.AppColors
import java.util.Calendar

@Composable
internal fun OnThisDayCarousel(
    yearGroups: List<Pair<Int, List<GalleryItem>>>,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    val appColors = AppColors.current
    val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_on_this_day),
            color = appColors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // One card per milestone — tapping opens that memory's photos in the viewer, instead of
            // spilling every photo of the day inline (which read as an endless "1y 1y 1y…" run).
            yearGroups.forEach { (year, yearItems) ->
                item(key = "otd_$year") {
                    OnThisDayCard(
                        coverItem = yearItems.first(),
                        yearsAgo = (now - year).coerceAtLeast(1),
                        count = yearItems.size,
                        onClick = { onPhotoClick(yearItems, 0) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun OnThisDayCard(
    coverItem: GalleryItem,
    yearsAgo: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val imageModel: Any? = when (coverItem) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(coverItem.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(coverItem.local.uri)
        is GalleryItem.CloudOnly -> coverItem.cloud.thumbnailUrl
    }
    val isVideo = when (coverItem) {
        is GalleryItem.LocalOnly -> coverItem.local.mimeType
        is GalleryItem.Synced    -> coverItem.local.mimeType
        is GalleryItem.CloudOnly -> coverItem.cloud.mimeType
    }.startsWith("video/")
    val yearsAgoLabel = androidx.compose.ui.res.pluralStringResource(
        R.plurals.count_years_ago_plural, yearsAgo, yearsAgo,
    )
    val countLabel = androidx.compose.ui.res.pluralStringResource(
        R.plurals.count_photos_plural, count, count,
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverRequest = remember(imageModel) {
        ImageRequest.Builder(context).data(imageModel).size(512).build()
    }
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Bottom gradient so the caption stays legible over bright covers.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                text = yearsAgoLabel,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = countLabel,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// Season memory card — same cover size, corner radius and overlay treatment as OnThisDayCard,
// but captioned with a free-form season title (e.g. "Summer 2023") plus a photo count.
@Composable
internal fun SeasonCard(
    coverItem: GalleryItem,
    title: String,
    count: Int,
    onClick: () -> Unit,
) {
    val imageModel: Any? = when (coverItem) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(coverItem.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(coverItem.local.uri)
        is GalleryItem.CloudOnly -> coverItem.cloud.thumbnailUrl
    }
    val isVideo = when (coverItem) {
        is GalleryItem.LocalOnly -> coverItem.local.mimeType
        is GalleryItem.Synced    -> coverItem.local.mimeType
        is GalleryItem.CloudOnly -> coverItem.cloud.mimeType
    }.startsWith("video/")
    val countLabel = androidx.compose.ui.res.pluralStringResource(
        R.plurals.count_photos_plural, count, count,
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverRequest = remember(imageModel) {
        ImageRequest.Builder(context).data(imageModel).size(512).build()
    }
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Bottom gradient so the caption stays legible over bright covers.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = countLabel,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
