package com.flowstate.focus.widgets

import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.ChapterProgress
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.ExamCountdown
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.Syllabus
import com.flowstate.focus.domain.SyllabusExam
import com.flowstate.focus.domain.SyllabusKeys
import com.flowstate.focus.domain.SyllabusMath
import com.flowstate.focus.domain.SyllabusStep
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * 1.8.8: what each widget shows, mapped from app state by pure functions so the mapping is unit tested on the
 * JVM (WidgetModelsTest). Rendering (RemoteViews, bitmaps) lives in the providers and WidgetBitmaps.
 */

// ---------------------------------------------------------------------------------------------- timer

/** How the big time is shown. A running phase ticks on its own in a Chronometer; a paused one is static text. */
internal sealed interface TimeDisplay {
    /** Counts down to [endAt] (wall clock ms). */
    data class CountDown(val endAt: Long) : TimeDisplay
    /** Counts up from [startedAt] (wall clock ms), for the stopwatch. */
    data class CountUp(val startedAt: Long) : TimeDisplay
    data class Fixed(val text: String) : TimeDisplay
}

internal data class TimerModel(
    val idle: Boolean,
    /** Another device of this account runs the timer: widgets open the app instead of starting one here. */
    val remoteLocked: Boolean,
    val remoteLabel: String,
    val running: Boolean,
    val paused: Boolean,
    val isBreak: Boolean,
    /** "FOCUSING", "PAUSED", "SHORT BREAK", "LONG BREAK", "BREAK". */
    val status: String,
    /** The subject, or "Focus" / "Break" when none was picked. */
    val subject: String,
    /** "Round 2 of 4" for Pomodoro, else the mode ("Countdown", "Stopwatch"). */
    val roundLabel: String,
    /** "2/4" for Pomodoro, "" otherwise. */
    val roundShort: String,
    /** Share of the phase already behind, 0..1 (the stopwatch fills once per hour). */
    val fraction: Float,
    val time: TimeDisplay,
    /** "Break at 5:41 pm", "Ends at 6:10 pm", "Started 4:02 pm", "Back to focus at 5:46 pm". */
    val nextLine: String,
    val canPause: Boolean,
    val canStop: Boolean,
    val canSkip: Boolean,
    /** Break mode: the remaining break minutes (rounded) for the idle "next break" line. */
    val breakTip: String,
    val mode: TimerMode,
)

internal object TimerModels {
    val BREAK_TIPS = listOf(
        "Stretch, sip water, look away",
        "Stand up and roll your shoulders",
        "Look at something 20 feet away",
        "Take five slow, deep breaths",
        "Refill your water bottle",
        "Walk around for a minute",
    )

    fun clockText(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
    }

    fun clockTextUp(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
    }

    /** "5:41 pm" in the user's locale (12 or 24 hour as [use24h] says). */
    fun timeOfDay(ms: Long, zone: ZoneId, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
        val pattern = if (use24h) "H:mm" else "h:mm a"
        val text = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))
        return if (use24h) text else text.lowercase(locale)
    }

    fun map(
        s: TimerState,
        now: Long,
        remoteLabel: String?,
        zone: ZoneId,
        use24h: Boolean,
        idleFocusMin: Int,
    ): TimerModel {
        val remoteLocked = remoteLabel != null
        if (!s.isActive) {
            return TimerModel(
                idle = true, remoteLocked = remoteLocked, remoteLabel = remoteLabel.orEmpty(),
                running = false, paused = false, isBreak = false,
                status = if (remoteLocked) "ON YOUR ${remoteLabel!!.uppercase()}" else "READY",
                subject = if (remoteLocked) "Focus is running on your $remoteLabel" else "Ready to focus",
                roundLabel = if (remoteLocked) "Open Flowstate" else "$idleFocusMin min Pomodoro",
                roundShort = "", fraction = 0f, time = TimeDisplay.Fixed("Ready"),
                nextLine = if (remoteLocked) "Tap to open Flowstate" else "Tap play to start a $idleFocusMin min Pomodoro",
                canPause = false, canStop = false, canSkip = false,
                breakTip = BREAK_TIPS.first(), mode = s.mode,
            )
        }
        val paused = s.status == Status.PAUSED
        val running = s.status == Status.RUNNING
        val pomodoro = s.mode == TimerMode.POMODORO
        val rounds = s.plan.rounds.coerceAtLeast(1)
        // Round within the cycle, 1-based. On a break [round] has already moved on, so the break belongs to the round before.
        val roundIndex = if (s.isBreak && !s.standaloneBreak) ((s.round - 1).coerceAtLeast(0) % rounds) + 1 else (s.round % rounds) + 1
        val status = when {
            s.isBreak && s.phase == Phase.LONG_BREAK -> "LONG BREAK"
            s.isBreak && s.standaloneBreak -> "BREAK"
            s.isBreak -> "SHORT BREAK"
            paused -> "PAUSED"
            else -> "FOCUSING"
        }
        val subject = when {
            s.isBreak -> if (s.phase == Phase.LONG_BREAK) "Long break" else if (s.standaloneBreak) "Break" else "Short break"
            s.subject.isNotBlank() -> s.subject
            else -> "Focus"
        }
        val elapsed = s.elapsedAt(now)
        val fraction = when {
            s.countsUp -> ((elapsed % 3_600_000L) / 3_600_000f)
            else -> (elapsed.toFloat() / s.phaseDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
        val time: TimeDisplay = when {
            paused && s.countsUp -> TimeDisplay.Fixed(clockTextUp(elapsed))
            paused -> TimeDisplay.Fixed(clockText(s.remainingAt(now)))
            s.countsUp -> TimeDisplay.CountUp(s.phaseStartedAt)
            else -> TimeDisplay.CountDown(s.endAt)
        }
        val at = { ms: Long -> timeOfDay(ms, zone, use24h) }
        val nextLine = when {
            paused -> if (s.autoPaused) "Paused: you seemed away" else "Paused"
            s.isBreak && pomodoro && !s.standaloneBreak -> "Back to focus at ${at(s.endAt)}"
            s.isBreak -> "Ends at ${at(s.endAt)}"
            s.countsUp -> "Started ${at(s.phaseStartedAt)}"
            pomodoro -> "Break at ${at(s.endAt)}"
            else -> "Ends at ${at(s.endAt)}"
        }
        // A strict focus block has no way out from a widget, same as the notification.
        val locked = s.strict && s.phase == Phase.FOCUS
        val tipIndex = ((s.phaseStartedAt / 60_000L) % BREAK_TIPS.size).toInt().coerceIn(0, BREAK_TIPS.lastIndex)
        return TimerModel(
            idle = false, remoteLocked = remoteLocked, remoteLabel = remoteLabel.orEmpty(),
            running = running, paused = paused, isBreak = s.isBreak,
            status = status, subject = subject,
            roundLabel = when {
                pomodoro && !s.standaloneBreak -> "Round $roundIndex of $rounds"
                s.isBreak -> "Break"
                else -> s.mode.label
            },
            roundShort = if (pomodoro && !s.standaloneBreak) "$roundIndex/$rounds" else "",
            fraction = fraction,
            time = time,
            nextLine = nextLine,
            canPause = !locked,
            canStop = !locked,
            canSkip = !locked && (s.isBreak || (pomodoro && s.phase == Phase.FOCUS)),
            breakTip = BREAK_TIPS[tipIndex],
            mode = s.mode,
        )
    }
}

// ---------------------------------------------------------------------------------------------- stats

internal data class DailyModel(
    val focusedMs: Long,
    val goalMs: Long,
    val percent: Int,
    val sessions: Int,
    val streak: Int,
    /** Focus per day of the current week, Monday first. */
    val week: List<Long>,
    /** Index of today in [week] (0 = Monday). */
    val todayIndex: Int,
)

internal data class StreakModel(
    val current: Int,
    val best: Int,
    /** 28 shade levels (0 = none … 4 = goal reached), oldest first; the last cell is today. */
    val levels: List<Int>,
)

internal object StatsModels {
    fun daily(rows: List<SessionEntity>, goalMs: Long, today: LocalDate, zone: ZoneId): DailyModel {
        val perDay = StatsCalculator.perDay(rows, zone)
        val focused = perDay[today] ?: 0L
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DailyModel(
            focusedMs = focused,
            goalMs = goalMs,
            percent = progressPercent(focused, goalMs),
            sessions = rows.count { StatsCalculator.dateOf(it.startedAt, zone) == today },
            streak = StatsCalculator.streak(perDay, today),
            week = (0 until 7).map { perDay[monday.plusDays(it.toLong())] ?: 0L },
            todayIndex = today.dayOfWeek.value - 1,
        )
    }

    fun streak(perDay: Map<LocalDate, Long>, goalMs: Long, today: LocalDate): StreakModel = StreakModel(
        current = StatsCalculator.streak(perDay, today),
        best = maxOf(bestStreak(perDay), StatsCalculator.streak(perDay, today)),
        levels = (27 downTo 0).map { heatLevel(perDay[today.minusDays(it.toLong())] ?: 0L, goalMs) },
    )

    /** Longest run of consecutive days with at least [StatsCalculator.STREAK_MIN_MS] of focus. */
    fun bestStreak(perDay: Map<LocalDate, Long>): Int {
        val days = perDay.filterValues { it >= StatsCalculator.STREAK_MIN_MS }.keys.sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    /**
     * 0 below the streak minimum; then 1..4 by share of the daily goal (under 1/3, under 2/3, under the goal,
     * goal reached). Without a goal, every streak day is level 3.
     */
    fun heatLevel(focusMs: Long, goalMs: Long): Int = when {
        focusMs < StatsCalculator.STREAK_MIN_MS -> 0
        goalMs <= 0L -> 3
        focusMs >= goalMs -> 4
        focusMs * 3 >= goalMs * 2 -> 3
        focusMs * 3 >= goalMs -> 2
        else -> 1
    }
}

// ---------------------------------------------------------------------------------------------- tasks

internal data class TaskItem(val id: Long, val title: String, val done: Boolean)
internal data class TasksModel(val items: List<TaskItem>, val done: Int, val total: Int)

internal object TaskModels {
    const val MAX_ROWS = 4

    /**
     * Today's tasks: everything due today (ticked or not) plus open tasks that are overdue or have no date.
     * Oldest first, so a row keeps its place when it is ticked from the widget.
     */
    fun today(all: List<TaskEntity>, today: LocalDate, zone: ZoneId): TasksModel {
        val list = all.filter { t ->
            val due = t.deadline?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            when {
                due == today -> true
                t.done -> false
                due == null -> true
                else -> due.isBefore(today)
            }
        }.sortedWith(compareBy<TaskEntity> { it.createdAt }.thenBy { it.id })
        return TasksModel(list.take(MAX_ROWS).map { TaskItem(it.id, it.title, it.done) }, list.count { it.done }, list.size)
    }
}

// ---------------------------------------------------------------------------------------------- exams & syllabus

internal data class ExamModel(
    val name: String,
    val daysLeft: Long,
    val dateLine: String,
    /** Null when no bundled syllabus matches the exam's name. */
    val syllabusPercent: Int?,
    val chaptersDone: Int,
    val chapters: Int,
)

internal data class SyllabusRing(val label: String, val percent: Int)
internal data class SyllabusModel(val examChip: String, val rings: List<SyllabusRing>, val upNext: String?)

internal object SyllabusModels {
    fun progressOf(progress: Map<String, ChapterProgress>, subject: String, chapter: String): ChapterProgress {
        val key = SyllabusKeys.chapterKey(subject, chapter)
        return progress[key] ?: ChapterProgress.empty(key)
    }

    fun exam(exams: List<Exam>, today: LocalDate, syllabus: Syllabus, progress: Map<String, ChapterProgress>): ExamModel? {
        val exam = ExamCountdown.main(exams, today) ?: return null
        val match = syllabus.exams.firstOrNull { it.matches(exam.name) }
        val chapters = match?.subjects?.flatMap { s -> s.chapters.map { progressOf(progress, s.name, it) } }.orEmpty()
        val stats = SyllabusMath.stats(chapters)
        return ExamModel(
            name = exam.name,
            daysLeft = ExamCountdown.daysLeft(exam, today),
            dateLine = ExamCountdown.shortDate(exam.date, today),
            syllabusPercent = if (match == null || stats.chapters == 0) null else Math.round(stats.done * 100f / stats.chapters),
            chaptersDone = stats.done,
            chapters = stats.chapters,
        )
    }

    fun shortSubject(name: String): String = when (name.lowercase(Locale.ROOT)) {
        "physics" -> "Phy"
        "chemistry" -> "Chem"
        "mathematics", "maths", "math" -> "Maths"
        "biology" -> "Bio"
        else -> if (name.length <= 6) name else name.take(5)
    }

    fun rings(exam: SyllabusExam, progress: Map<String, ChapterProgress>, examDate: LocalDate?, zone: ZoneId): SyllabusModel {
        val rings = exam.subjects.take(4).map { s ->
            SyllabusRing(shortSubject(s.name), SyllabusMath.percent(SyllabusMath.stats(s.chapters) { progressOf(progress, s.name, it) }.fraction))
        }
        // Up next across the whole exam: chapters are addressed by their progress key so subjects never collide.
        val names = LinkedHashMap<String, String>()
        exam.subjects.forEach { s -> s.chapters.forEach { names[SyllabusKeys.chapterKey(s.name, it)] = it } }
        val next = SyllabusMath.upNext(names.keys.toList(), { key -> progress[key] ?: ChapterProgress.empty(key) }, examDate, zone)
        val upNext = next?.let { n ->
            val chapter = names[n.chapter] ?: n.chapter
            if (n.step == SyllabusStep.DONE) "Start $chapter" else "${n.step.upNext} $chapter"
        }
        return SyllabusModel(exam.chip, rings, upNext)
    }
}

// ---------------------------------------------------------------------------------------------- group

/** Bar length for a member's live minutes: a full bar is an hour. */
internal fun memberBarFraction(minutes: Long): Float = (minutes.coerceAtLeast(0L) / 60f).coerceIn(0.04f, 1f)
