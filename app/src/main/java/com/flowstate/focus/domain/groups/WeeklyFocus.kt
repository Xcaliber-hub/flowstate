package com.flowstate.focus.domain.groups

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** UTC Monday 00:00, independent of device locale and time zone. */
fun weeklyWeekStartUtc(now: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
    calendar.timeInMillis = now
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
    return calendar.timeInMillis
}

fun weeklyWeekKey(ms: Long): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = ms
    }
    return "%04d-W%02d".format(Locale.ROOT, calendar.getWeekYear(), calendar.get(Calendar.WEEK_OF_YEAR))
}

/** Sum session intervals clipped to the current UTC Monday-to-Monday window. */
fun weeklyTotal(sessions: Iterable<Pair<Long, Long>>, now: Long): Long {
    val start = weeklyWeekStartUtc(now)
    val end = start + WEEK_MS
    return sessions.sumOf { (startedAt, durationMs) ->
        val finish = startedAt + durationMs.coerceAtLeast(0L)
        (minOf(finish, end) - maxOf(startedAt, start)).coerceAtLeast(0L)
    }
}
