package com.flowstate.focus.domain

import com.flowstate.focus.data.db.SessionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 1.7.2 "This week's sessions": the current Monday–Sunday week in local time. */
data class WeekRange(
    /** Monday of the week. */
    val monday: LocalDate,
    /** Monday 00:00 local, epoch ms (inclusive). */
    val startMs: Long,
    /** The next Monday 00:00 local, epoch ms (exclusive). */
    val endMs: Long,
) {
    val sunday: LocalDate get() = monday.plusDays(6)

    operator fun contains(ms: Long): Boolean = ms in startMs until endMs

    companion object {
        /**
         * The week containing [today]. Bounds come from each day's real start in [zone], so a week with
         * a daylight-saving change is 167 or 169 hours long and still starts and ends at midnight.
         */
        fun of(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): WeekRange {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return WeekRange(
                monday = monday,
                startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli(),
                endMs = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
    }
}

/** One day of the list: the day, its total and its sessions, newest first. */
data class SessionDay(val date: LocalDate, val totalMs: Long, val sessions: List<SessionEntity>)

object WeekSessions {
    /**
     * Sessions that started inside [week], grouped by their local start date (as the Focus screen's
     * "Today's sessions" does), newest day first and newest session first within a day.
     */
    fun group(sessions: List<SessionEntity>, week: WeekRange, zone: ZoneId = ZoneId.systemDefault()): List<SessionDay> =
        sessions.filter { it.startedAt in week }
            .sortedWith(compareByDescending<SessionEntity> { it.startedAt }.thenByDescending { it.id })
            .groupBy { StatsCalculator.dateOf(it.startedAt, zone) }
            .map { (date, list) -> SessionDay(date, list.sumOf { it.durationMs }, list) }
            .sortedByDescending { it.date }
}
