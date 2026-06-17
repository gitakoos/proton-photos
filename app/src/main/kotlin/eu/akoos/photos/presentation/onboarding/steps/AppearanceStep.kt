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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.SmallLabel
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.paletteAccent

@Composable
internal fun AppearanceStep(
    themeMode: ThemeMode,
    palette: ThemePalette,
    language: String,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (ThemePalette) -> Unit,
    onLanguage: (String) -> Unit,
) {
    val colors = AppColors.current
    Column {
        StepHeader(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.onboarding_appearance_title),
            subtitle = stringResource(R.string.onboarding_appearance_subtitle),
        )
        Spacer(Modifier.height(18.dp))

        // Theme pills
        SmallLabel(stringResource(R.string.theme_mode_section))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = themeMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            color = if (selected) colors.accent.copy(alpha = 0.22f) else colors.cardBg,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .border(
                            width = if (selected) 1.dp else 0.5.dp,
                            color = if (selected) colors.accent else colors.cardBorder,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .clickable { onThemeMode(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(mode.labelRes),
                        color = if (selected) colors.accent else colors.fgPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SmallLabel(stringResource(R.string.settings_palette_section))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(999.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemePalette.entries.forEach { p ->
                val swatch = paletteAccent(p, isLight = colors.isLight)
                val selected = palette == p
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(swatch, CircleShape)
                        .border(
                            width = if (selected) 2.dp else 0.5.dp,
                            color = if (selected) colors.fgPrimary else colors.cardBorder,
                            shape = CircleShape,
                        )
                        .clickable { onPalette(p) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SmallLabel(stringResource(R.string.language_section))
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
        ) {
            val options = listOf(
                "system" to R.string.settings_language_system,
                "en" to R.string.language_en,
                "de" to R.string.language_de,
                "es" to R.string.language_es,
                "fr" to R.string.language_fr,
                "hu" to R.string.language_hu,
                "it" to R.string.language_it,
                "nl" to R.string.language_nl,
            )
            options.forEachIndexed { idx, (tag, labelRes) ->
                val selected = language == tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguage(tag) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(labelRes),
                        color = colors.fgPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(colors.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
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
