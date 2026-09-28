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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import java.time.Instant
import java.time.ZoneId

// ── Bottom controls of the Photos tab ─────────────────────────────────────────
//
// Left: the time switcher (Years / Months / Days). Middle: the tab dock. Right: the filter button.
// Time is navigated, categories are filtered, specific things are searched (top bar).

/** Height of the round bottom buttons, matching the tab dock beside them. */
private val BottomButtonSize = 48.dp

/** How the Photos tab shows the library: whole years, months, or the day-by-day timeline. */
enum class TimeView { Years, Months, Days }

/** One year or month of the (filtered) timeline: its newest photo as the cover and how many
 *  items it holds. [month] is 1-based, null for a year. */
data class TimeBucket(val year: Int, val month: Int?, val cover: GalleryItem, val count: Int)

/** Group [items] into years (newest first), each covered by its newest photo. */
fun yearBuckets(items: List<GalleryItem>, zone: ZoneId = ZoneId.systemDefault()): List<TimeBucket> =
    buckets(items, zone) { date -> date.year to null }

/** Group [items] into months (newest first), each covered by its newest photo. */
fun monthBuckets(items: List<GalleryItem>, zone: ZoneId = ZoneId.systemDefault()): List<TimeBucket> =
    buckets(items, zone) { date -> date.year to date.monthValue }

private fun buckets(
    items: List<GalleryItem>,
    zone: ZoneId,
    keyOf: (java.time.LocalDate) -> Pair<Int, Int?>,
): List<TimeBucket> {
    val covers = LinkedHashMap<Pair<Int, Int?>, GalleryItem>()
    val counts = HashMap<Pair<Int, Int?>, Int>()
    for (item in items) {
        val key = keyOf(Instant.ofEpochMilli(item.captureTimeMs).atZone(zone).toLocalDate())
        val cover = covers[key]
        if (cover == null || item.captureTimeMs > cover.captureTimeMs) covers[key] = item
        counts[key] = (counts[key] ?: 0) + 1
    }
    return covers.entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int?>, GalleryItem>> { it.key.first }.thenByDescending { it.key.second ?: 0 })
        .map { (key, cover) -> TimeBucket(key.first, key.second, cover, counts[key] ?: 0) }
}

/** Round button in the bottom row, the same surface as the tab dock; solid accent while [active]. */
@Composable
private fun BottomRoundButton(
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(BottomButtonSize)
            .clip(CircleShape)
            .background(if (active) Accent else PillBgOpaque)
            .border(0.5.dp, if (active) Accent else PillBorder, CircleShape)
            .clickable(onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        content(if (active) Color.White else FgPrimary)
    }
}

/** Bottom-left: opens the Years / Months / Days switcher. */
@Composable
internal fun TimeViewButton(onClick: () -> Unit) {
    val label = stringResource(R.string.time_view_switch)
    BottomRoundButton(active = false, contentDescription = label, onClick = onClick) { tint ->
        Icon(Icons.Outlined.CalendarViewMonth, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Bottom-right on the Albums tab: create a new album (or, signed out, a device folder). */
@Composable
internal fun CreateButton(label: String, onClick: () -> Unit) {
    BottomRoundButton(active = false, contentDescription = label, onClick = onClick) { tint ->
        Icon(Icons.Default.Add, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Bottom-left while a time view is open: ✕ closes the switcher and returns to the timeline. */
@Composable
internal fun TimeViewCloseButton(onClick: () -> Unit) {
    BottomRoundButton(active = false, contentDescription = stringResource(R.string.close), onClick = onClick) { tint ->
        Icon(Icons.Default.Close, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Takes the tab dock's place while a time view is open: Years / Months / Days as a segmented pill. */
@Composable
internal fun TimeViewSwitcher(current: TimeView, onSelect: (TimeView) -> Unit) {
    Row(
        modifier = Modifier
            .height(BottomButtonSize)
            .clip(RoundedCornerShape(999.dp))
            .background(PillBgOpaque)
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            TimeView.Years to R.string.time_view_years,
            TimeView.Months to R.string.time_view_months,
            TimeView.Days to R.string.time_view_days,
        ).forEach { (view, labelRes) ->
            val selected = view == current
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .height(BottomButtonSize - 8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(view) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(labelRes),
                    color = if (selected) Accent else FgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bottom-right: the quick filter. A checklist of the categories (in the order and with the hidden
 * ones from Settings → Timeline → Filter menu); several can be ticked. The button turns solid while
 * anything is ticked, and the search bar at the top names what is active.
 */
@Composable
internal fun FilterButton(
    categories: List<GalleryFilter>,
    selected: Set<GalleryFilter>,
    onToggle: (GalleryFilter) -> Unit,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.filter_title)
    Box {
        BottomRoundButton(active = selected.isNotEmpty(), contentDescription = label, onClick = { open = true }) { tint ->
            Icon(Icons.Default.FilterList, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = AppColors.current.sheetBg,
        ) {
            categories.forEach { category ->
                val isOn = category in selected
                val tint = if (isOn) Accent else FgPrimary
                DropdownMenuItem(
                    text = { Text(categoryLabel(category), color = tint, fontSize = 15.sp) },
                    leadingIcon = { GalleryCategoryIcon(category, tint) },
                    trailingIcon = {
                        if (isOn) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    },
                    // Stays open, so several can be ticked in one go.
                    onClick = { onToggle(category) },
                )
            }
            HorizontalDivider(color = PillBorder)
            val allTint = if (selected.isEmpty()) Accent else FgPrimary
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_filter_all), color = allTint, fontSize = 15.sp) },
                leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = allTint, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (selected.isEmpty()) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                },
                onClick = { onClear(); open = false },
            )
        }
    }
}

// ── Years and Months views ────────────────────────────────────────────────────

/** Whole years as large cover cards, newest first. Tapping one drills into its months. */
@Composable
internal fun YearsView(
    buckets: List<TimeBucket>,
    topPadding: Dp,
    onYearClick: (Int) -> Unit,
    onRequestThumbnail: (String) -> Unit,
) {
    val countLabelRes = R.plurals.count_photos_plural
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(buckets, key = { "y${it.year}" }) { bucket ->
            TimeCoverCard(
                cover = bucket.cover,
                title = bucket.year.toString(),
                subtitle = pluralStringResource(countLabelRes, bucket.count, bucket.count),
                large = true,
                onRequestThumbnail = onRequestThumbnail,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.25f),
                onClick = { onYearClick(bucket.year) },
            )
        }
    }
}

/** Months as cover cards under a header per year, newest first. Opens scrolled to [focusYear] when
 *  one was picked on the Years view; tapping a month drills into the days of that month. */
@Composable
internal fun MonthsView(
    buckets: List<TimeBucket>,
    topPadding: Dp,
    focusYear: Int?,
    onMonthClick: (year: Int, month: Int) -> Unit,
    onRequestThumbnail: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val monthFormat = remember(locale) { java.text.SimpleDateFormat("LLLL", locale) }
    val byYear = remember(buckets) { buckets.groupBy { it.year }.toList() }
    val gridState = rememberLazyGridState()
    // Index of each year's header in the grid, for opening on the picked year.
    val headerIndex = remember(byYear) {
        var i = 0
        byYear.associate { (year, months) -> (year to i).also { i += 1 + months.size } }
    }
    LaunchedEffect(focusYear, headerIndex) {
        headerIndex[focusYear]?.let { gridState.scrollToItem(it) }
    }
    val countLabelRes = R.plurals.count_photos_plural
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        byYear.forEach { (year, months) ->
            item(key = "h$year", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    year.toString(),
                    color = FgPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            months.forEach { bucket ->
                val month = bucket.month ?: return@forEach
                item(key = "m$year-$month") {
                    val name = remember(year, month, monthFormat) {
                        monthFormat.format(java.util.GregorianCalendar(year, month - 1, 1).time)
                            .replaceFirstChar { it.titlecase(locale) }
                    }
                    TimeCoverCard(
                        cover = bucket.cover,
                        title = name,
                        subtitle = pluralStringResource(countLabelRes, bucket.count, bucket.count),
                        large = false,
                        onRequestThumbnail = onRequestThumbnail,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                        onClick = { onMonthClick(year, month) },
                    )
                }
            }
        }
    }
}

/** A cover photo with a title and count over a bottom gradient. */
@Composable
private fun TimeCoverCard(
    cover: GalleryItem,
    title: String,
    subtitle: String,
    large: Boolean,
    onRequestThumbnail: (String) -> Unit,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageModel: Any? = when (cover) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(cover.local.uri)
        is GalleryItem.Synced -> android.net.Uri.parse(cover.local.uri)
        is GalleryItem.CloudOnly -> LocalThumbnailUrls.current.value[cover.cloud.linkId] ?: cover.cloud.thumbnailUrl
    }
    // A cloud-only cover has no image until its thumbnail is decrypted; ask for it once.
    if (cover is GalleryItem.CloudOnly && imageModel == null) {
        LaunchedEffect(cover.cloud.linkId) { onRequestThumbnail(cover.cloud.linkId) }
    }
    val request = remember(imageModel) { ImageRequest.Builder(context).data(imageModel).size(if (large) 1024 else 512).build() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent))),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontSize = if (large) 34.sp else 22.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}
