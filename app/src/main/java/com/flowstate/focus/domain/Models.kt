package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable

enum class TimerMode(val label: String) {
    POMODORO("Pomodoro"),
    COUNTDOWN("Countdown"),
    STOPWATCH("Stopwatch");

    /** Stopwatch counts up with no fixed end. */
    val countsUp: Boolean get() = this == STOPWATCH

    companion object {
        /** The retired open-ended "Flow" mode, still found in old sessions and saved timer state. */
        const val LEGACY_FLOW = "FLOW"

        /**
         * Reads a stored mode name. The retired Flow mode counted up exactly like the stopwatch, so a
         * Flow block that was running during the upgrade resumes as a stopwatch; anything unknown
         * falls back to the default.
         */
        fun fromName(name: String?): TimerMode = when (name) {
            null -> POMODORO
            LEGACY_FLOW -> STOPWATCH
            else -> TimerMode.entries.firstOrNull { it.name == name } ?: POMODORO
        }

        /** Display label for a stored mode name. Old sessions recorded in Flow mode keep their label. */
        fun labelFor(name: String): String =
            if (name == LEGACY_FLOW) "Flow" else TimerMode.entries.firstOrNull { it.name == name }?.label ?: name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

enum class Phase { IDLE, FOCUS, SHORT_BREAK, LONG_BREAK }

enum class Status { IDLE, RUNNING, PAUSED }

/** Things the user should be told about even when the app is in the background. */
enum class TimerEvent { FOCUS_DONE_BREAK_NEXT, BREAK_DONE_FOCUS_NEXT, SESSION_DONE, BREAK_DONE }

/** Lengths for a Pomodoro cycle. Countdown sessions only use [focusMs]. */
@Immutable
data class Plan(
    val focusMs: Long = 25 * 60_000L,
    val shortBreakMs: Long = 5 * 60_000L,
    val longBreakMs: Long = 15 * 60_000L,
    val rounds: Int = 4,
)

/**
 * Immutable snapshot of the timer.
 *
 * Time is never counted by incrementing a number. [phaseStartedAt] is an absolute wall-clock
 * time, so the remaining time is always recomputed from "now". That is what keeps the timer
 * correct after the screen turns off, the process is killed, or the UI is rebuilt.
 */
@Immutable
data class TimerState(
    val mode: TimerMode = TimerMode.POMODORO,
    val phase: Phase = Phase.IDLE,
    val status: Status = Status.IDLE,
    /** Zero means the phase counts up (stopwatch focus). */
    val phaseDurationMs: Long = 0L,
    val phaseStartedAt: Long = 0L,
    val pausedAt: Long = 0L,
    val strict: Boolean = false,
    val subject: String = "",
    val round: Int = 0,
    val plan: Plan = Plan(),
    /** A break the user started on its own ("Take a break"), not one inside a Pomodoro cycle. */
    val standaloneBreak: Boolean = false,
    /** When the current "Still focusing?" check-in was shown, or 0 when none is waiting. */
    val checkInAt: Long = 0L,
    /** True when the timer was paused automatically because a check-in went unanswered. */
    val autoPaused: Boolean = false,
) {
    val isActive: Boolean get() = status != Status.IDLE
    /** A focus block is running or paused. While true, no second session may be started. */
    val isFocusInProgress: Boolean get() = isActive && phase == Phase.FOCUS
    val isBreak: Boolean get() = isActive && (phase == Phase.SHORT_BREAK || phase == Phase.LONG_BREAK)
    val isFocusRunning: Boolean get() = phase == Phase.FOCUS && status == Status.RUNNING
    val countsUp: Boolean get() = phaseDurationMs == 0L
    val endAt: Long get() = phaseStartedAt + phaseDurationMs

    fun elapsedAt(now: Long): Long =
        ((if (status == Status.PAUSED) pausedAt else now) - phaseStartedAt).coerceAtLeast(0L)

    /** A check-in is on screen and waiting for "I'm here". */
    val awaitingCheckIn: Boolean get() = checkInAt > 0L && status == Status.RUNNING

    fun remainingAt(now: Long): Long = (phaseDurationMs - elapsedAt(now)).coerceAtLeast(0L)
}
