package com.flowstate.focus.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.domain.AppSettings

/** Slate: deep navy to pale grey, from the stacked-leaderboard reference. Darkest first. */
val SlatePalette = listOf(
    Color(0xFF06141B), Color(0xFF11212D), Color(0xFF253745),
    Color(0xFF4A5C6A), Color(0xFF9BA8AB), Color(0xFFCCD0CF),
)
val SlateAccent = Color(0xFF253745)

/** Used when Material You dynamic color is off or unavailable (Android 11 and below). Slate is last. */
val AccentColors = listOf(
    Color(0xFF6750A4), Color(0xFF00796B), Color(0xFFC2185B),
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00),
    SlateAccent,
)

/** The accent the user picked (or the wallpaper's primary with dynamic color), for components that build their own shades. */
val LocalAccentSeed = staticCompositionLocalOf { AccentColors.first() }

@Composable
fun FlowstateTheme(settings: AppSettings, darkTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val accent = AccentColors[settings.accentIndex.coerceIn(AccentColors.indices)]
    val base = when {
        dynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accent == SlateAccent -> slateScheme(darkTheme)
        else -> accentScheme(accent, darkTheme)
    }
    val seed = if (dynamic) dynamicLightColorScheme(context).primary else accent
    // Keep a true-black canvas, but lift content surfaces and secondary text enough to
    // preserve clear hierarchy against it. Do not alter the light palette.
    val slate = !dynamic && accent == SlateAccent
    val scheme = when {
        darkTheme && slate -> base // slateScheme already keeps the black canvas, with a faint navy tint on surfaces
        darkTheme -> base.copy(
            background = Color.Black,
            surface = Color(0xFF101010),
            surfaceVariant = Color(0xFF2A2A2A),
            surfaceContainer = Color(0xFF202020),
            surfaceContainerHigh = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xFFD0D0D0),
            outline = Color(0xFF909090),
            outlineVariant = Color(0xFF707070),
        )
        else -> base
    }
    CompositionLocalProvider(LocalAccentSeed provides seed) {
        MaterialTheme(colorScheme = scheme, typography = FlowstateTypography, shapes = FlowstateShapes, content = content)
    }
}

/**
 * Slate keeps the app black-and-white at heart: a true black (or near white) canvas, with surfaces
 * and accents pulled from [SlatePalette] so cards, buttons and the leaderboard share one cool tone.
 */
private fun slateScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = Color(0xFF9BA8AB),
            onPrimary = Color(0xFF06141B),
            primaryContainer = Color(0xFF253745),
            onPrimaryContainer = Color(0xFFCCD0CF),
            secondary = Color(0xFFCCD0CF),
            onSecondary = Color(0xFF06141B),
            secondaryContainer = Color(0xFF11212D),
            onSecondaryContainer = Color(0xFFCCD0CF),
            tertiary = Color(0xFF9BA8AB),
            background = Color.Black,
            onBackground = Color(0xFFF2F4F4),
            surface = Color(0xFF070D11),
            onSurface = Color(0xFFF2F4F4),
            surfaceVariant = Color(0xFF1C2A34),
            onSurfaceVariant = Color(0xFFC4CCCE),
            surfaceContainer = Color(0xFF0E1A21),
            surfaceContainerHigh = Color(0xFF16252F),
            outline = Color(0xFF7D8C93),
            outlineVariant = Color(0xFF4A5C6A),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF253745),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE2E3),
            onPrimaryContainer = Color(0xFF06141B),
            secondary = Color(0xFF4A5C6A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6EAEA),
            onSecondaryContainer = Color(0xFF11212D),
            tertiary = Color(0xFF4A5C6A),
            background = Color(0xFFF7F8F8),
            onBackground = Color(0xFF06141B),
            surface = Color(0xFFF7F8F8),
            onSurface = Color(0xFF06141B),
            surfaceVariant = Color(0xFFE3E7E8),
            onSurfaceVariant = Color(0xFF4A5C6A),
            surfaceContainer = Color(0xFFEEF0F0),
            surfaceContainerHigh = Color(0xFFE5E8E9),
            outline = Color(0xFF9BA8AB),
            outlineVariant = Color(0xFFCCD0CF),
        )
    }

private fun accentScheme(accent: Color, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = lerp(accent, Color.White, 0.45f),
            onPrimary = Color.Black,
            primaryContainer = lerp(accent, Color.Black, 0.45f),
            onPrimaryContainer = Color.White,
            secondary = lerp(accent, Color.White, 0.6f),
            tertiary = lerp(accent, Color(0xFF00E5FF), 0.5f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.White, 0.82f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
            secondary = lerp(accent, Color.Black, 0.2f),
            tertiary = lerp(accent, Color(0xFF00B8D4), 0.5f),
        )
    }

val FlowstateShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val FlowstateTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
)
