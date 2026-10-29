package com.flowstate.focus.ui.groups

import androidx.compose.ui.graphics.Color
import com.flowstate.focus.ui.theme.AccentColors
import com.flowstate.focus.ui.theme.SlateAccent
import com.flowstate.focus.ui.theme.SlatePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StackColorTest {
    @Test fun slateUsesTheReferencePalette() {
        assertEquals(SlatePalette, stackPalette(SlateAccent))
        val light = rankedPalette(SlateAccent, dark = false)
        assertEquals(Color(0xFF06141B), stackColor(0, 7, light))
        assertEquals(Color(0xFFCCD0CF), stackColor(6, 7, light))
    }

    @Test fun darkThemeFadesIntoBlack() {
        val dark = rankedPalette(SlateAccent, dark = true)
        assertEquals(Color(0xFFCCD0CF), stackColor(0, 7, dark))
        assertEquals(Color(0xFF06141B), stackColor(6, 7, dark))
    }

    @Test fun everyAccentRampsFromDarkToLight() {
        AccentColors.forEach { accent ->
            val p = stackPalette(accent)
            assertEquals(6, p.size)
            for (i in 1 until p.size) assertTrue(p[i].red + p[i].green + p[i].blue > p[i - 1].red + p[i - 1].green + p[i - 1].blue)
        }
    }

    @Test fun singleMemberGetsTheFirstShade() {
        val p = rankedPalette(AccentColors.first(), dark = false)
        assertEquals(p.first(), stackColor(0, 1, p))
    }
}
