package com.flowstate.focus.ui.focus

import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerBarTextTest {
    private val focus = TimerState(mode = TimerMode.POMODORO, phase = Phase.FOCUS, status = Status.RUNNING, plan = Plan(rounds = 4))

    @Test fun subjectIsTitleAndPhaseMovesBelow() {
        val s = focus.copy(subject = "Physics", round = 1)
        assertEquals("Physics", timerBarTitle(s))
        assertEquals("Focus · Round 2 of 4", timerBarSubtitle(s))
    }

    @Test fun noSubjectShowsPhaseAsTitle() {
        assertEquals("Focus", timerBarTitle(focus))
        assertEquals("Round 1 of 4", timerBarSubtitle(focus))
    }

    @Test fun breakCountsTheRoundJustFinished() {
        val s = focus.copy(phase = Phase.SHORT_BREAK, round = 1)
        assertEquals("Short break", timerBarTitle(s))
        assertEquals("Round 1 of 4", timerBarSubtitle(s))
    }

    @Test fun pausedIsSaidFirst() {
        val s = focus.copy(status = Status.PAUSED, subject = "Maths")
        assertEquals("Paused · Focus · Round 1 of 4", timerBarSubtitle(s))
    }

    @Test fun stopwatchAndStandaloneBreakHaveNoRounds() {
        assertEquals("Stopwatch", timerBarSubtitle(TimerState(mode = TimerMode.STOPWATCH, phase = Phase.FOCUS, status = Status.RUNNING)))
        val brk = TimerState(mode = TimerMode.POMODORO, phase = Phase.SHORT_BREAK, status = Status.RUNNING, standaloneBreak = true)
        assertEquals("Break", timerBarTitle(brk))
        assertEquals("Time left", timerBarSubtitle(brk))
    }
}
