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

package eu.akoos.photos.presentation.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Search overlay over the calendar: a slim field; typing a month or year lists the matching
 *  months as buttons, and tapping one scrolls the calendar there. Mirrors the map's search feel. */
@Composable
fun CalendarSearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    months: List<MonthBucket>,
    onMonthPick: (year: Int, month: Int) -> Unit,
    searchResults: List<DayBucket>,
    onDayClick: (DayBucket) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val monthFmt = remember { SimpleDateFormat("MMMM", Locale.getDefault()) }
    val cal = remember { Calendar.getInstance() }
    val matches = remember(months, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            months
        } else {
            months.filter { b ->
                cal.set(b.year, b.month - 1, 1)
                val label = "${monthFmt.format(cal.time)} ${b.year}"
                label.contains(q, ignoreCase = true) || b.year.toString().contains(q)
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 56.dp, bottom = 16.dp),
        ) {
            SearchPill(query, onQueryChange)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.current.pillBorder),
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ResultsList(matches, monthFmt, onMonthPick, searchResults, onDayClick)
            }
        }
    }
}

@Composable
private fun SearchPill(query: String, onQueryChange: (String) -> Unit) {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100))
            .background(colors.pillBg, RoundedCornerShape(100))
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(100))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = colors.fgMute, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.calendar_search_placeholder),
                    color = colors.fgMute,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.fgPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = colors.fgMute,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun ResultsList(
    months: List<MonthBucket>,
    monthFmt: SimpleDateFormat,
    onMonthPick: (Int, Int) -> Unit,
    days: List<DayBucket>,
    onDayClick: (DayBucket) -> Unit,
) {
    val colors = AppColors.current
    val cal = remember { Calendar.getInstance() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(months, key = { "${it.year}-${it.month}" }) { b ->
            cal.set(b.year, b.month - 1, 1)
            val label = "${monthFmt.format(cal.time)} ${b.year}"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.pillBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, colors.pillBorder, RoundedCornerShape(14.dp))
                    .clickable { onMonthPick(b.year, b.month) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = colors.fgPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        items(days, key = { "d-${it.date}" }) { day ->
            DayResultRow(day, onDayClick)
        }
    }
}

@Composable
private fun DayResultRow(day: DayBucket, onDayClick: (DayBucket) -> Unit) {
    val colors = AppColors.current
    val dayFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val dateLabel = remember(day.date) {
        val c = Calendar.getInstance()
        val p = day.date.split('-')
        if (p.size == 3) c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        dayFmt.format(c.time)
    }
    val cover = photoCellInputsFor(day.coverItem).imageData
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.pillBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(14.dp))
            .clickable { onDayClick(day) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            dateLabel,
            color = colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.count_photos_plural, day.items.size, day.items.size),
            color = colors.fgMute,
            fontSize = 13.sp,
        )
    }
}
