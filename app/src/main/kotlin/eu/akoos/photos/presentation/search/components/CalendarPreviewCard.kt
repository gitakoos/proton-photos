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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/**
 * "Calendar" entry card on the Search idle page. A slim, full-width card that mirrors the map
 * card's framing — same corner radius and a hairline pill border — at roughly half the height. The
 * preview is a small decorative month grid (a fixed 7×4 cluster of dots, not live data) beside a
 * title + subtitle. The whole card is tappable and routes to the calendar page via [onClick].
 *
 * It sits directly below [MapPreviewCard] in the idle list, sharing the same horizontal insets.
 */
@Composable
fun CalendarPreviewCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 4.dp)
            .height(80.dp)
            .clip(shape)
            .background(colors.cardBg)
            .border(0.5.dp, colors.pillBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MiniMonthGrid()

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = null,
                    tint = colors.fgPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.calendar_title),
                    color = colors.fgPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = stringResource(R.string.map_card_calendar_subtitle),
                color = colors.fgDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Decorative calendar swatch: weekday-initial header row over a 7×4 grid of small dots, accent-
 * tinted, with one dot filled in the app accent to read as a "selected day". Purely static — it
 * carries no date meaning, so it never needs live data or date math.
 */
@Composable
private fun MiniMonthGrid() {
    val colors = AppColors.current
    val cell = 4.dp
    val gap = 3.dp
    // A single highlighted cell so the swatch reads as a calendar with a marked day.
    val highlightRow = 1
    val highlightCol = 3

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(7) {
                Box(
                    modifier = Modifier
                        .size(cell)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.fgDim.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(1.dp))
        repeat(4) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(7) { col ->
                    val isHighlight = row == highlightRow && col == highlightCol
                    Box(
                        modifier = Modifier
                            .size(cell)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isHighlight) colors.accent
                                else colors.fgDim.copy(alpha = 0.22f),
                            ),
                    )
                }
            }
        }
    }
}
