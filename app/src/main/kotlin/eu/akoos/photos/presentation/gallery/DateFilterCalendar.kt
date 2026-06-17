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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Calendar-style date filter. A year strip selects the year (or clears it); once a year is chosen a
 * month-by-month calendar appears so the user can pick an exact day, a whole month, or stay on the
 * whole year. Month is never mandatory — each level is optional:
 *   - year only:        tap a year chip
 *   - year + month:     tap "whole month"
 *   - year + month + day: tap a day cell
 *
 * Shared by the gallery and search filter sheets via [onPick], which receives the chosen
 * (year, month 1-12, day 1-31) — any of which may be null.
 */
@Composable
internal fun DateFilterCalendar(
    year: Int?,
    month: Int?,
    day: Int?,
    years: List<Int>,
    onPick: (year: Int?, month: Int?, day: Int?) -> Unit,
) {
    val colors = AppColors.current

    SectionLabel(stringResource(R.string.filter_year_label))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "year_any") {
            FilterChip(
                label = stringResource(R.string.filter_any),
                selected = year == null,
                onClick = { onPick(null, null, null) },
            )
        }
        years.forEach { y ->
            item(key = "year_$y") {
                // Selecting a year resets any month/day so the picker reopens at the year level.
                FilterChip(label = "$y", selected = year == y, onClick = { onPick(y, null, null) })
            }
        }
    }

    if (year != null) {
        val today = remember { Calendar.getInstance() }
        var viewMonth by remember(year) {
            mutableIntStateOf(
                month ?: if (year == today.get(Calendar.YEAR)) today.get(Calendar.MONTH) + 1 else 1,
            )
        }
        // Keep the visible month aligned when a month is set from elsewhere (e.g. "jump to month").
        LaunchedEffect(month) { if (month != null) viewMonth = month }

        val monthName = remember(year, viewMonth) {
            SimpleDateFormat("LLLL", Locale.getDefault())
                .format(Calendar.getInstance().apply { set(year, viewMonth - 1, 1) }.time)
                .replaceFirstChar { it.uppercase() }
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavArrow("‹", enabled = viewMonth > 1, on = colors.fgPrimary, off = colors.fgMute) {
                if (viewMonth > 1) viewMonth--
            }
            Text(
                text = "$monthName $year",
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            NavArrow("›", enabled = viewMonth < 12, on = colors.fgPrimary, off = colors.fgMute) {
                if (viewMonth < 12) viewMonth++
            }
        }

        // Locale-aware week start (Sunday-first vs Monday-first) drives both the header order and
        // the leading-blank count so the day columns line up with their weekday labels.
        val firstDayOfWeek = remember { Calendar.getInstance().firstDayOfWeek }
        val shortWeekdays = remember { DateFormatSymbols.getInstance().shortWeekdays }
        val weekdayLabels = remember(firstDayOfWeek) {
            (0 until 7).map { i ->
                val wd = ((firstDayOfWeek - 1 + i) % 7) + 1
                shortWeekdays[wd].take(1).uppercase()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            weekdayLabels.forEach { wl ->
                Text(
                    wl,
                    color = colors.fgMute,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val daysInMonth = remember(year, viewMonth) {
            Calendar.getInstance().apply { set(year, viewMonth - 1, 1) }
                .getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        val leadingBlanks = remember(year, viewMonth, firstDayOfWeek) {
            val fw = Calendar.getInstance().apply { set(year, viewMonth - 1, 1) }.get(Calendar.DAY_OF_WEEK)
            (fw - firstDayOfWeek + 7) % 7
        }
        val weeks = (leadingBlanks + daysInMonth + 6) / 7
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..daysInMonth) {
                            val selected = month == viewMonth && day == dayNum
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) colors.accent else Color.Transparent)
                                    .clickable {
                                        // Tapping the selected day again drops back to the whole month.
                                        if (selected) onPick(year, viewMonth, null)
                                        else onPick(year, viewMonth, dayNum)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$dayNum",
                                    color = if (selected) Color.White else colors.fgPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        FilterChip(
            label = stringResource(R.string.filter_whole_month),
            selected = month == viewMonth && day == null,
            onClick = {
                // Toggle: whole month on, or back to the whole year if it was already on.
                if (month == viewMonth && day == null) onPick(year, null, null)
                else onPick(year, viewMonth, null)
            },
        )
    }
}

@Composable
private fun NavArrow(glyph: String, enabled: Boolean, on: Color, off: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) on else off, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = AppColors.current.fgMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}
