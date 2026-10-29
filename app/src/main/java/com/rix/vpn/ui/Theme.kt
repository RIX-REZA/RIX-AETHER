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
 * One palette, two skins.
 *
 * Every screen reads colours through [RixColors], whose properties are
 * composable getters over [LocalRixPalette]; that is why adding the light skin
 * did not require touching the ~90 call sites that already said
 * `RixColors.Surface`.
 */
data class RixPalette(
    val isDark: Boolean,
    val background: Color,
    val backgroundLift: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val stroke: Color,
    val violet: Color,
    val violetDeep: Color,
    val teal: Color,
    val tealDeep: Color,
    val text: Color,
    val textMuted: Color,
    val danger: Color,
    val warning: Color,
    /** Text/icon colour drawn on top of [violet] or [teal]. */
    val onAccent: Color,
    /** Opacity of the decorative background wells; lower in light mode. */
    val wellViolet: Float,
    val wellTeal: Float
)

val RixDarkPalette = RixPalette(
    isDark = true,
    background = Color(0xFF07070D),
    backgroundLift = Color(0xFF0B0B16),
    surface = Color(0xFF10101F),
    surfaceHigh = Color(0xFF17172B),
    stroke = Color(0xFF23233C),
    violet = Color(0xFF8F7BFF),
    violetDeep = Color(0xFF5B45D6),
    teal = Color(0xFF2BD9C4),
    tealDeep = Color(0xFF12A08F),
    text = Color(0xFFEFEDFF),
    textMuted = Color(0xFF8E8BAD),
    danger = Color(0xFFFF5C7A),
    warning = Color(0xFFFFC46B),
    onAccent = Color(0xFF0B0616),
    wellViolet = 0.16f,
    wellTeal = 0.10f
)

val RixLightPalette = RixPalette(
    isDark = false,
    background = Color(0xFFF3F3F9),
    backgroundLift = Color(0xFFFFFFFF),
    surface = Color(0xFFFCFCFE),
    surfaceHigh = Color(0xFFFFFFFF),
    stroke = Color(0xFFE1E1EE),
    violet = Color(0xFF6847F0),
    violetDeep = Color(0xFF4A2FC4),
    teal = Color(0xFF0C9C8C),
    tealDeep = Color(0xFF0A8377),
    text = Color(0xFF14142B),
    textMuted = Color(0xFF6C6C88),
    danger = Color(0xFFD62B4B),
    warning = Color(0xFFA96B05),
    onAccent = Color(0xFFFFFFFF),
    wellViolet = 0.09f,
    wellTeal = 0.07f
)

val LocalRixPalette = staticCompositionLocalOf { RixDarkPalette }

/**
 * Colour accessor kept as an object so existing call sites stay unchanged.
 *
 * The getters are `@ReadOnlyComposable`, which is legal because each one only
 * reads a composition local; Compose therefore records the dependency and every
 * screen recomposes when the skin changes.
 */
object RixColors {
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalRixPalette.current.isDark
    val Background: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.background
    val BackgroundLift: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.backgroundLift
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.surfaceHigh
    val Stroke: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.stroke
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.violet
    val VioletDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.violetDeep
    val Teal: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.teal
    val TealDeep: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.tealDeep
    val Text: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.text
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.textMuted
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.danger
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.warning
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalRixPalette.current.onAccent
}

private fun schemeFor(p: RixPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.violet,
        onPrimary = p.onAccent,
        primaryContainer = p.violetDeep,
        onPrimaryContainer = p.text,
        secondary = p.teal,
        onSecondary = p.onAccent,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textMuted,
        outline = p.stroke,
        error = p.danger,
        onError = Color(0xFF2A0010)
    )
} else {
    lightColorScheme(
        primary = p.violet,
        onPrimary = p.onAccent,
        primaryContainer = p.violetDeep,
        onPrimaryContainer = Color.White,
        secondary = p.teal,
        onSecondary = p.onAccent,
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
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/**
 * Forces RTL because every label in this app is Persian.
 *
 * The OS layout direction follows the device locale, so a phone set to English
 * would lay Persian text out left-to-right and every row would look wrong. Text
 * that must stay left-to-right (addresses, ports, logs) opts out locally with
 * `textDirection = TextDirection.Ltr`.
 *
 * Status- and navigation-bar icon colours are flipped with the skin, otherwise
 * light mode would draw white clock glyphs on a white bar.
 */
@Composable
fun RixTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
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

    CompositionLocalProvider(
        LocalRixPalette provides palette,
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(
            colorScheme = schemeFor(palette),
            typography = RixTypography,
            content = content
        )
    }
}