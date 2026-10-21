package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable
import com.flowstate.focus.data.db.SessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class StatsPeriod(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

@Immutable
data class Bucket(val label: String, val valueMs: Long)

/** Pure functions so every screen computes statistics the same way and they are easy to unit test. */
object StatsCalculator {
    /** A day only counts toward the streak with at least this much focus. */
    const val STREAK_MIN_MS = 5 * 60_000L

    fun dateOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun perDay(sessions: List<SessionEntity>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Long> =
        sessions.groupBy { dateOf(it.startedAt, zone) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }

    /** Consecutive qualifying days ending today, or yesterday if today has not qualified yet. */
    fun streak(perDay: Map<LocalDate, Long>, today: LocalDate): Int {
        var day = if ((perDay[today] ?: 0L) >= STREAK_MIN_MS) today else today.minusDays(1)
        var count = 0
        while ((perDay[day] ?: 0L) >= STREAK_MIN_MS) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    fun periodStart(period: StatsPeriod, today: LocalDate): LocalDate = when (period) {
        StatsPeriod.DAY -> today
        StatsPeriod.WEEK -> today.minusDays(6)
        StatsPeriod.MONTH -> today.minusDays(29)
        StatsPeriod.YEAR -> today.withDayOfMonth(1).minusMonths(11)
    }

    fun inPeriod(
        sessions: List<SessionEntity>,
        period: StatsPeriod,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SessionEntity> {
        val start = periodStart(period, today)
        return sessions.filter { dateOf(it.startedAt, zone) >= start }
    }

    /** Chart buckets for sessions that are already filtered to [period]. */
    fun buckets(
        period: StatsPeriod,
        sessions: List<SessionEntity>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Bucket> {
        val locale = Locale.getDefault()
        return when (period) {
            StatsPeriod.DAY -> {
                val byHour = sessions
                    .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }
                    .mapValues { (_, list) -> list.sumOf { it.durationMs } }
                (0..23).map { Bucket(if (it % 6 == 0) it.toString() else "", byHour[it] ?: 0L) }
            }
            StatsPeriod.WEEK -> {
                val map = perDay(sessions, zone)
                (6 downTo 0).map {
                    val d = today.minusDays(it.toLong())
                    Bucket(d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), map[d] ?: 0L)
                }
            }
            StatsPeriod.MONTH -> {
                val map = perDay(sessions, zone)
                (29 downTo 0).map {
                    val d = today.minusDays(it.toLong())
                    Bucket(if (it % 5 == 0) d.dayOfMonth.toString() else "", map[d] ?: 0L)
                }
            }
            StatsPeriod.YEAR -> {
                val byMonth = sessions
                    .groupBy { YearMonth.from(dateOf(it.startedAt, zone)) }
                    .mapValues { (_, list) -> list.sumOf { it.durationMs } }
                val current = YearMonth.from(today)
                (11 downTo 0).map {
                    val ym = current.minusMonths(it.toLong())
                    Bucket(ym.month.getDisplayName(TextStyle.NARROW, locale), byMonth[ym] ?: 0L)
                }
            }
        }
    }

    /** 0-100. Blends how close you are to your daily goal with how many sessions you finish. */
    fun productivityScore(weekMs: Long, goalMs: Long, completionRate: Float): Int {
        if (goalMs <= 0L) return 0
        val goalPart = (weekMs / (goalMs * 7f)).coerceIn(0f, 1f) * 70f
        return (goalPart + completionRate.coerceIn(0f, 1f) * 30f).toInt().coerceIn(0, 100)
    }
}
