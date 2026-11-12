package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.1 "some sessions have somehow disappeared": the shared pull plan and the handoff rule. */
class SessionSyncTest {
    private val me = "android-me"
    private val pc = "win-pc"

    private fun doc(id: String, device: String, startedAt: Long? = 1_000L, deleted: Boolean = false) =
        CloudSessionDoc(id, deleted, device, startedAt, 1_500_000L, "POMODORO", "Physics", true)

    @Test fun ownSessionMissingLocallyIsRestoredFromTheCloud() {
        // Until 1.8.0 every document with this device's id was skipped, so a lost local history never came back.
        val plan = SessionSyncPlan.plan(listOf(doc("${me}_1000", me)), "u", me, knownCloudIds = emptySet(), unsyncedOwnStarts = emptySet())
        assertEquals(1, plan.add.size)
        val row = plan.add.single()
        assertEquals("${me}_1000", row.cloudId)
        assertEquals(me, row.origin)
        assertEquals("u", row.ownerUid)
    }

    @Test fun ownSessionStillStoredOrWaitingForUploadIsNotDuplicated() {
        val docs = listOf(doc("${me}_1000", me, 1_000L), doc("${me}_2000", me, 2_000L))
        val plan = SessionSyncPlan.plan(docs, "u", me, knownCloudIds = setOf("${me}_1000"), unsyncedOwnStarts = setOf(2_000L))
        assertTrue(plan.add.isEmpty())
    }

    @Test fun otherDevicesSessionsAreAddedOnceAndRepeatsAreHarmless() {
        val docs = listOf(doc("${pc}_1", pc, 1L), doc("${pc}_1", pc, 1L), doc("${pc}_2", pc, 2L))
        val first = SessionSyncPlan.plan(docs, "u", me, emptySet(), emptySet())
        assertEquals(listOf("${pc}_1", "${pc}_2"), first.add.map { it.cloudId })
        val again = SessionSyncPlan.plan(docs, "u", me, first.add.mapNotNull { it.cloudId }.toSet(), emptySet())
        assertTrue(again.add.isEmpty())
    }

    @Test fun onlyAnExplicitTombstoneDeletesAndAbsenceNeverDoes() {
        // A partial page, an empty page or a failed upload is just "no documents": nothing may be deleted.
        assertTrue(SessionSyncPlan.plan(emptyList(), "u", me, setOf("a", "b"), emptySet()).deleteCloudIds.isEmpty())
        val plan = SessionSyncPlan.plan(listOf(doc("${pc}_9", pc, deleted = true), doc("${pc}_8", pc)), "u", me, setOf("${pc}_9", "x"), emptySet())
        assertEquals(setOf("${pc}_9"), plan.deleteCloudIds)
        assertEquals(listOf("${pc}_8"), plan.add.map { it.cloudId })
    }

    @Test fun aSessionDeletedHereIsNotResurrectedBeforeItsTombstoneLands() {
        val plan = SessionSyncPlan.plan(listOf(doc("${pc}_5", pc)), "u", me, emptySet(), emptySet(), pendingTombstones = setOf("${pc}_5"))
        assertTrue(plan.add.isEmpty())
    }

    @Test fun malformedDocumentsAreSkipped() {
        val plan = SessionSyncPlan.plan(listOf(doc("", pc), doc("${pc}_0", pc, startedAt = null), doc("${pc}_n", pc, startedAt = -5L)), "u", me, emptySet(), emptySet())
        assertTrue(plan.add.isEmpty())
    }

    @Test fun cloudIdsAreUniquePerRow() {
        val taken = mutableSetOf<String>()
        val a = SessionSyncPlan.cloudIdFor(me, 1_000L, 1L, taken).also { taken += it }
        val b = SessionSyncPlan.cloudIdFor(me, 1_000L, 2L, taken).also { taken += it }
        assertEquals("${me}_1000", a)
        assertEquals("${me}_1000_2", b)
        assertTrue(b.length <= 128)
    }

    // ---- handoff ----

    private val now = 10_000_000_000L
    private val block = TimerState(TimerMode.POMODORO, Phase.FOCUS, Status.RUNNING, 25 * 60_000L, now - 10 * 60_000L, subject = "Maths", round = 1)

    @Test fun takeOverOfTheSameBlockRecordsNothingHere() {
        assertNull(HandoffPolicy.recordOnRelease(block, block, now))
        // The other device paused and resumed (start pushed later) or moved on to the break.
        assertNull(HandoffPolicy.recordOnRelease(block, block.copy(phaseStartedAt = block.phaseStartedAt + 60_000L), now))
        assertNull(HandoffPolicy.recordOnRelease(block, block.copy(phase = Phase.SHORT_BREAK, round = 2), now))
        // Ended elsewhere: ambiguous, kept as before (the other device recorded it).
        assertNull(HandoffPolicy.recordOnRelease(block, TimerState(), now))
    }

    @Test fun aDifferentBlockElsewhereRecordsTheTimeSpentHere() {
        val other = block.copy(subject = "Chemistry", phaseStartedAt = now - 60_000L)
        assertEquals(10 * 60_000L to false, HandoffPolicy.recordOnRelease(block, other, now))
        assertEquals(10 * 60_000L to false, HandoffPolicy.recordOnRelease(block, block.copy(mode = TimerMode.COUNTDOWN), now))
        assertEquals(10 * 60_000L to false, HandoffPolicy.recordOnRelease(block, block.copy(round = 0), now))
        assertEquals(10 * 60_000L to false, HandoffPolicy.recordOnRelease(block, block.copy(phaseStartedAt = block.phaseStartedAt - 1), now))
    }

    @Test fun aPauseLeftOvernightIsRecordedWhenAnotherDeviceStartsFresh() {
        val paused = block.copy(status = Status.PAUSED, pausedAt = now - 13 * 60 * 60_000L, phaseStartedAt = now - 13 * 60 * 60_000L - 40 * 60_000L,
            phaseDurationMs = 60 * 60_000L)
        assertTrue(HandoffPolicy.isStale(paused, now))
        assertEquals(40 * 60_000L to false, HandoffPolicy.recordOnRelease(paused, block, now))
        assertEquals(40 * 60_000L to false, HandoffPolicy.recordOnRelease(paused, TimerState(), now))
    }

    @Test fun anEndedCountdownIsRecordedInFullAndBreaksNever() {
        val ended = block.copy(phaseStartedAt = now - 60 * 60_000L)
        assertEquals(25 * 60_000L to true, HandoffPolicy.recordOnRelease(ended, block.copy(subject = "x"), now))
        assertNull(HandoffPolicy.recordOnRelease(block.copy(phase = Phase.SHORT_BREAK), block.copy(subject = "x"), now))
        assertFalse(HandoffPolicy.isContinuation(block, block.copy(subject = "x"), now))
    }
}
