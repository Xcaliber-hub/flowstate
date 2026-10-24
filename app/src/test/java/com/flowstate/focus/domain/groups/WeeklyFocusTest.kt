package com.flowstate.focus.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeeklyFocusTest {
    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test fun clipsIntervalsToIstMondayWindow() {
        val mondayIst = utc(2025, 1, 5, 18, 30)
        assertEquals(mondayIst, weeklyWeekStartIst(utc(2025, 1, 8, 13)))
        assertEquals(1_800_000L, weeklyTotal(listOf((mondayIst - 1_800_000L) to 3_600_000L), utc(2025, 1, 8)))
        assertEquals(0L, weeklyTotal(listOf((mondayIst - 9_000_000L) to 3_600_000L), utc(2025, 1, 8)))
        assertEquals("2025-W02", weeklyWeekKeyIst(mondayIst))
    }

    @Test fun weekRollsAtMondayMidnightIst() {
        val beforeReset = utc(2025, 1, 12, 18, 29)
        val reset = utc(2025, 1, 12, 18, 30)
        assertEquals(utc(2025, 1, 5, 18, 30), weeklyWeekStartIst(beforeReset))
        assertEquals(reset, weeklyWeekStartIst(reset))
        assertEquals("2025-W02", weeklyWeekKeyIst(beforeReset))
        assertEquals("2025-W03", weeklyWeekKeyIst(reset))
        assertEquals(0L, weeklyTotal(listOf((beforeReset - 1_800_000L) to 1_800_000L), reset))
    }
}
