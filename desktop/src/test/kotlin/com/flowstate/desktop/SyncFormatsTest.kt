package com.flowstate.desktop

import com.flowstate.desktop.cloud.FsDoc
import com.flowstate.desktop.cloud.RemoteTimer
import com.flowstate.desktop.cloud.decodeActiveTimer
import com.flowstate.desktop.cloud.encodeActiveTimer
import com.flowstate.desktop.cloud.fromFs
import com.flowstate.desktop.cloud.fsValue
import com.flowstate.desktop.cloud.isStale
import com.flowstate.desktop.store.SessionStore
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncFormatsTest {
    init {
        System.setProperty("flowstate.home", File(System.getProperty("java.io.tmpdir"), "flowstate-test-" + System.nanoTime()).path)
    }

    private val running = TimerState(
        mode = TimerMode.POMODORO, phase = Phase.FOCUS, status = Status.RUNNING, phaseDurationMs = 25 * 60_000L,
        phaseStartedAt = System.currentTimeMillis() - 5 * 60_000L, subject = "Physics", round = 2, plan = Plan(rounds = 4),
    )

    @Test fun activeTimerRoundTripsThroughFirestoreValues() {
        val fields = encodeActiveTimer(running, "win-1", "DESKTOP", "windows")
        // Through the REST wire format and back, as the phone would write and the PC read it.
        val wire = fields.mapValues { fromFs(fsValue(it.value)) } + ("updatedAt" to Instant.ofEpochMilli(1_000L))
        val remote = decodeActiveTimer(FsDoc("projects/p/databases/(default)/documents/users/u/state/activeTimer", wire, null))!!
        assertEquals("win-1", remote.deviceId)
        assertEquals(running.phase, remote.state.phase)
        assertEquals(running.phaseStartedAt, remote.state.phaseStartedAt)
        assertEquals(running.phaseDurationMs, remote.state.phaseDurationMs)
        assertEquals(running.round, remote.state.round)
        assertEquals("Physics", remote.state.subject)
        assertEquals(1_000L, remote.updatedAt)
    }

    @Test fun staleLocksAreIgnored() {
        val now = System.currentTimeMillis()
        assertFalse(RemoteTimer("a", "", "android", running, now).isStale(now))
        val ended = running.copy(phaseStartedAt = now - 60 * 60_000L)
        assertTrue(RemoteTimer("a", "", "android", ended, now).isStale(now))
        assertTrue(RemoteTimer("a", "", "android", TimerState(), now).isStale(now))
    }

    @Test fun pulledSessionsAreDeduplicatedByCloudId() {
        val store = SessionStore("sessions-test.json")
        val row = SessionEntity(startedAt = 1L, durationMs = 60_000L, mode = "POMODORO", subject = "", completed = true, cloudId = "android-1_1", ownerUid = "u", origin = "android-1")
        assertEquals(1, store.insertRemote(listOf(row)))
        assertEquals(0, store.insertRemote(listOf(row, row)))
        assertEquals(1, store.all.value.size)
        store.deleteByCloudId("android-1_1")
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun versionComparison() {
        assertTrue(UpdateCheck.isNewer("1.6.1", "1.6.0"))
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateCheck.isNewer("1.6.0", "1.6.0"))
        assertNull(null)
    }
}
