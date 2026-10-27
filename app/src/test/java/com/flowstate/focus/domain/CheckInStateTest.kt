package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInStateTest {
    @Test fun checkInOnlyWaitsWhileRunning() {
        val running = TimerState(phase = Phase.FOCUS, status = Status.RUNNING, checkInAt = 5_000)
        assertTrue(running.awaitingCheckIn)
        assertFalse(running.copy(status = Status.PAUSED).awaitingCheckIn)
        assertFalse(running.copy(checkInAt = 0L).awaitingCheckIn)
    }

    @Test fun autoPauseAtPromptTimeDropsUnansweredMinutes() {
        // Stopwatch started at t=0, check-in shown at 60 min, nobody answered for 2 min.
        val prompt = 60 * 60_000L
        val paused = TimerState(mode = TimerMode.STOPWATCH, phase = Phase.FOCUS, status = Status.PAUSED,
            phaseStartedAt = 0L, pausedAt = prompt, autoPaused = true)
        assertEquals(prompt, paused.elapsedAt(prompt + 2 * 60_000L))
    }
}
