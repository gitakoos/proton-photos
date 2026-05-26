package me.proton.photos.presentation.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode

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
private val LineLight = Color(0x14000000)
private val Line2Light = Color(0x1F000000)
private val PillBgLight = Color(0xBEF2F2F4)
private val PillBgOpaqueLight = Color(0xEAEAEAEC)
private val PillBorderLight = Color(0xFFD0D0D5)
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

private val DarkAppColors = AppColorsTokens(
    isLight        = false,
    bg0            = Bg0Dark,
    bg1            = Bg1Dark,
    bg2            = Bg2Dark,
    pageBg         = Color(0xFF0E0E0F),
    cardBg         = Color(0xFF1C1C1E),
    cardBorder     = Color(0xFF2C2C2E),
    surfaceWeak    = Color(0x14FFFFFF),
    fgPrimary      = FgPrimaryDark,
    fgDim          = FgDimDark,
    fgMute         = FgMuteDark,
    accent         = AccentDark,
    accent2        = Accent2Dark,
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

private val LightAppColors = AppColorsTokens(
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
    accent         = AccentLight,
    accent2        = Accent2Light,
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

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2F8A),
    onPrimaryContainer = FgPrimaryDark,
    secondary = Accent2Dark,
    onSecondary = Color.White,
    background = Bg0Dark,
    onBackground = FgPrimaryDark,
    surface = Bg1Dark,
    onSurface = FgPrimaryDark,
    surfaceVariant = Bg2Dark,
    onSurfaceVariant = FgDimDark,
    surfaceContainer = PillBgDark,
    outline = Line2Dark,
    error = ErrorColorDark,
    onError = Color.White,
    surfaceTint = Color.Transparent,
)

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DDFF),
    onPrimaryContainer = FgPrimaryLight,
    secondary = Accent2Light,
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

/** Composition-local with the active app color tokens. */
val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

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

// ── Non-composable constants kept for places that need a stable Color outside
// compose (drawing canvases, RemoteViews, widgets, etc.). These resolve to the
// dark palette and are intentionally NOT theme-aware. Use AppColors.current
// instead inside composables.
val Bg0Static = Bg0Dark
val Bg1Static = Bg1Dark
val Bg2Static = Bg2Dark
val FgPrimaryStatic = FgPrimaryDark
val AccentStatic = AccentDark

@Composable
fun ProtonPhotosTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors  = if (darkTheme) DarkAppColors else LightAppColors
    val scheme  = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalIndication provides NoIndication,
            LocalAppColors  provides colors,
        ) {
            content()
        }
    }
}
