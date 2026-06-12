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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import eu.akoos.photos.presentation.settings.components.rememberDebouncedAction
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.paletteAccent

/**
 * Unified Appearance + Language screen. Theme picker = 3 horizontally-arrayed pills
 * (System / Light / Dark). Palette = a single pill row of colored swatches the user
 * taps directly — no per-row labels. Language = the original list, no restart-hint
 * footer (UI updates immediately so the warning was confusing).
 *
 * [LanguageSettingsScreen] is retained as a thin wrapper that just forwards to this
 * screen — kept so any legacy nav destination still resolves. The Settings list now
 * routes both menu entries here.
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onTimelineFilterClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
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
        // Single pill, dots horizontally so every palette is one tap away. No
        // labels because the swatch itself is the identity. Selected dot gets a
        // 2dp accent ring so the active palette stays unambiguous against any
        // background. Outer padding matches inter-swatch spacing for even visual
        // weight from edge to edge.
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

        Spacer(Modifier.height(20.dp))

        // ── Language list ───────────────────────────────────────────────────
        // Same row format as before, kept as a collapsible section so the page
        // stays scannable when only one option matters to the user.
        CollapsibleSection(label = stringResource(R.string.language_section)) {
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

        Spacer(Modifier.height(20.dp))

        // ── Photos timeline behaviour ────────────────────────────────────────
        // Display-level toggles that change WHAT shows up on the Photos tab,
        // separate from the look (theme / palette) and the language. Lives here
        // rather than under Privacy because it's a visualisation choice, not a
        // security one.
        CollapsibleSection(label = stringResource(R.string.settings_photos_timeline_section)) {
            SettingsCard {
                // Single entry point — the timeline filter screen holds both the
                // "hide album photos" toggle and the per-folder device exclusions, so
                // everything that controls what shows on the Photos tab lives in one place.
                NavRow(
                    label = stringResource(R.string.settings_timeline_filter),
                    onClick = onTimelineFilterClick,
                )
            }
        }
    }
}

/**
 * Legacy entry point — kept so navigation routes that still point at
 * `language_settings` resolve. Renders the same unified screen.
 */
@Composable
fun LanguageSettingsScreen(
    onBack: () -> Unit,
    onTimelineFilterClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) = AppearanceSettingsScreen(
    onBack = onBack,
    onTimelineFilterClick = onTimelineFilterClick,
    viewModel = viewModel,
)

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

