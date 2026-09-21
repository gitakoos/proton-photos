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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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

// Fixed body height (weekday row + six week rows) so the card never resizes: the month and year
// pickers fill the exact same area as the day grid, and stepping months never changes the height.
private val CalendarBodyHeight = 264.dp
private val DayRowHeight = 40.dp
private val DayCellSize = 34.dp
// The range band is a touch thinner than the day dots, so the start/end dots read as fuller caps.
private val RangeBandHeight = 30.dp
private const val WeeksShown = 6

/** Which in-header picker is open over the day grid. */
private enum class DateSubPicker { None, Month, Year }

/**
 * Calendar-style date filter, all inside one contained card that shows the month grid straight away.
 * Both the month and the year in the header ("January 2025") are tappable: the month opens a month
 * grid, the year a year grid, each filling the same-sized body. Selection:
 *   - whole year:  tap the year, pick one
 *   - whole month: tap the month, pick one (also the default when no day is chosen)
 *   - single day:  tap a day; tapping it again drops back to the whole month
 *   - day range:   long-press a day and drag across the grid
 *
 * Clearing is handled by the sheet's own Reset action. Shared by the gallery and search filter sheets
 * via [onPick], which receives (year, month 1-12, day 1-31, dayEnd 1-31), any of which may be null;
 * [dayEnd] is the inclusive end of a range whose start is [day].
 */
@Composable
internal fun DateFilterCalendar(
    year: Int?,
    month: Int?,
    day: Int?,
    dayEnd: Int?,
    years: List<Int>,
    onPick: (year: Int?, month: Int?, day: Int?, dayEnd: Int?) -> Unit,
) {
    val colors = AppColors.current
    val today = remember { Calendar.getInstance() }
    val displayYear = year ?: today.get(Calendar.YEAR)

    var subPicker by remember { mutableStateOf(DateSubPicker.None) }
    var viewMonth by remember(displayYear) {
        mutableIntStateOf(
            month ?: if (displayYear == today.get(Calendar.YEAR)) today.get(Calendar.MONTH) + 1 else 1,
        )
    }
    LaunchedEffect(month) { if (month != null) viewMonth = month }

    val monthName = remember(displayYear, viewMonth) {
        SimpleDateFormat("LLLL", Locale.getDefault())
            .format(Calendar.getInstance().apply { set(displayYear, viewMonth - 1, 1) }.time)
            .replaceFirstChar { it.uppercase() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceWeak)
            .border(0.5.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        // Header: month arrows + a title whose month and year each open their own picker. The arrows
        // stay put in both modes (inert while a picker is open).
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavArrow(Icons.Filled.ChevronLeft, enabled = subPicker == DateSubPicker.None && viewMonth > 1) {
                if (viewMonth > 1) viewMonth--
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TitlePart(
                        text = monthName,
                        active = subPicker == DateSubPicker.Month,
                        onClick = { subPicker = if (subPicker == DateSubPicker.Month) DateSubPicker.None else DateSubPicker.Month },
                    )
                    Spacer(Modifier.width(2.dp))
                    TitlePart(
                        text = "$displayYear",
                        active = subPicker == DateSubPicker.Year,
                        onClick = { subPicker = if (subPicker == DateSubPicker.Year) DateSubPicker.None else DateSubPicker.Year },
                        trailing = {
                            Icon(
                                if (subPicker == DateSubPicker.None) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowDropUp,
                                contentDescription = stringResource(R.string.filter_year_label),
                                tint = colors.fgMute,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }
            NavArrow(Icons.Filled.ChevronRight, enabled = subPicker == DateSubPicker.None && viewMonth < 12) {
                if (viewMonth < 12) viewMonth++
            }
        }

        Spacer(Modifier.height(6.dp))

        // The body swaps between day grid, month grid and year grid at a fixed height, so the card
        // holds its size across all three.
        Box(modifier = Modifier.fillMaxWidth().height(CalendarBodyHeight)) {
            AnimatedContent(
                targetState = subPicker,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "dateBody",
            ) { picker ->
                when (picker) {
                    DateSubPicker.Year -> YearGrid(
                        modifier = Modifier.fillMaxSize(),
                        years = years,
                        selectedYear = year,
                        onPick = { y ->
                            onPick(y, null, null, null)
                            subPicker = DateSubPicker.None
                        },
                    )
                    DateSubPicker.Month -> MonthPickerGrid(
                        modifier = Modifier.fillMaxSize(),
                        selectedMonth = month,
                        onPick = { m ->
                            onPick(displayYear, m, null, null)
                            subPicker = DateSubPicker.None
                        },
                    )
                    DateSubPicker.None -> DayGridBody(
                        modifier = Modifier.fillMaxSize(),
                        year = displayYear,
                        viewMonth = viewMonth,
                        selectedMonth = month,
                        selectedDay = day,
                        selectedDayEnd = dayEnd,
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

/** One tappable segment of the header title (the month or the year), tinted accent while its picker
 *  is open, with an optional trailing caret. */
@Composable
private fun TitlePart(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (active) colors.accent else colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.invoke()
    }
}

/** The weekday header plus the sliding month grid, filling the fixed body height. */
@Composable
private fun DayGridBody(
    modifier: Modifier,
    year: Int,
    viewMonth: Int,
    selectedMonth: Int?,
    selectedDay: Int?,
    selectedDayEnd: Int?,
    onPick: (year: Int?, month: Int?, day: Int?, dayEnd: Int?) -> Unit,
) {
    val colors = AppColors.current
    // Locale-aware week start (Sunday-first vs Monday-first) drives both the header order and the
    // leading-blank count so the day columns line up with their weekday labels.
    val firstDayOfWeek = remember { Calendar.getInstance().firstDayOfWeek }
    val shortWeekdays = remember { DateFormatSymbols.getInstance().shortWeekdays }
    val weekdayLabels = remember(firstDayOfWeek) {
        (0 until 7).map { i ->
            val wd = ((firstDayOfWeek - 1 + i) % 7) + 1
            shortWeekdays[wd].take(1).uppercase()
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
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
        // The grid slides left/right when the month changes so stepping through months feels
        // continuous; every month draws six week rows, so the height is constant.
        AnimatedContent(
            targetState = viewMonth,
            transitionSpec = {
                val forward = targetState > initialState
                if (forward) {
                    (slideInHorizontally(tween(220)) { it } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(tween(220)) { -it } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(140)))
                }
            },
            label = "monthGrid",
        ) { shownMonth ->
            MonthGrid(
                year = year,
                viewMonth = shownMonth,
                selectedMonth = selectedMonth,
                selectedDay = selectedDay,
                selectedDayEnd = selectedDayEnd,
                onPick = onPick,
            )
        }
    }
}

/** One month's day grid, always six week rows tall so months swap without the card resizing. A tap
 *  picks a single day; a long-press then drag sweeps a day range across the grid. */
@Composable
private fun MonthGrid(
    year: Int,
    viewMonth: Int,
    selectedMonth: Int?,
    selectedDay: Int?,
    selectedDayEnd: Int?,
    onPick: (year: Int?, month: Int?, day: Int?, dayEnd: Int?) -> Unit,
) {
    val colors = AppColors.current
    val density = LocalDensity.current
    val firstDayOfWeek = remember { Calendar.getInstance().firstDayOfWeek }
    val daysInMonth = remember(year, viewMonth) {
        Calendar.getInstance().apply { set(year, viewMonth - 1, 1) }
            .getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val leadingBlanks = remember(year, viewMonth, firstDayOfWeek) {
        val fw = Calendar.getInstance().apply { set(year, viewMonth - 1, 1) }.get(Calendar.DAY_OF_WEEK)
        (fw - firstDayOfWeek + 7) % 7
    }
    val rowPx = with(density) { DayRowHeight.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var lastDrag by remember { mutableStateOf<Int?>(null) }

    fun dayAt(pos: Offset): Int? {
        if (widthPx <= 0) return null
        val col = (pos.x / (widthPx / 7f)).toInt().coerceIn(0, 6)
        val row = (pos.y / rowPx).toInt()
        if (row < 0 || row >= WeeksShown) return null
        val d = row * 7 + col - leadingBlanks + 1
        return if (d in 1..daysInMonth) d else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .pointerInput(year, viewMonth, daysInMonth, leadingBlanks) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        val d = dayAt(off)
                        if (d != null) {
                            anchor = d
                            lastDrag = d
                            onPick(year, viewMonth, d, null)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val a = anchor ?: return@detectDragGesturesAfterLongPress
                        val d = dayAt(change.position) ?: return@detectDragGesturesAfterLongPress
                        // Only push a filter update when the swept day actually changes, not per pixel.
                        if (d != lastDrag) {
                            lastDrag = d
                            val lo = minOf(a, d)
                            val hi = maxOf(a, d)
                            onPick(year, viewMonth, lo, if (lo == hi) null else hi)
                        }
                    },
                    onDragEnd = { anchor = null },
                    onDragCancel = { anchor = null },
                )
            },
    ) {
        for (week in 0 until WeeksShown) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - leadingBlanks + 1
                    val inMonth = dayNum in 1..daysInMonth
                    val onThisMonth = selectedMonth == viewMonth && selectedDay != null
                    val inRange = onThisMonth && selectedDayEnd != null && dayNum in selectedDay!!..selectedDayEnd
                    val isSingle = onThisMonth && selectedDayEnd == null && selectedDay == dayNum
                    val isStart = inRange && dayNum == selectedDay
                    val isEnd = inRange && dayNum == selectedDayEnd
                    val filled = isSingle || isStart || isEnd
                    Box(
                        modifier = Modifier.weight(1f).height(DayRowHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Range band: a thin bar behind the dots. At the ends it fills only the half
                        // toward the middle, so the fuller start/end dots cap it into one connected run.
                        if (inRange) {
                            val bandModifier = when {
                                isStart && isEnd -> Modifier.fillMaxWidth()
                                isStart -> Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f)
                                isEnd -> Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f)
                                else -> Modifier.fillMaxWidth()
                            }
                            Box(
                                bandModifier
                                    .height(RangeBandHeight)
                                    .background(colors.accent.copy(alpha = 0.16f)),
                            )
                        }
                        if (inMonth) {
                            Box(
                                modifier = Modifier
                                    .size(DayCellSize)
                                    .clip(CircleShape)
                                    .background(if (filled) colors.accent else Color.Transparent)
                                    .clickable {
                                        if (isSingle) onPick(year, viewMonth, null, null)
                                        else onPick(year, viewMonth, dayNum, null)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$dayNum",
                                    color = when {
                                        filled -> Color.White
                                        inRange -> colors.accent
                                        else -> colors.fgPrimary
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The year picker: a four-column grid filling the same body area as the day grid. */
@Composable
private fun YearGrid(
    modifier: Modifier,
    years: List<Int>,
    selectedYear: Int?,
    onPick: (Int) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        years.chunked(4).forEach { rowYears ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowYears.forEach { y ->
                    GridCell(
                        label = "$y",
                        selected = selectedYear == y,
                        modifier = Modifier.weight(1f).height(50.dp),
                        onClick = { onPick(y) },
                    )
                }
                repeat(4 - rowYears.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The month picker: a three-column grid of month names filling the same body area as the day grid. */
@Composable
private fun MonthPickerGrid(
    modifier: Modifier,
    selectedMonth: Int?,
    onPick: (Int) -> Unit,
) {
    val shortMonths = remember { DateFormatSymbols.getInstance().shortMonths }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        (1..12).chunked(3).forEach { rowMonths ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMonths.forEach { m ->
                    GridCell(
                        label = shortMonths[m - 1].replaceFirstChar { it.uppercase() },
                        selected = selectedMonth == m,
                        modifier = Modifier.weight(1f).height(50.dp),
                        onClick = { onPick(m) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridCell(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent else colors.chipUnselectedBg)
            .then(if (!selected) Modifier.border(0.5.dp, colors.line, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else colors.fgPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun NavArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) colors.fgPrimary else colors.fgMute.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}
