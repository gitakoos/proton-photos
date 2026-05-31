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

package eu.akoos.photos.presentation.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.SmallLabel
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.onboarding.components.ToggleCard
import eu.akoos.photos.presentation.theme.AppColors

@Composable
internal fun AppLockStep(
    enabled: Boolean,
    timeoutMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeoutChange: (Int) -> Unit,
) {
    val colors = AppColors.current
    Column {
        StepHeader(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.onboarding_lock_title),
            subtitle = stringResource(R.string.onboarding_lock_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        ToggleCard(
            label = stringResource(R.string.onboarding_lock_enable),
            description = stringResource(R.string.onboarding_lock_enable_desc),
            checked = enabled,
            onChange = onEnabledChange,
        )
        if (enabled) {
            Spacer(Modifier.height(14.dp))
            SmallLabel(stringResource(R.string.onboarding_lock_timeout_label))
            Spacer(Modifier.height(8.dp))
            val options = listOf(0, 1, 5, 10, 15, 60)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
            ) {
                options.forEachIndexed { idx, mins ->
                    val selected = mins == timeoutMinutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTimeoutChange(mins) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = timeoutLabel(mins), color = colors.fgPrimary, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        if (selected) {
                            Box(modifier = Modifier.size(20.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (idx < options.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(colors.cardBorder))
                    }
                }
            }
        }
    }
}

@Composable
private fun timeoutLabel(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.settings_app_lock_timeout_immediate)
    1 -> stringResource(R.string.settings_app_lock_timeout_1min)
    5 -> stringResource(R.string.settings_app_lock_timeout_5min)
    10 -> stringResource(R.string.settings_app_lock_timeout_10min)
    15 -> stringResource(R.string.settings_app_lock_timeout_15min)
    60 -> stringResource(R.string.settings_app_lock_timeout_1h)
    else -> "$minutes min"
}
