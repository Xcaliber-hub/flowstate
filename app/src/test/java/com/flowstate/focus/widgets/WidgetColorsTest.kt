package com.flowstate.focus.widgets

import androidx.compose.ui.graphics.toArgb
import com.flowstate.focus.ui.theme.AccentColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetColorsTest {
    @Test fun darkGlassUsesTheBrightVariantOfEveryPresetIncludingSlate() {
        val expected = listOf(0xFFB7A4F5, 0xFF62D0BB, 0xFFFF83B2, 0xFF82D38B, 0xFF82B7FF, 0xFFFFB36E, 0xFFB6C4C9).map { it.toInt() }
        assertEquals(7, AccentColors.size)
        assertEquals(expected, (0 until 7).map { WidgetColors.accentFor(it, dark = true) })
    }

    @Test fun lightGlassUsesTheAppsOwnAccentColors() {
        (AccentColors.indices).forEach { i -> assertEquals(AccentColors[i].toArgb(), WidgetColors.accentFor(i, dark = false)) }
        assertEquals(0xFF253745.toInt(), WidgetColors.accentFor(6, dark = false)) // Slate
    }

    @Test fun outOfRangeIndexIsClamped() {
        assertEquals(WidgetColors.accentFor(0, true), WidgetColors.accentFor(-3, true))
        assertEquals(WidgetColors.accentFor(6, false), WidgetColors.accentFor(42, false))
    }

    @Test fun onAccentTextPicksTheReadableColour() {
        // Bright accents on dark glass take the near-black #17131F; the deep light-theme accents take white.
        WidgetColors.BRIGHT_ACCENTS.forEach { assertEquals(WidgetColors.ON_ACCENT_DARK, WidgetColors.onAccent(it)) }
        listOf(0xFF6750A4, 0xFF00796B, 0xFFC2185B, 0xFF2E7D32, 0xFF1565C0, 0xFF253745).forEach {
            assertEquals("for ${it.toString(16)}", WidgetColors.ON_ACCENT_LIGHT, WidgetColors.onAccent(it.toInt()))
        }
    }

    @Test fun paletteFollowsTheMode() {
        val dark = WidgetColors.palette(dark = true, accent = 0xFFB7A4F5.toInt())
        assertTrue(dark.dark)
        assertEquals(0x29FFFFFF, dark.track) // white 16 %
        assertEquals(0xFFF9F8FF.toInt(), dark.text)
        val light = WidgetColors.palette(dark = false, accent = 0xFF6750A4.toInt())
        assertFalse(light.dark)
        assertEquals(0x1A17151F, light.track) // #17151F 10 %
        assertEquals(0xFF17151F.toInt(), light.text)
        assertEquals(WidgetColors.ON_ACCENT_LIGHT, light.onAccent)
    }

    @Test fun syllabusRingsStartWithTheAccentThenTealAndBlue() {
        val lavender = 0xFFB7A4F5.toInt()
        assertEquals(listOf(lavender, WidgetColors.TEAL, WidgetColors.BLUE), WidgetColors.syllabusRingColors(lavender, 3))
    }

    @Test fun syllabusRingsSkipAColourTooCloseToTheAccent() {
        val teal = 0xFF62D0BB.toInt()
        val rings = WidgetColors.syllabusRingColors(teal, 3)
        assertEquals(teal, rings[0])
        assertFalse(WidgetColors.TEAL in rings.drop(1))
        assertEquals(WidgetColors.BLUE, rings[1])
        val blue = 0xFF1565C0.toInt() // the app's blue on light glass
        assertFalse(WidgetColors.BLUE in WidgetColors.syllabusRingColors(blue, 3).drop(1))
    }

    @Test fun iatGetsFourDistinctRings() {
        WidgetColors.BRIGHT_ACCENTS.forEach { accent ->
            val rings = WidgetColors.syllabusRingColors(accent, 4)
            assertEquals(4, rings.size)
            assertEquals(4, rings.toSet().size)
        }
    }

    @Test fun slateIsGreySoNothingClashes() {
        val slate = 0xFFB6C4C9.toInt()
        assertEquals(listOf(slate, WidgetColors.TEAL, WidgetColors.BLUE), WidgetColors.syllabusRingColors(slate, 3))
    }

    @Test fun withAlphaKeepsTheColour() {
        assertEquals(0x80B7A4F5.toInt(), WidgetColors.withAlpha(0xFFB7A4F5.toInt(), 0.5f))
    }
}
