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

package eu.akoos.photos.presentation.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.paletteAccent
import eu.akoos.photos.presentation.util.AppLanguages
import eu.akoos.photos.presentation.util.ClockFormat
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Appearance hub — menu rows to the theme/palette and language sub-pages, plus the photo
 * timeline page and the Albums tab's page. Kept thin so each concern lives on its own focused
 * page instead of one long mixed scroll.
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onThemeClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onTimeFormatClick: () -> Unit = {},
    onTimelineFilterClick: () -> Unit = {},
    onAlbumsSettingsClick: () -> Unit = {},
    onLandingTabClick: () -> Unit = {},
    onViewerClick: () -> Unit = {},
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SectionLabel(stringResource(R.string.settings_section_look))
        Spacer(Modifier.height(8.dp))
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
            NavRow(
                label = stringResource(R.string.settings_time_format),
                onClick = onTimeFormatClick,
            )
        }

        Spacer(Modifier.height(20.dp))

        // The photo library's own settings: the timeline's display (layout, category bar, albums,
        // device folders), the Albums tab's page and the tab the app opens on.
        SectionLabel(stringResource(R.string.settings_section_library))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_timeline),
                onClick = onTimelineFilterClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.gallery_tab_albums),
                onClick = onAlbumsSettingsClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_viewer),
                onClick = onViewerClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_landing_tab),
                onClick = onLandingTabClick,
            )
        }
    }}

/** How the photo viewer reacts to touch. Each choice of the viewer gets its own section here. */
@Composable
fun ViewerSettingsScreen(
    onBack: () -> Unit,
    viewModel: ViewerLongPressViewModel = hiltViewModel(),
) {
    val opensMenu by viewModel.opensMenu.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_viewer), onBack = onBack) {
        SectionLabel(stringResource(R.string.settings_viewer_long_press))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            ChoiceRow(
                label = stringResource(R.string.viewer_select_text),
                description = null,
                selected = !opensMenu,
                onClick = { viewModel.set(opensMenu = false) },
            )
            RowDivider()
            ChoiceRow(
                label = stringResource(R.string.settings_viewer_long_press_menu),
                description = stringResource(R.string.settings_viewer_long_press_menu_desc),
                selected = opensMenu,
                onClick = { viewModel.set(opensMenu = true) },
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
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(24.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(14.dp),
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

        // Tint the green "backed up" cloud badges with the chosen palette accent instead of the fixed
        // green. Independent of light/dark, so it is always shown.
        Spacer(Modifier.height(20.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_tint_cloud),
                checked = state.tintCloudWithAccent,
                onCheckedChange = { viewModel.setTintCloudWithAccent(it) },
            )
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
        val options = AppLanguages.all
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

/** The clock choice's saved state and its one write. */
@HiltViewModel
class ClockFormatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val choice: StateFlow<ClockFormat> = context.settingsDataStore.data
        .map { ClockFormat.fromKey(it[SettingsKeys.CLOCK_FORMAT]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ClockFormat.AUTO)

    fun set(format: ClockFormat) {
        viewModelScope.launch { context.settingsDataStore.edit { it[SettingsKeys.CLOCK_FORMAT] = format.key } }
    }
}

/** What a long press on the photo in the viewer does: select its text, or open the menu. */
@HiltViewModel
class ViewerLongPressViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val opensMenu: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.VIEWER_LONG_PRESS_OPENS_MENU] == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun set(opensMenu: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.VIEWER_LONG_PRESS_OPENS_MENU] = opensMenu }
        }
    }
}

/** How the time of day is written, chosen apart from the display language. */
@Composable
fun ClockFormatScreen(
    onBack: () -> Unit,
    viewModel: ClockFormatViewModel = hiltViewModel(),
) {
    val choice by viewModel.choice.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val options = listOf(
        Triple(ClockFormat.AUTO, R.string.settings_time_format_auto, stringResource(R.string.settings_time_format_auto_desc)),
        Triple(ClockFormat.H24, R.string.settings_time_format_24h, "13:45"),
        Triple(ClockFormat.H12, R.string.settings_time_format_12h, "1:45 PM"),
    )
    SettingsSubPageScaffold(title = stringResource(R.string.settings_time_format), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
        ) {
            options.forEachIndexed { index, (format, labelRes, description) ->
                ChoiceRow(
                    label = stringResource(labelRes),
                    description = description,
                    selected = choice == format,
                    onClick = { viewModel.set(format) },
                )
                if (index < options.lastIndex) RowDivider()
            }
        }
    }
}

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
