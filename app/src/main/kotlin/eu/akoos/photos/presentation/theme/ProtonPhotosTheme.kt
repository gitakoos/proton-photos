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

package eu.akoos.photos.presentation.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.ThemePalette

// No-op indication — suppresses all press ripple/animation
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = System.identityHashCode(this)
}

// ── Static palette (raw values; not used directly outside this file). ──────────
// Public consumers should reference the dynamic top-level properties below
// (`Bg0`, `FgPrimary`, etc.) which resolve via LocalAppColors at compose time.
private val Bg0Dark = Color(0xFF090909)
private val Bg1Dark = Color(0xFF0E0E0E)
private val Bg2Dark = Color(0xFF1A1A1A)
private val FgPrimaryDark = Color(0xFFECEBF3)
private val FgDimDark = Color(0xFFA4A1B4)
private val FgMuteDark = Color(0xFF6B6880)
private val AccentDark = Color(0xFF8B7CFF)
private val Accent2Dark = Color(0xFF6957D7)
// ── Palette accents (dark mode) — only `accent`/`accent2` shift between palettes. ──
private val AccentDarkForest  = Color(0xFF7BC47F); private val Accent2DarkForest  = Color(0xFF3F8C44)
private val AccentDarkSunset  = Color(0xFFFF8A65); private val Accent2DarkSunset  = Color(0xFFE64A19)
private val AccentDarkSea     = Color(0xFF4FC3F7); private val Accent2DarkSea     = Color(0xFF0288D1)
private val AccentDarkSepia   = Color(0xFFD4A574); private val Accent2DarkSepia   = Color(0xFF8B6F47)
private val AccentDarkMono    = Color(0xFFE0E0E0); private val Accent2DarkMono    = Color(0xFF9E9E9E)
private val LineDark = Color(0x14FFFFFF)
private val Line2Dark = Color(0x1EFFFFFF)
private val PillBgDark = Color(0xBE1C1C1E)
private val PillBgOpaqueDark = Color(0xEA1C1C1E)
private val PillBorderDark = Color(0xFF2C2C2E)
private val ErrorColorDark = Color(0xFFFF8C7A)
private val ActiveChipTextDark = Color(0xFF0F0D18)

private val Bg0Light = Color(0xFFF7F7F8)
private val Bg1Light = Color(0xFFFFFFFF)
private val Bg2Light = Color(0xFFEAEAEC)
private val FgPrimaryLight = Color(0xFF131318)
private val FgDimLight = Color(0xFF5C5A6B)
private val FgMuteLight = Color(0xFF8E8B9C)
private val AccentLight = Color(0xFF6957D7)
private val Accent2Light = Color(0xFF4A37BC)
// ── Palette accents (light mode). ──
private val AccentLightForest = Color(0xFF388E3C); private val Accent2LightForest = Color(0xFF1B5E20)
private val AccentLightSunset = Color(0xFFE64A19); private val Accent2LightSunset = Color(0xFFBF360C)
private val AccentLightSea    = Color(0xFF0288D1); private val Accent2LightSea    = Color(0xFF01579B)
private val AccentLightSepia  = Color(0xFF8B6F47); private val Accent2LightSepia  = Color(0xFF5D4037)
private val AccentLightMono   = Color(0xFF424242); private val Accent2LightMono   = Color(0xFF212121)
private val LineLight = Color(0x14000000)
private val Line2Light = Color(0x1F000000)
// Light-theme pills sit over photos (info pill, motion / panorama controls in the viewer), where
// a 75%-opaque near-white pill bled into white or busy images. Bumped to ~95% opacity so the pill
// reads as a solid surface, and a clearly darker border so its outline stays visible over white.
private val PillBgLight = Color(0xF2F2F2F4)
private val PillBgOpaqueLight = Color(0xF5EAEAEC)
private val PillBorderLight = Color(0xFFB4B4BC)
private val ErrorColorLight = Color(0xFFD8351E)
private val ActiveChipTextLight = Color(0xFFFFFFFF)

// ── Status colors — NEVER theme-aware. Same in light and dark. ───────────────────
val StatusSynced  = Color(0xFF30D158)  // green — uploaded, backed up
val StatusPending = Color(0xFFFF9F0A)  // amber — waiting
val StatusError   = Color(0xFFFF453A)  // red — failure

// ── Semantic color tokens — switch based on theme mode ───────────────────────────
data class AppColorsTokens(
    val isLight: Boolean,
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val pageBg: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val surfaceWeak: Color,
    val fgPrimary: Color,
    val fgDim: Color,
    val fgMute: Color,
    val accent: Color,
    val accent2: Color,
    val line: Color,
    val line2: Color,
    val pillBg: Color,
    val pillBgOpaque: Color,
    val pillBorder: Color,
    val errorColor: Color,
    val chipUnselectedBg: Color,
    val chipSelectedBg: Color,
    val filterPillBg: Color,
    val activeChipText: Color,
    // Editor / panel surfaces — deeper than cardBg, used in PhotoEditorScreen.
    val panelBg: Color,
    val panelChip: Color,
    val trackBg: Color,
    // Decorative dark overlays — translucent tints on cards, dim-on-white in light mode.
    val deleteTint: Color,        // soft red overlay for destructive option cards
    val errorChipBg: Color,       // chip background for errors and warnings
    val arcTrack: Color,          // background ring behind the storage progress arc
)

// ── Palette → accent resolution ───────────────────────────────────────────────────
// Only `accent` and `accent2` shift per palette; every other token stays palette-
// agnostic. Default returns the historical Proton purple so existing installs see
// zero visual change after this code lands.
private fun darkAccentFor(palette: ThemePalette): Pair<Color, Color> = when (palette) {
    ThemePalette.Default -> AccentDark         to Accent2Dark
    ThemePalette.Forest  -> AccentDarkForest   to Accent2DarkForest
    ThemePalette.Sunset  -> AccentDarkSunset   to Accent2DarkSunset
    ThemePalette.Sea     -> AccentDarkSea      to Accent2DarkSea
    ThemePalette.Sepia   -> AccentDarkSepia   to Accent2DarkSepia
    ThemePalette.Mono    -> AccentDarkMono     to Accent2DarkMono
}

private fun lightAccentFor(palette: ThemePalette): Pair<Color, Color> = when (palette) {
    ThemePalette.Default -> AccentLight        to Accent2Light
    ThemePalette.Forest  -> AccentLightForest  to Accent2LightForest
    ThemePalette.Sunset  -> AccentLightSunset  to Accent2LightSunset
    ThemePalette.Sea     -> AccentLightSea     to Accent2LightSea
    ThemePalette.Sepia   -> AccentLightSepia   to Accent2LightSepia
    ThemePalette.Mono    -> AccentLightMono    to Accent2LightMono
}

// AMOLED override — base surfaces drop to true black while cards/panels stay one step up so
// rows, dividers, and the editor panels keep their separation against the black page.
private val Bg0Amoled = Color(0xFF000000)
private val Bg1Amoled = Color(0xFF000000)
private val Bg2Amoled = Color(0xFF101010)
private val PageBgAmoled = Color(0xFF000000)
private val CardBgAmoled = Color(0xFF0C0C0D)

private fun darkAppColors(palette: ThemePalette, amoled: Boolean = false): AppColorsTokens {
    val (accent, accent2) = darkAccentFor(palette)
    return AppColorsTokens(
        isLight        = false,
        bg0            = if (amoled) Bg0Amoled else Bg0Dark,
        bg1            = if (amoled) Bg1Amoled else Bg1Dark,
        bg2            = if (amoled) Bg2Amoled else Bg2Dark,
        pageBg         = if (amoled) PageBgAmoled else Color(0xFF0E0E0F),
        cardBg         = if (amoled) CardBgAmoled else Color(0xFF1C1C1E),
        cardBorder     = Color(0xFF2C2C2E),
        surfaceWeak    = Color(0x14FFFFFF),
        fgPrimary      = FgPrimaryDark,
        fgDim          = FgDimDark,
        fgMute         = FgMuteDark,
        accent         = accent,
        accent2        = accent2,
        line           = LineDark,
        line2          = Line2Dark,
        pillBg         = PillBgDark,
        pillBgOpaque   = PillBgOpaqueDark,
        pillBorder     = PillBorderDark,
        errorColor     = ErrorColorDark,
        chipUnselectedBg = Color(0xFF1C1C1E),
        chipSelectedBg   = Color(0xFF3A3A3C),
        filterPillBg     = Color(0xFF3A3A3C),
        activeChipText   = ActiveChipTextDark,
        panelBg          = Color(0xFF14111B),
        panelChip        = Color(0xFF1F1B29),
        trackBg          = Color(0xFF2E2A38),
        deleteTint       = Color(0x18FF453A),
        errorChipBg      = Color(0x33FF8C7A),
        arcTrack         = Color(0xFF2C2C2E),
    )
}

private fun lightAppColors(palette: ThemePalette): AppColorsTokens {
    val (accent, accent2) = lightAccentFor(palette)
    return AppColorsTokens(
        isLight        = true,
        bg0            = Bg0Light,
        bg1            = Bg1Light,
        bg2            = Bg2Light,
        pageBg         = Color(0xFFF2F2F5),
        cardBg         = Color(0xFFFFFFFF),
        cardBorder     = Color(0xFFD8D8DC),
        surfaceWeak    = Color(0x14000000),
        fgPrimary      = FgPrimaryLight,
        fgDim          = FgDimLight,
        fgMute         = FgMuteLight,
        accent         = accent,
        accent2        = accent2,
        line           = LineLight,
        line2          = Line2Light,
        pillBg         = PillBgLight,
        pillBgOpaque   = PillBgOpaqueLight,
        pillBorder     = PillBorderLight,
        errorColor     = ErrorColorLight,
        chipUnselectedBg = Color(0xFFFFFFFF),
        chipSelectedBg   = Color(0xFFE2E2E5),
        filterPillBg     = Color(0xFFE2E2E5),
        activeChipText   = ActiveChipTextLight,
        panelBg          = Color(0xFFEEEFF2),
        panelChip        = Color(0xFFFFFFFF),
        trackBg          = Color(0xFFDCDCE0),
        deleteTint       = Color(0x1FD8351E),
        errorChipBg      = Color(0x33D8351E),
        arcTrack         = Color(0xFFD8D8DC),
    )
}

private fun darkColorSchemeFor(palette: ThemePalette, amoled: Boolean = false) = darkColorScheme(
    primary = darkAccentFor(palette).first,
    onPrimary = Color.White,
    // Chips / switches sit on a tinted accent container — derive it from the active
    // palette so Material3 components (FilterChip, Switch thumb, etc.) match.
    primaryContainer = darkAccentFor(palette).first.copy(alpha = 0.2f),
    onPrimaryContainer = FgPrimaryDark,
    secondary = darkAccentFor(palette).second,
    onSecondary = Color.White,
    background = if (amoled) Bg0Amoled else Bg0Dark,
    onBackground = FgPrimaryDark,
    surface = if (amoled) Bg1Amoled else Bg1Dark,
    onSurface = FgPrimaryDark,
    surfaceVariant = if (amoled) Bg2Amoled else Bg2Dark,
    onSurfaceVariant = FgDimDark,
    surfaceContainer = PillBgDark,
    outline = Line2Dark,
    error = ErrorColorDark,
    onError = Color.White,
    surfaceTint = Color.Transparent,
)

private fun lightColorSchemeFor(palette: ThemePalette) = lightColorScheme(
    primary = lightAccentFor(palette).first,
    onPrimary = Color.White,
    primaryContainer = lightAccentFor(palette).first.copy(alpha = 0.2f),
    onPrimaryContainer = FgPrimaryLight,
    secondary = lightAccentFor(palette).second,
    onSecondary = Color.White,
    background = Bg0Light,
    onBackground = FgPrimaryLight,
    surface = Bg1Light,
    onSurface = FgPrimaryLight,
    surfaceVariant = Bg2Light,
    onSurfaceVariant = FgDimLight,
    surfaceContainer = PillBgLight,
    outline = Line2Light,
    error = ErrorColorLight,
    onError = Color.White,
    surfaceTint = Color.Transparent,
)

/**
 * Resolve the accent color for an arbitrary palette in the given light/dark mode.
 * Used by the Settings palette picker to render swatch dots for every option
 * regardless of which palette is currently active.
 */
fun paletteAccent(palette: ThemePalette, isLight: Boolean): Color =
    if (isLight) lightAccentFor(palette).first else darkAccentFor(palette).first

/** Composition-local with the active app color tokens. */
val LocalAppColors = staticCompositionLocalOf { darkAppColors(ThemePalette.Default) }

/** Accessor — call AppColors.current to read tokens in a Composable. */
object AppColors {
    val current: AppColorsTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// ── Theme-aware top-level color tokens ───────────────────────────────────────────
//
// These mirror the original `val Bg0 = Color(...)` constants but resolve via the
// active LocalAppColors provider. Every existing call site (~85 in the codebase)
// automatically becomes theme-aware without per-file refactoring.
//
// Status colors (StatusSynced / Pending / Error) intentionally stay as plain
// constants — backed-up green and error red must read the same in any theme.

val Bg0: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.bg0

val Bg1: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.bg1

val Bg2: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.bg2

val FgPrimary: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.fgPrimary

val FgDim: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.fgDim

val FgMute: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.fgMute

val Accent: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.accent

val Accent2: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.accent2

val Line: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.line

val Line2: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.line2

val PillBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.pillBg

val PillBgOpaque: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.pillBgOpaque

val PillBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.pillBorder

val ErrorColor: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.errorColor

val ActiveChipText: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.activeChipText

val CardBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.cardBg

val CardBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.cardBorder

val PageBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.pageBg

val SurfaceWeak: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.surfaceWeak

val PanelBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.panelBg

val PanelChip: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.panelChip

val TrackBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.trackBg

val DeleteTint: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.deleteTint

val ErrorChipBg: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.errorChipBg

val ArcTrack: Color
    @Composable @ReadOnlyComposable
    get() = LocalAppColors.current.arcTrack

// ── App font — Inter (variable .ttf; the wght axis resolves weights on API 26+). ──────
// Applied app-wide via the Material typography + LocalTextStyle so every Text() renders in
// Inter while keeping its own size and weight. Single family, no user choice.
@OptIn(ExperimentalTextApi::class)
private val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal,   variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium,   variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold,     variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val InterTypography: Typography = Typography().let { b ->
    b.copy(
        displayLarge   = b.displayLarge.copy(fontFamily = InterFamily),
        displayMedium  = b.displayMedium.copy(fontFamily = InterFamily),
        displaySmall   = b.displaySmall.copy(fontFamily = InterFamily),
        headlineLarge  = b.headlineLarge.copy(fontFamily = InterFamily),
        headlineMedium = b.headlineMedium.copy(fontFamily = InterFamily),
        headlineSmall  = b.headlineSmall.copy(fontFamily = InterFamily),
        titleLarge     = b.titleLarge.copy(fontFamily = InterFamily),
        titleMedium    = b.titleMedium.copy(fontFamily = InterFamily),
        titleSmall     = b.titleSmall.copy(fontFamily = InterFamily),
        bodyLarge      = b.bodyLarge.copy(fontFamily = InterFamily),
        bodyMedium     = b.bodyMedium.copy(fontFamily = InterFamily),
        bodySmall      = b.bodySmall.copy(fontFamily = InterFamily),
        labelLarge     = b.labelLarge.copy(fontFamily = InterFamily),
        labelMedium    = b.labelMedium.copy(fontFamily = InterFamily),
        labelSmall     = b.labelSmall.copy(fontFamily = InterFamily),
    )
}

@Composable
fun ProtonPhotosTheme(
    darkTheme: Boolean = true,
    palette: ThemePalette = ThemePalette.Default,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val amoled  = darkTheme && amoledBlack
    val colors  = if (darkTheme) darkAppColors(palette, amoled) else lightAppColors(palette)
    val scheme  = if (darkTheme) darkColorSchemeFor(palette, amoled) else lightColorSchemeFor(palette)
    MaterialTheme(colorScheme = scheme, typography = InterTypography) {
        CompositionLocalProvider(
            LocalIndication provides NoIndication,
            LocalAppColors  provides colors,
            LocalTextStyle  provides LocalTextStyle.current.merge(TextStyle(fontFamily = InterFamily)),
        ) {
            content()
        }
    }
}
