package me.proton.photos.presentation.settings

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
import me.proton.photos.R
import me.proton.photos.presentation.theme.AppColors

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SectionLabel(stringResource(R.string.theme_mode_section))
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
        ) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                val selected = state.themeMode == mode
                val description = when (mode) {
                    ThemeMode.System -> stringResource(R.string.theme_mode_desc_system)
                    ThemeMode.Light  -> stringResource(R.string.theme_mode_desc_light)
                    ThemeMode.Dark   -> stringResource(R.string.theme_mode_desc_dark)
                }
                ChoiceRow(
                    label = stringResource(mode.labelRes),
                    description = description,
                    selected = selected,
                    onClick = { viewModel.setThemeMode(mode) },
                )
                if (index < ThemeMode.entries.lastIndex) RowDivider()
            }
        }
    }
}

private data class LanguageOption(val tag: String, val labelRes: Int)

@Composable
fun LanguageSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val options = listOf(
        LanguageOption("system", R.string.settings_language_system),
        LanguageOption("en",     R.string.language_en),
        LanguageOption("de",     R.string.language_de),
        LanguageOption("es",     R.string.language_es),
        LanguageOption("fr",     R.string.language_fr),
        LanguageOption("hu",     R.string.language_hu),
        LanguageOption("it",     R.string.language_it),
    )

    SettingsSubPageScaffold(title = stringResource(R.string.settings_language), onBack = onBack) {
        SectionLabel(stringResource(R.string.language_section))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.language_section_desc),
            color = colors.fgMute,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
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

@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

@Composable
internal fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(colors.surfaceWeak, CircleShape)
                        .border(0.5.dp, colors.pillBorder, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        null,
                        tint = colors.fgDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(title, color = colors.fgPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}
