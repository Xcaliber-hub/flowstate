package com.flowstate.focus.widgets

import androidx.compose.ui.graphics.toArgb
import com.flowstate.focus.ui.theme.AccentColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 1.8.8: the colours every home-screen widget is drawn with. Pure maths on ARGB ints (no android.graphics), so
 * the theme sync is unit tested on the JVM ([com.flowstate.focus.widgets.WidgetColorsTest]).
 *
 * The widgets follow the app's palette:
 *  - dark glass uses brighter equivalents of the seven accent presets (lavender … Slate), which stay readable
 *    on translucent dark glass;
 *  - light glass uses the app's own [AccentColors], exactly as the app's light theme does;
 *  - with dynamic colour (Android 12+) the data source passes the wallpaper accent instead
 *    (system_accent1_200 on dark glass, system_accent1_600 on light glass), see [FlowstateWidgetDataSource].
 */
internal data class WidgetPalette(
    val dark: Boolean,
    val accent: Int,
    /** Text and icons drawn on top of [accent] (filled buttons, the Pomodoro pill, ticked boxes). */
    val onAccent: Int,
    val text: Int,
    val sub: Int,
    val muted: Int,
    /** Unfilled part of rings, bars and empty heatmap cells. */
    val track: Int,
    /** Hairline separators. */
    val rim: Int,
    /** 1.9.2: Liquid glass UI is on, so the card is clear glass (no tint) and text follows the wallpaper. */
    val liquid: Boolean = false,
)

internal object WidgetColors {
    /** Bright variants for dark glass, in the app's preset order (lavender, teal, pink, green, blue, orange, Slate). */
    val BRIGHT_ACCENTS: IntArray = intArrayOf(
        0xFFB7A4F5.toInt(), 0xFF62D0BB.toInt(), 0xFFFF83B2.toInt(),
        0xFF82D38B.toInt(), 0xFF82B7FF.toInt(), 0xFFFFB36E.toInt(),
        0xFFB6C4C9.toInt(),
    )

    /** The app's presets as used by its light theme (Slate is last). */
    val LIGHT_ACCENTS: IntArray get() = AccentColors.map { it.toArgb() }.toIntArray()

    const val TEXT_DARK_GLASS = 0xFFF9F8FF.toInt()
    const val SUB_DARK_GLASS = 0xFFD0CDDA.toInt()
    const val MUTED_DARK_GLASS = 0x8CF9F8FF.toInt()
    const val TEXT_LIGHT_GLASS = 0xFF17151F.toInt()
    const val SUB_LIGHT_GLASS = 0xFF4A4658.toInt()
    const val MUTED_LIGHT_GLASS = 0x8017151F.toInt()
    /** White at 16 % on dark glass, #17151F at 10 % on light glass. */
    const val TRACK_DARK_GLASS = 0x29FFFFFF
    const val TRACK_LIGHT_GLASS = 0x1A17151F
    const val RIM_DARK_GLASS = 0x38FFFFFF
    const val RIM_LIGHT_GLASS = 0x2617151F

    const val ON_ACCENT_DARK = 0xFF17131F.toInt()
    const val ON_ACCENT_LIGHT = 0xFFFFFFFF.toInt()

    /** Status dot and break tint. */
    const val TEAL = 0xFF62D0BB.toInt()
    const val BLUE = 0xFF82B7FF.toInt()
    const val AMBER = 0xFFFFB36E.toInt()

    /** Secondary syllabus ring colours, in order of preference; one too close in hue to the accent is skipped. */
    private val RING_CANDIDATES = intArrayOf(TEAL, BLUE, 0xFFFFB36E.toInt(), 0xFFFF83B2.toInt(), 0xFFB7A4F5.toInt(), 0xFF82D38B.toInt())

    /** The accent for preset [index] (clamped to the seven presets) on dark or light glass. */
    fun accentFor(index: Int, dark: Boolean): Int {
        val list = if (dark) BRIGHT_ACCENTS else LIGHT_ACCENTS
        return list[index.coerceIn(0, list.lastIndex)]
    }

    fun palette(dark: Boolean, accent: Int, liquid: Boolean = false): WidgetPalette = WidgetPalette(
        liquid = liquid,
        dark = dark,
        accent = accent or 0xFF000000.toInt(),
        onAccent = onAccent(accent),
        text = if (dark) TEXT_DARK_GLASS else TEXT_LIGHT_GLASS,
        sub = if (dark) SUB_DARK_GLASS else SUB_LIGHT_GLASS,
        muted = if (dark) MUTED_DARK_GLASS else MUTED_LIGHT_GLASS,
        track = if (dark) TRACK_DARK_GLASS else TRACK_LIGHT_GLASS,
        rim = if (dark) RIM_DARK_GLASS else RIM_LIGHT_GLASS,
    )

    /** #17131F or white, whichever has the higher WCAG contrast on [accent]. */
    fun onAccent(accent: Int): Int {
        val l = luminance(accent)
        val l1 = luminance(ON_ACCENT_DARK)
        val contrastDark = (l + 0.05) / (l1 + 0.05)
        val contrastWhite = 1.05 / (l + 0.05)
        return if (contrastDark >= contrastWhite) ON_ACCENT_DARK else ON_ACCENT_LIGHT
    }

    /** WCAG relative luminance of an sRGB colour (alpha ignored). */
    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) + 0.7152 * channel((color shr 8) and 0xFF) + 0.0722 * channel(color and 0xFF)
    }

    /** HSV hue in degrees (0 until 360) and saturation (0..1). */
    fun hueSat(color: Int): Pair<Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val sat = if (mx == 0f) 0f else d / mx
        if (d == 0f) return 0f to 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return ((h + 360f) % 360f) to sat
    }

    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * Ring colours for [count] syllabus subjects: the first ring is the accent, the rest teal #62D0BB, then blue
     * #82B7FF (then further fallbacks for IAT's fourth subject), skipping any candidate whose hue is within
     * [MIN_HUE_GAP] degrees of the accent or of a ring already chosen. A grey accent (Slate) has no hue to clash with.
     */
    fun syllabusRingColors(accent: Int, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val chosen = mutableListOf(accent or 0xFF000000.toInt())
        for (c in RING_CANDIDATES) {
            if (chosen.size >= count) break
            val (h, _) = hueSat(c)
            val clash = chosen.any { other ->
                val (oh, os) = hueSat(other)
                os >= GREY_SATURATION && hueDistance(h, oh) < MIN_HUE_GAP
            }
            if (!clash) chosen += c
        }
        // Never fewer rings than subjects, even if every candidate clashed.
        while (chosen.size < count) chosen += RING_CANDIDATES[chosen.size % RING_CANDIDATES.size]
        return chosen.take(count)
    }

    /** Replaces the alpha of [color] with [alpha] (0..1). */
    fun withAlpha(color: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (color and 0x00FFFFFF)

    const val MIN_HUE_GAP = 30f
    const val GREY_SATURATION = 0.2f
}
