package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExamsTest {
    private val today = LocalDate.of(2026, 11, 6)
    private fun exam(id: Long, date: LocalDate, shown: Boolean = true, pinned: Boolean = false) =
        Exam(id = id, name = "E$id", date = date, showOnFocus = shown, pinned = pinned, createdAt = 0)

    @Test fun labels() {
        assertEquals("D-76", ExamCountdown.dLabel(exam(1, today.plusDays(76)), today))
        assertEquals("D-Day", ExamCountdown.dLabel(exam(1, today), today))
        assertEquals("Done", ExamCountdown.dLabel(exam(1, today.minusDays(1)), today))
    }

    @Test fun focusListPutsPinnedFirstThenNearestAndSkipsPastAndHidden() {
        val near = exam(1, today.plusDays(10))
        val far = exam(2, today.plusDays(90), pinned = true)
        val mid = exam(3, today.plusDays(40))
        val hidden = exam(4, today.plusDays(5), shown = false)
        val past = exam(5, today.minusDays(3))
        val list = ExamCountdown.forFocus(listOf(mid, past, near, hidden, far), today)
        assertEquals(listOf(2L, 1L, 3L), list.map { it.id })
    }

    @Test fun mainIsPinnedUpcomingElseNearest() {
        val near = exam(1, today.plusDays(10))
        val pinnedPast = exam(2, today.minusDays(2), pinned = true)
        assertEquals(1L, ExamCountdown.main(listOf(pinnedPast, near), today)?.id)
        assertNull(ExamCountdown.main(listOf(pinnedPast), today))
    }

    @Test fun timeLeftReadsNaturally() {
        assertEquals("10 weeks, 6 days left", ExamCountdown.timeLeft(exam(1, today.plusDays(76)), today))
        assertEquals("1 week left", ExamCountdown.timeLeft(exam(1, today.plusDays(7)), today))
        assertEquals("1 day left", ExamCountdown.timeLeft(exam(1, today.plusDays(1)), today))
        assertEquals("Today", ExamCountdown.timeLeft(exam(1, today), today))
    }

    @Test fun progressIsClamped() {
        val e = exam(1, today.plusDays(10))
        assertEquals(0f, ExamCountdown.progress(e, today, today), 1e-6f)
        assertEquals(0.5f, ExamCountdown.progress(e, today.plusDays(5), today), 1e-6f)
        assertTrue(ExamCountdown.progress(e, today.plusDays(30), today) <= 1f)
    }
}

class AmbientMixTest {
    @Test fun toggleKeepsLevelAndDragSwitchesOn() {
        val m = AmbientMix(enabled = emptySet())
        val on = m.withLevel(AmbientSound.CAFE, 0.4f)
        assertTrue(on.isOn(AmbientSound.CAFE))
        val off = on.toggle(AmbientSound.CAFE)
        assertEquals(false, off.isOn(AmbientSound.CAFE))
        assertEquals(0.4f, off.level(AmbientSound.CAFE), 0f)
        assertTrue(off.toggle(AmbientSound.CAFE).isOn(AmbientSound.CAFE))
    }

    @Test fun names() {
        assertEquals("Rain", AmbientMix(enabled = setOf(AmbientSound.RAIN)).name)
        assertEquals("Rain + Library", AmbientMix(enabled = setOf(AmbientSound.LIBRARY, AmbientSound.RAIN)).name)
        assertEquals("Rain + 2 more", AmbientMix(enabled = setOf(AmbientSound.RAIN, AmbientSound.FIRE, AmbientSound.WAVES)).name)
    }
}
