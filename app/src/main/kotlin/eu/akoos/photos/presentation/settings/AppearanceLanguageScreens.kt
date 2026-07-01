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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.paletteAccent

/**
 * Appearance hub — menu rows to the theme/palette and language sub-pages, plus the photo
 * timeline page. Kept thin so each concern lives on its own focused page instead of one
 * long mixed scroll.
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onThemeClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onLayoutClick: () -> Unit = {},
    onTimelineFilterClick: () -> Unit = {},
    onLandingTabClick: () -> Unit = {},
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_theme_palette),
                onClick = onThemeClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.language_section),
                onClick = onLanguageClick,
            )
            RowDivider()
            // Layout + display — grid columns, mosaic, and the button-label toggle. These are
            // app-wide display choices (they also affect album/folder selection), so they sit at
            // the appearance level rather than nested inside the timeline page.
            NavRow(
                label = stringResource(R.string.settings_timeline_section_layout),
                onClick = onLayoutClick,
            )
            RowDivider()
            // Photos timeline page — what shows on the Photos tab and its display filters.
            NavRow(
                label = stringResource(R.string.settings_timeline),
                onClick = onTimelineFilterClick,
            )
            RowDivider()
            // Landing tab — which top-level tab the gallery opens on at start. A startup
            // display choice, so it sits with the other appearance rows.
            NavRow(
                label = stringResource(R.string.settings_landing_tab),
                onClick = onLandingTabClick,
            )
        }
    }
}

/** Theme mode (System / Light / Dark) plus the colour palette. */
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(title = stringResource(R.string.settings_theme_palette), onBack = onBack) {
        // ── Theme mode row of pills ─────────────────────────────────────────
        CollapsibleSection(label = stringResource(R.string.theme_mode_section)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    ThemeModePill(
                        label = stringResource(mode.labelRes),
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Palette dots ────────────────────────────────────────────────────
        CollapsibleSection(label = stringResource(R.string.settings_palette_section)) {
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
                    val selected = state.palette == p
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(swatch, CircleShape)
                            .border(
                                width = if (selected) 2.dp else 0.5.dp,
                                color = if (selected) colors.fgPrimary else colors.cardBorder,
                                shape = CircleShape,
                            )
                            .clickable { viewModel.setThemePalette(p) },
                    )
                }
            }
        }

        // ── AMOLED pure-black toggle — only takes effect in dark mode, so it shows only when the
        // effective theme is dark: an explicit Dark choice, or System while the system is dark. ──
        val darkEffective = state.themeMode == ThemeMode.Dark ||
            (state.themeMode == ThemeMode.System && isSystemInDarkTheme())
        if (darkEffective) {
            Spacer(Modifier.height(20.dp))
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.settings_amoled_black),
                    description = stringResource(R.string.settings_amoled_black_summary),
                    checked = state.amoledBlack,
                    onCheckedChange = { viewModel.setAmoledBlack(it) },
                )
            }
        }
    }
}

/** Display language picker. */
@Composable
fun LanguageSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(title = stringResource(R.string.language_section), onBack = onBack) {
        val options = remember_languageOptions()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
        ) {
            options.forEachIndexed { index, option ->
                val selected = state.language == option.tag
                ChoiceRow(
                    label = stringResource(option.labelRes),
                    description = null,
                    selected = selected,
                    onClick = { viewModel.setLanguage(option.tag) },
                )
                if (index < options.lastIndex) RowDivider()
            }
        }
    }
}

private data class LanguageOption(val tag: String, val labelRes: Int)

@Composable
private fun remember_languageOptions(): List<LanguageOption> = listOf(
    LanguageOption("system", R.string.settings_language_system),
    LanguageOption("en",     R.string.language_en),
    LanguageOption("de",     R.string.language_de),
    LanguageOption("es",     R.string.language_es),
    LanguageOption("fr",     R.string.language_fr),
    LanguageOption("hu",     R.string.language_hu),
    LanguageOption("it",     R.string.language_it),
    LanguageOption("nl",     R.string.language_nl),
    LanguageOption("pl",     R.string.language_pl),
    LanguageOption("sk",     R.string.language_sk),
    LanguageOption("sl",     R.string.language_sl),
    LanguageOption("cs",     R.string.language_cs),
)

@Composable
private fun ThemeModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Box(
        modifier = modifier
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(description, color = colors.fgMute, fontSize = 12.sp)
            }
        }
        if (selected) {
            Box(
                modifier = Modifier.size(20.dp).background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
