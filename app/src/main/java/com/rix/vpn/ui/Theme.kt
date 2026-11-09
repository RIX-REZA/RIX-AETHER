package com.rix.vpn.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.rix.vpn.data.ThemeMode

/**
 * Professional minimal palette — NO PURPLE/VIOLET.
 * Dark gray / charcoal / white with a single teal accent.
 * Meets v1.0.8 design requirements: minimal, modern, no decorative purple.
 */
data class RixPalette(
    val isDark: Boolean,
    val background: Color,
    val backgroundLift: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val stroke: Color,
    /** Primary accent — teal only, used for active/connected states. */
    val accent: Color,
    val accentDeep: Color,
    /** Text/icon colour drawn on top of [accent]. */
    val onAccent: Color,
    /** Secondary accent — amber for warnings/pending. */
    val warning: Color,
    val warningDeep: Color,
    /** Error/danger red. */
    val danger: Color,
    val dangerDeep: Color,
    val text: Color,
    val textMuted: Color,
    /** Opacity of the decorative background wells; lower in light mode. */
    val wellAccent: Float
)

val RixDarkPalette = RixPalette(
    isDark = true,
    background = Color(0xFF0A0A0F),        // Near-black charcoal
    backgroundLift = Color(0xFF11111A),    // Slightly lifted
    surface = Color(0xFF16161F),           // Card surface
    surfaceHigh = Color(0xFF1E1E2A),       // Elevated surface
    stroke = Color(0xFF2A2A3A),            // Borders/dividers
    accent = Color(0xFF00D4AA),            // Teal accent (connected/active)
    accentDeep = Color(0xFF00A888),        // Deeper teal
    onAccent = Color(0xFF051A15),          // Text on teal
    warning = Color(0xFFFFB800),           // Amber warning
    warningDeep = Color(0xFFCC9300),
    danger = Color(0xFFFF4D6A),            // Error red
    dangerDeep = Color(0xFFCC3D55),
    text = Color(0xFFE8E8ED),              // Primary text
    textMuted = Color(0xFF8A8A9A),         // Muted text
    wellAccent = 0.08f
)

val RixLightPalette = RixPalette(
    isDark = false,
    background = Color(0xFFFAFAFC),        // Off-white
    backgroundLift = Color(0xFFFFFFFF),    // Pure white
    surface = Color(0xFFFFFFFF),           // White cards
    surfaceHigh = Color(0xFFF5F5F8),       // Slightly gray elevated
    stroke = Color(0xFFE0E0E8),            // Light borders
    accent = Color(0xFF00A888),            // Teal accent (darker for light mode)
    accentDeep = Color(0xFF008A72),
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFCC9300),           // Amber warning
    warningDeep = Color(0xFFA67700),
    danger = Color(0xFFD62B4B),            // Error red
    dangerDeep = Color(0xFFB0233D),
    text = Color(0xFF1A1A2E),              // Dark text
    textMuted = Color(0xFF6E6E82),         // Muted text
    wellAccent = 0.05f
)

val LocalRixPalette = staticCompositionLocalOf { RixDarkPalette }

/**
 * Colour accessor — single accent (teal), no violet/purple anywhere.
 */
object RixColors {
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalRixPalette.current.isDark
    val Background: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.background
    val BackgroundLift: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.backgroundLift
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.surfaceHigh
    val Stroke: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.stroke
    /** @deprecated Use [Accent] — kept for binary compatibility during migration. */
    @Deprecated("Use Accent", ReplaceWith("Accent"))
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accent
    /** @deprecated Use [AccentDeep] — kept for binary compatibility during migration. */
    @Deprecated("Use AccentDeep", ReplaceWith("AccentDeep"))
    val VioletDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accentDeep
    /** Primary accent — teal. */
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accent
    /** Deeper accent for pressed/hover states. */
    val AccentDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accentDeep
    /** @deprecated Use [Accent] — kept for binary compatibility. */
    @Deprecated("Use Accent", ReplaceWith("Accent"))
    val Teal: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accent
    /** @deprecated Use [AccentDeep] — kept for binary compatibility. */
    @Deprecated("Use AccentDeep", ReplaceWith("AccentDeep"))
    val TealDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.accentDeep
    val Text: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.text
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.textMuted
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.danger
    val DangerDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.dangerDeep
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.warning
    val WarningDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.warningDeep
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.onAccent
}

private fun schemeFor(p: RixPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = p.text,
        secondary = p.warning,
        onSecondary = Color.Black,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textMuted,
        outline = p.stroke,
        error = p.danger,
        onError = Color.White
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = Color.White,
        secondary = p.warning,
        onSecondary = Color.Black,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textMuted,
        outline = p.stroke,
        error = p.danger,
        onError = Color.White
    )
}

private val RixTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/**
 * Applies the skin, the language and the direction they imply.
 */
@Composable
fun RixTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    en: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) RixDarkPalette else RixLightPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val strings = remember(en) { Strings(en) }

    CompositionLocalProvider(
        LocalRixPalette provides palette,
        LocalStrings provides strings,
        LocalLayoutDirection provides if (en) LayoutDirection.Ltr else LayoutDirection.Rtl
    ) {
        MaterialTheme(
            colorScheme = schemeFor(palette),
            typography = RixTypography,
            content = content
        )
    }
}