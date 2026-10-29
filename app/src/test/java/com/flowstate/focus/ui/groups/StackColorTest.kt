package com.flowstate.focus.ui.groups

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class StackColorTest {
    @Test fun firstAndLastRanksUseThePaletteEnds() {
        assertEquals(Color(0xFF06141B), stackColor(0, 7))
        assertEquals(Color(0xFFCCD0CF), stackColor(6, 7))
    }

    @Test fun singleMemberGetsTheDarkestShade() {
        assertEquals(Color(0xFF06141B), stackColor(0, 1))
    }
}
