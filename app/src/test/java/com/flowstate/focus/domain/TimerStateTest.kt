package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStateTest {
    @Test fun runningCountdownUsesAbsoluteTimestamps() {
        val timer = TimerState(phase = Phase.FOCUS, status = Status.RUNNING,
            phaseStartedAt = 1_000, phaseDurationMs = 25 * 60_000)
        assertEquals(65_000L, timer.elapsedAt(66_000))
        assertEquals(1_435_000L, timer.remainingAt(66_000))
        assertTrue(timer.isFocusRunning)
    }

    @Test fun pausedTimerNeverKeepsCounting() {
        val timer = TimerState(phase = Phase.FOCUS, status = Status.PAUSED,
            phaseStartedAt = 1_000, phaseDurationMs = 60_000, pausedAt = 21_000)
        assertEquals(20_000L, timer.elapsedAt(900_000))
        assertEquals(40_000L, timer.remainingAt(900_000))
        assertFalse(timer.isFocusRunning)
    }

    @Test fun elapsedAndRemainingNeverGoNegative() {
        val timer = TimerState(status = Status.RUNNING, phaseStartedAt = 5_000, phaseDurationMs = 1_000)
        assertEquals(0L, timer.elapsedAt(4_000))
        assertEquals(0L, timer.remainingAt(100_000))
    }

    @Test fun breaksAreNotActiveFocusAndStopwatchCountsUp() {
        val rest = TimerState(phase = Phase.SHORT_BREAK, status = Status.RUNNING, phaseDurationMs = 300_000)
        assertFalse(rest.isFocusRunning)
        val stopwatch = TimerState(mode = TimerMode.STOPWATCH, phase = Phase.FOCUS,
            status = Status.RUNNING, phaseStartedAt = 1_000)
        assertTrue(stopwatch.countsUp)
        assertEquals(3_600_000L, stopwatch.elapsedAt(3_601_000))
    }
}
