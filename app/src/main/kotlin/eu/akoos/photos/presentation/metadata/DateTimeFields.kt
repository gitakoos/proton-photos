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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import eu.akoos.photos.presentation.util.formatTimeOfDay
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The date and time picker the details sheet and the metadata editor share: the picked moment written out
 * on top, the calendar card, and the time as one
 * field that opens an input under it. Every change goes to [onChange]; [latestMs] closes the days after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeFields(seedMs: Long, onChange: (Long) -> Unit, latestMs: Long? = null) {
    val colors = AppColors.current
    val seed = remember(seedMs) { Instant.ofEpochMilli(seedMs).atZone(ZoneId.systemDefault()) }
    var pickedDay by remember(seedMs) { mutableStateOf(toUtcMidnight(seedMs)) }
    val timeState = key(seedMs) {
        rememberTimePickerState(initialHour = seed.hour, initialMinute = seed.minute, is24Hour = LocalIs24Hour.current)
    }
    var timeOpen by remember { mutableStateOf(false) }
    val chosen = combineDateTime(pickedDay, timeState.hour, timeState.minute)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(chosen) { change(chosen) }
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            remember(chosen) { formatDateTimeMinute(chosen) },
            color = colors.fgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        CalendarCard(
            selectedDay = pickedDay,
            latest = latestMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
            onPick = { pickedDay = it },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cardFill(colors.cardBg))
                .border(0.5.dp, colors.line2, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { timeOpen = !timeOpen }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.details_time), color = colors.fgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    remember(chosen, timeState.is24hour) { formatTimeOfDay(chosen, Locale.getDefault(), timeState.is24hour) },
                    color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.fgPrimary.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            if (timeOpen) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.line2))
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    TimeInput(
                        state = timeState,
                        colors = TimePickerDefaults.colors(
                            timeSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            timeSelectorUnselectedContainerColor = cardFill(colors.cardBg),
                            timeSelectorSelectedContentColor = colors.fgPrimary,
                            timeSelectorUnselectedContentColor = colors.fgPrimary,
                            periodSelectorBorderColor = colors.line2,
                            periodSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            periodSelectorSelectedContentColor = colors.fgPrimary,
                            periodSelectorUnselectedContentColor = colors.fgDim,
                        ),
                    )
                }
            }
        }
    }
}
