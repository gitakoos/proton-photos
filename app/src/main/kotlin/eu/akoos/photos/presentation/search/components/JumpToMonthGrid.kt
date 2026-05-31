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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * One bucket per (year, month) combination — used by [JumpToMonthGrid] to render the
 * search empty-state's month-picker. [coverItem] is the most-recent photo in that
 * month, which we use as the tile thumbnail.
 */
data class MonthBucket(
    val year: Int,
    val month: Int,           // 1-12 (NOT the Calendar.MONTH ordinal)
    val coverItem: GalleryItem,
    val count: Int,
)

/**
 * Groups the merged gallery into year+month buckets, newest-first, dropping empty months.
 *
 * The picker only surfaces months the user actually has photos in, so cycling through
 * a freshly synced library never opens an empty filter view. [coverItem] picks the most
 * recent capture per month — that's almost always the most "memorable" thumbnail.
 */
fun buildMonthBuckets(items: List<GalleryItem>): List<MonthBucket> {
    if (items.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    return items
        .groupBy { item ->
            cal.timeInMillis = item.captureTimeMs
            cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
        }
        .map { (key, bucketItems) ->
            val (year, month) = key
            // Cover = newest item in the month — sortedByDescending so [first] is the most recent.
            val cover = bucketItems.maxByOrNull { it.captureTimeMs } ?: bucketItems.first()
            MonthBucket(year = year, month = month, coverItem = cover, count = bucketItems.size)
        }
        .sortedWith(compareByDescending<MonthBucket> { it.year }.thenByDescending { it.month })
}

/**
 * Header + 3-column adaptive grid section that renders [buckets] as month tiles.
 * Each tile previews the bucket's [MonthBucket.coverItem] with a "Month Year" caption.
 * Designed to be embedded inside a parent [androidx.compose.foundation.lazy.LazyColumn] —
 * wrap with `item { ... }` at the call site.
 */
@Composable
fun JumpToMonthGridSection(
    buckets: List<MonthBucket>,
    onMonthClick: (year: Int, month: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (buckets.isEmpty()) return
    val colors = AppColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.search_section_jump_to_month),
            color = colors.fgPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        )
        // Plain Column of rows (3-per-row) rather than a nested LazyVerticalGrid, because
        // Compose disallows nesting vertically-scrolling lazy containers without an explicit
        // height — and we want the parent LazyColumn to own the scroll. Bucket counts are
        // bounded by total months the user has photos in (rarely >100), so non-lazy
        // measurement here is cheap.
        val rows = remember(buckets) { buckets.chunked(3) }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            rows.forEach { row ->
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row.forEach { bucket ->
                        MonthTile(
                            bucket = bucket,
                            onClick = { onMonthClick(bucket.year, bucket.month) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the trailing row with invisible weight-1 spacers so the last tiles
                    // don't stretch across the full width when the row isn't full.
                    repeat(3 - row.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTile(
    bucket: MonthBucket,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val imageModel: Any? = when (val item = bucket.coverItem) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }
    val label = remember(bucket.year, bucket.month) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, bucket.year)
            set(Calendar.MONTH, bucket.month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
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
        // Bottom gradient + label band — same legibility treatment the gallery's
        // "on this day" tiles use. The caption sits inside the tile to keep the
        // grid visually tight; a separate Text below the tile would balloon the
        // row height without a clear reading hierarchy.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                ),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
