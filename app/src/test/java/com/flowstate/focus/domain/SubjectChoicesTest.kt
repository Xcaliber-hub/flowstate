package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectChoicesTest {
    @Test fun presetsMatchIgnoringCaseAndSpaces() {
        assertEquals("Maths", SubjectChoices.presetFor(" maths "))
        assertNull(SubjectChoices.presetFor("Mathematics"))
        assertFalse(SubjectChoices.isCustom(""))
        assertFalse(SubjectChoices.isCustom("PHYSICS"))
        assertTrue(SubjectChoices.isCustom("Physics – Rotational motion"))
    }

    @Test fun prefilledChapterShowsAsCustomChipFirst() {
        val chips = SubjectChoices.customChips("Physics – Rotational motion", listOf("Organic", "English"))
        assertEquals(listOf("Physics – Rotational motion", "Organic", "English"), chips)
    }

    @Test fun customChipsCapAndSkipPresetsAndDuplicates() {
        val chips = SubjectChoices.customChips("organic", listOf("Organic", "Physics", "English", "Hindi", "CS"))
        assertEquals(listOf("Organic", "English", "Hindi"), chips)
        assertEquals(listOf("A", "B", "C"), SubjectChoices.customChips("", listOf("A", "B", "C", "D")))
    }

    @Test fun rememberMovesToFrontAndKeepsThree() {
        assertEquals(listOf("D", "A", "B"), SubjectChoices.remember(listOf("A", "B", "C"), "D"))
        assertEquals(listOf("b", "A", "C"), SubjectChoices.remember(listOf("A", "B", "C"), " b ")) // latest spelling wins
        assertEquals(listOf("A"), SubjectChoices.remember(listOf("A"), "Chemistry"))
        assertEquals(listOf("A"), SubjectChoices.remember(listOf("A"), "  "))
    }
}
