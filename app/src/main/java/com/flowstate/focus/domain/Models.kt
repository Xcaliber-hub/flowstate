package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable

enum class TimerMode(val label: String) {
    POMODORO("Pomodoro"),
    COUNTDOWN("Countdown"),
    STOPWATCH("Stopwatch"),
    FLOW("Flow");

    /** Stopwatch and Flow count up with no fixed end. */
    val countsUp: Boolean get() = this == STOPWATCH || this == FLOW
}

enum class Phase { IDLE, FOCUS, SHORT_BREAK, LONG_BREAK }

enum class Status { IDLE, RUNNING, PAUSED }

/** Things the user should be told about even when the app is in the background. */
enum class TimerEvent { FOCUS_DONE_BREAK_NEXT, BREAK_DONE_FOCUS_NEXT, SESSION_DONE }

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
    /** Zero means the phase counts up (stopwatch / flow focus). */
    val phaseDurationMs: Long = 0L,
    val phaseStartedAt: Long = 0L,
    val pausedAt: Long = 0L,
    val strict: Boolean = false,
    val subject: String = "",
    val round: Int = 0,
    val plan: Plan = Plan(),
) {
    val isActive: Boolean get() = status != Status.IDLE
    val isFocusRunning: Boolean get() = phase == Phase.FOCUS && status == Status.RUNNING
    val countsUp: Boolean get() = phaseDurationMs == 0L
    val endAt: Long get() = phaseStartedAt + phaseDurationMs

    fun elapsedAt(now: Long): Long =
        ((if (status == Status.PAUSED) pausedAt else now) - phaseStartedAt).coerceAtLeast(0L)

    fun remainingAt(now: Long): Long = (phaseDurationMs - elapsedAt(now)).coerceAtLeast(0L)
}
