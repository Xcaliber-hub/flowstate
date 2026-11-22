package com.flowstate.focus.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetAvatarsTest {
    @Test fun twoCategoriesOfTwentyFourUniqueIds() {
        assertEquals(listOf("Orbs", "Stitched"), PresetAvatars.categories.map { it.name })
        assertEquals(24, PresetAvatars.orbs.size)
        assertEquals(24, PresetAvatars.stitched.size)
        assertEquals(48, PresetAvatars.ids.size)
        assertEquals(48, PresetAvatars.all.map { it.label }.toSet().size)
        PresetAvatars.ids.forEach { assertTrue(it, it.matches(Regex("[a-z0-9]+"))) }
    }

    @Test fun validatesApprovedIdsAndEmptyBackwardCompatibleValue() {
        PresetAvatars.ids.forEach { assertTrue(PresetAvatars.isValid(it)) }
        assertTrue(PresetAvatars.isValid(""))
        listOf("https://example.com/avatar.png", "review-only", "Aurora", "koi ", "upload").forEach {
            assertFalse(PresetAvatars.isValid(it))
        }
    }

    @Test fun removedStickerIdsFallBackToInitials() {
        listOf("sprout", "fox", "owl", "cactus", "mushroom", "coffee", "wave", "bolt").forEach {
            assertFalse(PresetAvatars.isValid(it))
            assertEquals("", PresetAvatars.fallback(it))
        }
        assertEquals("", PresetAvatars.fallback(null))
        PresetAvatars.ids.forEach { assertEquals(it, PresetAvatars.fallback(it)) }
    }

    @Test fun categoryOfFindsTheRightTab() {
        assertEquals(0, PresetAvatars.categoryOf("aurora"))
        assertEquals(1, PresetAvatars.categoryOf("chaiowl"))
        assertEquals(0, PresetAvatars.categoryOf(""))
    }
}
