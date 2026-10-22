package com.flowstate.focus.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetAvatarsTest {
    @Test fun catalogContainsOnlyApprovedIdsAndLabels() {
        assertEquals(listOf("sprout", "sunbeam", "moon", "fox", "orbit", "summit"), PresetAvatars.all.map { it.id })
        assertEquals(PresetAvatars.all.size, PresetAvatars.all.map { it.label }.toSet().size)
    }

    @Test fun validatesApprovedIdsAndEmptyBackwardCompatibleValue() {
        PresetAvatars.ids.forEach { assertTrue(PresetAvatars.isValid(it)) }
        assertTrue(PresetAvatars.isValid(""))
        assertFalse(PresetAvatars.isValid("https://example.com/avatar.png"))
        assertFalse(PresetAvatars.isValid("review-only"))
        assertFalse(PresetAvatars.isValid("SPRout"))
    }

    @Test fun fallbackPreservesOnlyApprovedValues() {
        PresetAvatars.ids.forEach { assertEquals(it, PresetAvatars.fallback(it)) }
        assertEquals("", PresetAvatars.fallback(null))
        assertEquals("", PresetAvatars.fallback("legacy-unknown"))
    }
}
