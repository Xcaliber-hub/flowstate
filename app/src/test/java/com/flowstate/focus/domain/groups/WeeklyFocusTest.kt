package com.flowstate.focus.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeeklyFocusTest {
    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day, hour, 0, 0)
    }.timeInMillis

    @Test fun clipsIntervalsToUtcMondayWindow() {
        val monday = utc(2025, 1, 6)
        assertEquals(monday, weeklyWeekStartUtc(utc(2025, 1, 8, 13)))
        assertEquals(3_600_000L, weeklyTotal(listOf((monday - 3_600_000L) to 7_200_000L), utc(2025, 1, 8)))
        assertEquals(0L, weeklyTotal(listOf((monday - 9_000_000L) to 3_600_000L), utc(2025, 1, 8)))
        assertEquals("2025-W02", weeklyWeekKey(monday))
    }

    @Test fun weekRollsAtUtcMondayMidnight() {
        val sunday = utc(2025, 1, 12, 23)
        val monday = utc(2025, 1, 13)
        assertEquals(utc(2025, 1, 6), weeklyWeekStartUtc(sunday))
        assertEquals(monday, weeklyWeekStartUtc(monday))
        assertEquals("2025-W03", weeklyWeekKey(monday))
        assertEquals(0L, weeklyTotal(listOf((sunday - 3_600_000L) to 7_200_000L), monday))
    }
}
