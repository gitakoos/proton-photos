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

package eu.akoos.photos.presentation.onboarding.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * The wizard's top row, built like the app's floating header: the round back button on the left and a pill in the
 * middle saying which step this is. The first and the last step have no back button.
 */
@Composable
internal fun WizardTopBar(step: Int, total: Int, onBack: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp)) {
            if (onBack != null) {
                IconBubble(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.onboarding_back),
                    onClick = onBack,
                )
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                "$step / $total",
                color = AppColors.current.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(PillBg, RoundedCornerShape(20.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
        Spacer(Modifier.size(40.dp))
    }
}

/** A step's title and one line under it, left-aligned like the app's page headers. */
@Composable
internal fun StepHeader(title: String, subtitle: String) {
    val colors = AppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = colors.fgPrimary,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = colors.fgMute, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

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

/** One option of a single choice, as a row inside a settings card; the chosen one carries an accent check. */
@Composable
internal fun ChoiceRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** What a permission step asks for, as a settings row that reads "Granted" once it is in place. */
@Composable
internal fun PermissionRow(icon: ImageVector, label: String, granted: Boolean) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fgDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(Icons.Default.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.permissions_state_granted),
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
