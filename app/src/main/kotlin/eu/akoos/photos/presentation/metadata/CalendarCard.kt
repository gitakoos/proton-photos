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

package eu.akoos.photos.presentation.metadata

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.theme.AppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private const val FIRST_YEAR = 1900
private const val DAY_ROWS = 6

/** Years offered after the current one when no latest day closes the range. */
private const val YEARS_AHEAD = 10

/**
 * The cells of one month laid out in weeks that start on [firstDayOfWeek]: [null] for the blank cells before
 * the first and after the last day, so the list is always a whole number of weeks.
 */
internal fun monthCells(year: Int, month: Int, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
    val first = LocalDate.of(year, month, 1)
    val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = ArrayList<LocalDate?>()
    repeat(lead) { cells.add(null) }
    for (day in 1..first.lengthOfMonth()) cells.add(first.withDayOfMonth(day))
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

/** Whether [day] may be picked: every day is, unless a [latest] day closes the ones after it. */
internal fun isDayEnabled(day: LocalDate, latest: LocalDate?): Boolean = latest == null || !day.isAfter(latest)

/**
 * A light calendar card: a month grid with previous and next buttons, and a month and year chooser behind
 * the title. [selectedDay] and the value passed to [onPick] are UTC midnights of the calendar day, the
 * convention the date and time fields use. Days after [latest] are shown dimmed and cannot be picked.
 */
@Composable
internal fun CalendarCard(selectedDay: Long, latest: LocalDate?, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(14.dp)
    val locale = Locale.getDefault()
    val selected = remember(selectedDay) { utcMillisToDate(selectedDay) }
    var shown by remember(selectedDay) { mutableStateOf(YearMonth.from(selected)) }
    var monthMode by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val lastMonth = latest?.let { YearMonth.from(it) }
    val titleFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardFill(colors.cardBg))
            .border(0.5.dp, colors.line2, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { monthMode = !monthMode }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    titleFormatter.format(shown.atDay(1)).replaceFirstChar { it.uppercase(locale) },
                    color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Icon(
                    if (monthMode) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null, tint = colors.fgDim, modifier = Modifier.size(20.dp),
                )
            }
            Box(Modifier.weight(1f))
            if (!monthMode) {
                StepButton(Icons.Default.ChevronLeft, enabled = shown.year > FIRST_YEAR || shown.monthValue > 1) {
                    shown = shown.minusMonths(1)
                }
                StepButton(Icons.Default.ChevronRight, enabled = lastMonth == null || shown.isBefore(lastMonth)) {
                    shown = shown.plusMonths(1)
                }
            }
        }
        Crossfade(targetState = monthMode, label = "calendarMode", modifier = Modifier.fillMaxWidth().height(268.dp)) { months ->
            if (months) {
                MonthChooser(shown, lastMonth, locale) { picked ->
                    shown = picked
                    monthMode = false
                }
            } else {
                DayGrid(shown, selected, today, latest, locale) { onPick(dateToUtcMillis(it)) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (enabled) colors.fgPrimary else colors.fgMute.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun DayGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    latest: LocalDate?,
    locale: Locale,
    onDay: (LocalDate) -> Unit,
) {
    val colors = AppColors.current
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDay) { monthCells(month.year, month.monthValue, firstDay) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            for (i in 0 until 7) {
                Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                        color = colors.fgMute, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
        for (row in 0 until DAY_ROWS) {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                for (col in 0 until 7) {
                    val day = cells.getOrNull(row * 7 + col)
                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                isSelected = day == selected,
                                isToday = day == today,
                                enabled = isDayEnabled(day, latest),
                                description = dateFormat.format(day),
                                onClick = { onDay(day) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, isSelected: Boolean, isToday: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val colors = AppColors.current
    val textColor = when {
        isSelected -> Color.White
        !enabled -> colors.fgMute.copy(alpha = 0.4f)
        isToday -> colors.accent
        else -> colors.fgPrimary
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(colors.accent) else Modifier)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.accent, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            color = textColor, fontSize = 14.sp,
            fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MonthChooser(shown: YearMonth, lastMonth: YearMonth?, locale: Locale, onPick: (YearMonth) -> Unit) {
    val colors = AppColors.current
    var year by remember(shown) { mutableStateOf(shown.year) }
    val lastYear = lastMonth?.year
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            for (row in 0 until 3) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until 4) {
                        val month = row * 4 + col + 1
                        val target = YearMonth.of(year, month)
                        val enabled = lastMonth == null || !target.isAfter(lastMonth)
                        val isSelected = target == shown
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(50))
                                .then(if (isSelected) Modifier.background(colors.accent) else Modifier)
                                .clickable(enabled = enabled) { onPick(target) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                java.time.Month.of(month).getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                                color = when {
                                    isSelected -> Color.White
                                    !enabled -> colors.fgMute.copy(alpha = 0.4f)
                                    else -> colors.fgPrimary
                                },
                                fontSize = 14.sp, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Column {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.line2))
            // The years scroll sideways, so choosing one never drags the sheet the calendar sits in.
            val years = remember(lastYear) { (FIRST_YEAR..(lastYear ?: (LocalDate.now().year + YEARS_AHEAD))).toList() }
            val yearList = rememberLazyListState(
                initialFirstVisibleItemIndex = (years.indexOf(year) - 2).coerceAtLeast(0),
            )
            LazyRow(
                state = yearList,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(years, key = { it }) { y ->
                    val isYear = y == year
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .then(if (isYear) Modifier.background(colors.accent.copy(alpha = 0.18f)) else Modifier)
                            .clickable { year = y }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            y.toString(),
                            color = if (isYear) colors.accent else colors.fgPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (isYear) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private fun utcMillisToDate(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

private fun dateToUtcMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
