package com.flowstate.focus.widgets

import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataTest {
    @Test fun progressIsClampedAndZeroGoalIsSafe() {
        assertEquals(0, progressPercent(30_000L, 0L))
        assertEquals(50, progressPercent(30L * 60_000L, 60L * 60_000L))
        assertEquals(100, progressPercent(90L * 60_000L, 60L * 60_000L))
        assertEquals(0, progressPercent(-1L, 60_000L))
    }

    @Test fun focusDurationUsesHoursAndMinutesWithoutRoundingUp() {
        assertEquals("0m", formatFocusDuration(0L))
        assertEquals("59m", formatFocusDuration(59L * 60_000L + 59_999L))
        assertEquals("2h", formatFocusDuration(120L * 60_000L))
        assertEquals("2h 15m", formatGoalDuration(135L * 60_000L))
    }

    @Test fun onlyRecentRunningFocusPresenceIsCounted() {
        val now = 1_800_000_000_000L
        assertTrue(isFreshlyFocusing(member(now, Status.RUNNING, Phase.FOCUS), now))
        assertFalse(isFreshlyFocusing(member(now - 121_000L, Status.RUNNING, Phase.FOCUS), now))
        assertFalse(isFreshlyFocusing(member(now, Status.PAUSED, Phase.FOCUS), now))
        assertFalse(isFreshlyFocusing(member(now, Status.RUNNING, Phase.SHORT_BREAK), now))
    }

    private fun member(updatedAt: Long, status: Status, phase: Phase) = GroupMember(
        uid = "member",
        name = "Member",
        weeklyFocusMs = 0L,
        live = TimerState(status = status, phase = phase, phaseStartedAt = updatedAt),
        updatedAt = updatedAt,
    )
}
