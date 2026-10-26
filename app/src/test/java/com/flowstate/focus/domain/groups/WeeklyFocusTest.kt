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

    @Test fun countsSessionsStartedThisIstWeek() {
        val mondayIst = utc(2025, 1, 5, 18, 30)
        val now = utc(2025, 1, 8)
        assertEquals(2, weeklySessionCount(listOf(mondayIst, mondayIst + 3_600_000L, mondayIst - 1L), now))
    }

    @Test fun dailyTotalClipsToIstMidnight() {
        // 26 Oct 2026 00:00 IST is 25 Oct 18:30 UTC.
        val midnight = utc(2026, 10, 25, 18, 30)
        val now = midnight + 10 * 3_600_000L
        assertEquals(midnight, dayStartIst(now))
        assertEquals("2026-10-26", dayKeyIst(now))
        assertEquals("2026-10-25", dayKeyIst(midnight - 1L))
        val sessions = listOf((midnight - 1_800_000L) to 3_600_000L, (midnight + 3_600_000L) to 1_200_000L)
        assertEquals(1_800_000L + 1_200_000L, dailyTotalIst(sessions, now))
        assertEquals(0L, dailyTotalIst(emptyList(), now))
    }

    @Test fun leaguesFollowWeeklyFocus() {
        assertEquals(null, League.of(0L))
        assertEquals(League.BRONZE, League.of(5 * 3_600_000L))
        assertEquals(League.SILVER, League.of(19 * 3_600_000L))
        assertEquals(League.DIAMOND, League.of(100 * 3_600_000L))
    }
}
