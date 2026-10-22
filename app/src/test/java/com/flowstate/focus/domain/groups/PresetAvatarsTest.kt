package com.flowstate.focus.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetAvatarsTest {
    @Test fun catalogContainsAllTwelveApprovedIdsAndLabelsInOrder() {
        val expected = listOf(
            "sprout" to "Sprout", "sunbeam" to "Sunbeam", "moon" to "Moon",
            "fox" to "Fox", "orbit" to "Orbit", "summit" to "Summit",
            "cloud" to "Cloud", "ember" to "Ember", "turtle" to "Turtle",
            "jelly" to "Jelly", "comet" to "Comet", "bloom" to "Bloom",
        )
        assertEquals(expected, PresetAvatars.all.map { it.id to it.label })
        assertEquals(12, PresetAvatars.ids.size)
        assertEquals(12, PresetAvatars.all.map { it.label }.toSet().size)
    }

    @Test fun validatesApprovedIdsAndEmptyBackwardCompatibleValue() {
        PresetAvatars.ids.forEach { assertTrue(PresetAvatars.isValid(it)) }
        assertTrue(PresetAvatars.isValid(""))
        listOf("https://example.com/avatar.png", "review-only", "SPRout", "Cloud", "bloom ", "upload").forEach {
            assertFalse(PresetAvatars.isValid(it))
        }
    }

    @Test fun fallbackPreservesOnlyApprovedValues() {
        PresetAvatars.ids.forEach { assertEquals(it, PresetAvatars.fallback(it)) }
        assertEquals("", PresetAvatars.fallback(null))
        assertEquals("", PresetAvatars.fallback("legacy-unknown"))
    }

    @Test fun additionalPresetsAreAcceptedWithoutChangingOriginalIds() {
        assertEquals(listOf("sprout", "sunbeam", "moon", "fox", "orbit", "summit"),
            PresetAvatars.all.take(6).map { it.id })
        listOf("cloud", "ember", "turtle", "jelly", "comet", "bloom").forEach {
            assertTrue(PresetAvatars.isValid(it))
            assertEquals(it, PresetAvatars.fallback(it))
        }
    }
}
