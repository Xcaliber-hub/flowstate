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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.domain.AppSettings

/** Used when Material You dynamic color is off or unavailable (Android 11 and below). */
val AccentColors = listOf(
    Color(0xFF6750A4), Color(0xFF00796B), Color(0xFFC2185B),
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00),
)

@Composable
fun FlowstateTheme(settings: AppSettings, darkTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accentScheme(AccentColors[settings.accentIndex.coerceIn(AccentColors.indices)], darkTheme)
    }
    // True black background in dark mode: saves power on AMOLED panels.
    val scheme = if (darkTheme) base.copy(background = Color.Black, surface = Color(0xFF050505)) else base
    MaterialTheme(colorScheme = scheme, typography = FlowstateTypography, shapes = FlowstateShapes, content = content)
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
