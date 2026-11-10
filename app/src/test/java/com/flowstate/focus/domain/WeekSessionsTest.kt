package com.flowstate.focus.domain

import com.flowstate.focus.data.db.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeekSessionsTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, zone: ZoneId = ist) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: Long, at: Long, minutes: Long = 25) =
        SessionEntity(id = id, startedAt = at, durationMs = minutes * 60_000L, mode = "POMODORO", subject = "", completed = true)

    @Test
    fun everyDayOfTheWeekMapsToTheSameMondayToSunday() {
        // Mon 9 Nov 2026 .. Sun 15 Nov 2026
        for (d in 9..15) {
            val w = WeekRange.of(LocalDate.of(2026, 11, d), ist)
            assertEquals(LocalDate.of(2026, 11, 9), w.monday)
            assertEquals(LocalDate.of(2026, 11, 15), w.sunday)
            assertEquals(ms(2026, 11, 9), w.startMs)
            assertEquals(ms(2026, 11, 16), w.endMs)
        }
    }

    @Test
    fun boundsAreMidnightToMidnightInclusiveExclusive() {
        val w = WeekRange.of(LocalDate.of(2026, 11, 10), ist)
        assertTrue(ms(2026, 11, 9, 0, 0) in w)
        assertTrue(ms(2026, 11, 15, 23, 59) in w)
        assertFalse(ms(2026, 11, 8, 23, 59) in w)
        assertFalse(ms(2026, 11, 16, 0, 0) in w)
    }

    @Test
    fun weekCrossesMonthAndYearBoundaries() {
        val w = WeekRange.of(LocalDate.of(2027, 1, 1), ist) // a Friday
        assertEquals(LocalDate.of(2026, 12, 28), w.monday)
        assertEquals(LocalDate.of(2027, 1, 3), w.sunday)
    }

    @Test
    fun daylightSavingWeekStillStartsAndEndsAtMidnight() {
        val ny = ZoneId.of("America/New_York")
        // DST ends Sun 1 Nov 2026 in New York: that week is 169 hours long.
        val w = WeekRange.of(LocalDate.of(2026, 10, 28), ny)
        assertEquals(LocalDate.of(2026, 10, 26), w.monday)
        assertEquals(ms(2026, 10, 26, zone = ny), w.startMs)
        assertEquals(ms(2026, 11, 2, zone = ny), w.endMs)
        assertEquals(169L * 3_600_000L, w.endMs - w.startMs)
    }

    @Test
    fun groupsNewestDayFirstAndNewestSessionFirst() {
        val w = WeekRange.of(LocalDate.of(2026, 11, 11), ist)
        val rows = listOf(
            session(1, ms(2026, 11, 8, 22)),       // last week: dropped
            session(2, ms(2026, 11, 9, 9)),
            session(3, ms(2026, 11, 11, 7), 50),
            session(4, ms(2026, 11, 11, 18), 30),
            session(5, ms(2026, 11, 9, 23, 50)),   // started before midnight: filed on Monday
            session(6, ms(2026, 11, 16, 0, 5)),    // next week: dropped
        )
        val days = WeekSessions.group(rows, w, ist)
        assertEquals(listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 9)), days.map { it.date })
        assertEquals(listOf(4L, 3L), days[0].sessions.map { it.id })
        assertEquals(80L * 60_000L, days[0].totalMs)
        assertEquals(listOf(5L, 2L), days[1].sessions.map { it.id })
    }
}
