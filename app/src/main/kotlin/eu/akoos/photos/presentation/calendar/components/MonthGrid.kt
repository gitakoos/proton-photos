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

package eu.akoos.photos.presentation.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.calendar.DayBucket
import eu.akoos.photos.presentation.calendar.MonthBucket
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Renders a single month: a textual header plus a 7-column grid of day cells.
 *
 * Days with photos show the (auto or user-picked) representative thumbnail with the
 * day number overlaid; empty days show just the day number on a flat surface. Leading
 * blanks (e.g. Wed when the 1st is a Thu) keep the visual alignment honest. The week
 * starts on the system's first day of week so French/American/etc. installs feel native.
 *
 * [expanded] controls how the grid sizes its rows:
 *  - `false` (default — used by the stacked LazyColumn layout): rows take their natural
 *    height driven by a 1:1 [aspectRatio] on each cell. The whole grid lays out top-down
 *    inside the parent scroll container.
 *  - `true` (used by the per-month [MonthPager]): the grid is asked to fill the parent's
 *    available height, with each of the (up to) six week rows sharing the vertical space
 *    via [weight], so a single-month page feels like a full-screen calendar rather than
 *    a small block of cells with empty space underneath.
 */
@Composable
fun MonthGrid(
    bucket: MonthBucket,
    onDayClick: (DayBucket) -> Unit,
    expanded: Boolean = false,
) {
    val colors = AppColors.current
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, bucket.year)
        set(Calendar.MONTH, bucket.month - 1)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = cal.firstDayOfWeek
    val weekdayOfFirst = cal.get(Calendar.DAY_OF_WEEK)
    // Number of blank cells before day 1 so the calendar starts on the right column.
    val leadingBlanks = ((weekdayOfFirst - firstDayOfWeek) + 7) % 7

    // Total cells to render (leading blanks + month days). Trailing blanks are NOT padded
    // — the last row stops at the last day so the grid has no dangling empty cells.
    val totalCells = leadingBlanks + daysInMonth
    val rowCount = (totalCells + 6) / 7

    val monthLabel = remember(bucket.year, bucket.month) {
        val dateCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, bucket.year); set(Calendar.MONTH, bucket.month - 1); set(Calendar.DAY_OF_MONTH, 1)
        }
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(dateCal.time)
    }

    // Outer column sizing depends on the mode:
    //  - stacked (expanded=false): wraps content vertically so each month consumes only
    //    the natural row-height inside the parent LazyColumn,
    //  - per-month (expanded=true): fills the page so the row weights below have a real
    //    height to divide up.
    val outerModifier = if (expanded) {
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    }
    Column(modifier = outerModifier) {
        Text(
            text = monthLabel,
            color = colors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Weekday header strip (Mon/Tue/...): one row, faint text, evenly spaced.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val weekdayFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            val weekdayCal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            }
            for (i in 0 until 7) {
                Box(
                    modifier = Modifier
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = weekdayFmt.format(weekdayCal.time).take(2),
                        color = colors.fgMute,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                weekdayCal.add(Calendar.DAY_OF_WEEK, 1)
            }
        }

        // Day grid — manual rows of 7 weighted cells. We render row-by-row instead of using
        // LazyVerticalGrid because the whole month is part of a parent LazyColumn already —
        // nesting two lazy verticals throws at runtime.
        //
        // Row height differs by mode:
        //  - stacked (expanded=false): row height is implicit, driven by each cell's 1:1
        //    aspectRatio against the column width / 7.
        //  - per-month (expanded=true): every row carries weight(1f) so all six potential
        //    rows share the page's remaining vertical space evenly, and cells fill their
        //    row's height instead of being square. This makes the grid feel like a full
        //    page-sized calendar instead of a small block at the top of an empty page.
        //    We always emit six rows in expanded mode so that the weights stay balanced
        //    across months (Feb 28d vs Jul 31d would otherwise resize when paging).
        val emittedRowCount = if (expanded) 6 else rowCount
        for (rowIdx in 0 until emittedRowCount) {
            val rowModifier = if (expanded) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 2.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            }
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until 7) {
                    val cellIdx = rowIdx * 7 + col
                    val day = cellIdx - leadingBlanks + 1
                    val cellSizing = if (expanded) {
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    } else {
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                    }
                    if (cellIdx < leadingBlanks || day > daysInMonth) {
                        // Empty cell to preserve column alignment without drawing anything.
                        Spacer(cellSizing)
                    } else {
                        val dayBucket = bucket.days[day]
                        Box(modifier = cellSizing) {
                            DayCell(
                                day = day,
                                dayBucket = dayBucket,
                                onClick = { dayBucket?.let(onDayClick) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    dayBucket: DayBucket?,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(10.dp)
    val hasContent = dayBucket != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(if (hasContent) Color.Black else colors.bg0)
            .border(0.5.dp, PillBorder, shape)
            .let { mod -> if (hasContent) mod.clickable(onClick = onClick) else mod },
    ) {
        if (dayBucket != null) {
            val coverModel: Any? = when (val item = dayBucket.coverItem) {
                is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
                is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
            }
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom gradient so the day number stays legible on bright covers.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )
            // Video indicator when the cover is a video — single small icon top-right.
            val isVideo = when (val it = dayBucket.coverItem) {
                is GalleryItem.LocalOnly -> it.local.mimeType.startsWith("video/")
                is GalleryItem.Synced    -> it.local.mimeType.startsWith("video/")
                is GalleryItem.CloudOnly -> it.cloud.mimeType.startsWith("video/")
            }
            if (isVideo) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                )
            }
            // Day number bottom-left.
            Text(
                text = day.toString(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 2.dp),
            )
            // Location pin badge when there's a location label on this day.
            if (!dayBucket.locationText.isNullOrBlank()) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp),
                )
            }
        } else {
            // Empty day — just the number on a flat surface.
            Text(
                text = day.toString(),
                color = colors.fgMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.Center),
            )
        }
    }
}

