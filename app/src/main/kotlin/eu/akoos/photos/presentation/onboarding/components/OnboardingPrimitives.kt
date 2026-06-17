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

package eu.akoos.photos.presentation.onboarding.components

import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

// ─────────────────────────────────────────────────────────────────────────────
// Shared building blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ProgressDots(currentIndex: Int, total: Int) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in 0 until total) {
            val isActive = i == currentIndex
            val isPast = i < currentIndex
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = if (isActive) 18.dp else 6.dp, height = 6.dp)
                    .background(
                        color = when {
                            isActive -> colors.accent
                            isPast -> colors.accent.copy(alpha = 0.5f)
                            else -> colors.cardBorder
                        },
                        shape = RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

@Composable
internal fun StepHeader(icon: ImageVector, title: String, subtitle: String) {
    val colors = AppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(72.dp).background(
                Brush.linearGradient(
                    listOf(colors.accent, colors.accent2),
                    Offset.Zero,
                    Offset(180f, 180f),
                ),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = colors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            color = colors.fgMute,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small reusable composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SmallLabel(text: String) {
    val colors = AppColors.current
    Text(
        text.uppercase(),
        color = colors.fgMute,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
internal fun LinkCard(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.fgMute, fontSize = 11.5.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            null,
            tint = colors.fgMute,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun ChoiceCard(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) colors.accent.copy(alpha = 0.15f) else colors.cardBg,
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) colors.accent else colors.cardBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.accent.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_recommended),
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(description, color = colors.fgMute, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (selected) colors.accent else Color.Transparent,
                    shape = CircleShape,
                )
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = colors.cardBorder,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun ToggleCard(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, color = colors.fgMute, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = onboardingSwitchColors())
    }
}

@Composable
internal fun SubToggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = onboardingSwitchColors())
    }
}

@Composable
internal fun onboardingSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppColors.current.accent,
    uncheckedThumbColor = AppColors.current.fgDim,
    uncheckedTrackColor = AppColors.current.surfaceWeak,
    uncheckedBorderColor = AppColors.current.cardBorder,
)

@Composable
internal fun AllowButton(label: String, granted: Boolean, onClick: () -> Unit) {
    // Granted shows a neutral "done" state with a check; not-yet-granted is the accent CTA.
    // Both route through the shared buttons so onboarding matches the rest of the app.
    if (granted) {
        SecondaryButton(
            label = label,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.Check,
            enabled = false,
        )
    } else {
        PrimaryButton(label = label, onClick = onClick, modifier = Modifier.fillMaxWidth())
    }
}
