package com.flowstate.focus.domain.groups

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
private const val IST_TIME_ZONE = "Asia/Kolkata"

/** Monday 00:00 in India Standard Time, independent of device locale and time zone. */
fun weeklyWeekStartIst(now: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(IST_TIME_ZONE), Locale.ROOT)
    calendar.timeInMillis = now
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
    return calendar.timeInMillis
}

/** ISO week key for the date in India Standard Time. */
fun weeklyWeekKeyIst(ms: Long): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(IST_TIME_ZONE), Locale.ROOT).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = ms
    }
    return "%04d-W%02d".format(Locale.ROOT, calendar.getWeekYear(), calendar.get(Calendar.WEEK_OF_YEAR))
}

/** Sum session intervals clipped to the Monday 00:00 IST-to-Monday 00:00 IST window. */
fun weeklyTotal(sessions: Iterable<Pair<Long, Long>>, now: Long): Long {
    val start = weeklyWeekStartIst(now)
    val end = start + WEEK_MS
    return sessions.sumOf { (startedAt, durationMs) ->
        val finish = startedAt + durationMs.coerceAtLeast(0L)
        (minOf(finish, end) - maxOf(startedAt, start)).coerceAtLeast(0L)
    }
}

/** Focus blocks that started inside this IST week (Monday 00:00 IST onwards). */
fun weeklySessionCount(startTimes: Iterable<Long>, now: Long): Int {
    val start = weeklyWeekStartIst(now)
    val end = start + WEEK_MS
    return startTimes.count { it in start until end }
}

/** Midnight IST of the day containing [now]. */
fun dayStartIst(now: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(IST_TIME_ZONE), Locale.ROOT)
    calendar.timeInMillis = now
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** Calendar date key in IST, for example "2026-10-26". */
fun dayKeyIst(ms: Long): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(IST_TIME_ZONE), Locale.ROOT).apply { timeInMillis = ms }
    return "%04d-%02d-%02d".format(Locale.ROOT, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
}

/** Sum session intervals clipped to today's IST day (midnight to midnight). */
fun dailyTotalIst(sessions: Iterable<Pair<Long, Long>>, now: Long): Long {
    val start = dayStartIst(now)
    // IST has no daylight saving, so a day is always 24 hours.
    val end = start + 24L * 60 * 60 * 1000
    return sessions.sumOf { (startedAt, durationMs) ->
        val finish = startedAt + durationMs.coerceAtLeast(0L)
        (minOf(finish, end) - maxOf(startedAt, start)).coerceAtLeast(0L)
    }
}
