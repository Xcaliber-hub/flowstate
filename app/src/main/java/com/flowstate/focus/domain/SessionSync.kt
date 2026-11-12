package com.flowstate.focus.domain

import com.flowstate.focus.data.db.SessionEntity

/*
 * 1.8.1: the session-sync decisions shared by Android (CloudSync) and Windows (CloudController), kept free of
 * Firebase and Room so both platforms make exactly the same call and it can be unit-tested.
 *
 * Background ("some sessions have somehow disappeared"): until 1.8.0 a device skipped every cloud session
 * whose deviceId was its own, assuming it still had it locally. A history that was lost locally (an app-data
 * restore from an older backup, a reinstall with the same id, a desktop sessions.json overwritten by a second
 * copy of the app) could therefore never come back from the cloud, although every session was still there.
 */

/** One users/{uid}/sessions/{id} document, already decoded. */
data class CloudSessionDoc(
    val id: String,
    val deleted: Boolean,
    val deviceId: String,
    val startedAt: Long?,
    val durationMs: Long,
    val mode: String,
    val subject: String,
    val completed: Boolean,
)

/** What to do with a page of pulled documents. [add] rows have id 0 (the store assigns ids). */
data class PullPlan(val add: List<SessionEntity>, val deleteCloudIds: Set<String>)

object SessionSyncPlan {
    /**
     * Decides which pulled documents become local rows and which local rows a tombstone removes.
     *
     * - A tombstone (`deleted == true`) removes the row with that cloud id: someone deleted it on purpose.
     * - A cloud id this device already stores is skipped, so pulling the same page again is harmless.
     * - A cloud id this device deleted itself and whose tombstone has not reached the cloud yet
     *   ([pendingTombstones]) is skipped, so a deleted session is not resurrected.
     * - This device's own session is restored when it is missing locally (origin = [myDeviceId]), unless a
     *   local row with the same start time is still waiting to be uploaded under that id.
     * - Nothing here ever deletes a row because it is absent from the cloud.
     */
    fun plan(
        docs: List<CloudSessionDoc>,
        uid: String,
        myDeviceId: String,
        knownCloudIds: Set<String>,
        unsyncedOwnStarts: Set<Long>,
        pendingTombstones: Set<String> = emptySet(),
    ): PullPlan {
        val add = ArrayList<SessionEntity>()
        val deleted = LinkedHashSet<String>()
        val seen = HashSet<String>(knownCloudIds)
        for (d in docs) {
            if (d.id.isBlank()) continue
            if (d.deleted) { deleted += d.id; continue }
            if (d.id in pendingTombstones) continue
            val startedAt = d.startedAt?.takeIf { it > 0L } ?: continue
            if (!seen.add(d.id)) continue
            val origin = d.deviceId.ifBlank { "unknown" }
            if (origin == myDeviceId && startedAt in unsyncedOwnStarts) continue
            add += SessionEntity(
                startedAt = startedAt,
                durationMs = d.durationMs.coerceIn(0L, 86_400_000L),
                mode = d.mode.ifBlank { "POMODORO" },
                subject = d.subject,
                completed = d.completed,
                cloudId = d.id,
                ownerUid = uid,
                origin = origin,
            )
        }
        // A document that is both added and tombstoned in one page: the tombstone wins.
        return PullPlan(add.filterNot { it.cloudId in deleted }, deleted)
    }

    /**
     * The cloud id for a local row: "<deviceId>_<startedAt>", or "<deviceId>_<startedAt>_<rowId>" when that id
     * is already used by another row. Two rows with the same start time used to share one cloud document (the
     * second overwrote the first in the cloud), and on Android the unique index made marking the second one
     * throw, which stopped every later upload.
     */
    fun cloudIdFor(deviceId: String, startedAt: Long, rowId: Long, taken: Set<String>): String {
        val base = "${deviceId}_$startedAt"
        return if (base !in taken) base else "${base}_$rowId"
    }
}

/**
 * 1.8.1: whether a session stopped here because another device claimed the timer is the same block
 * continuing there (recorded once, by the device that finishes it) or a different block (so this one must
 * be recorded here, or it is lost).
 */
object HandoffPolicy {
    private const val RUNNING_STALE_MS = 10 * 60_000L
    private const val PAUSED_STALE_MS = 12 * 60 * 60_000L

    /** Other devices ignore a lock this old (RemoteTimer.isStale), so they cannot have taken this block over. */
    fun isStale(local: TimerState, now: Long): Boolean = when (local.status) {
        Status.IDLE -> true
        Status.RUNNING -> !local.countsUp && now - local.endAt > RUNNING_STALE_MS
        Status.PAUSED -> local.pausedAt > 0L && now - local.pausedAt > PAUSED_STALE_MS
    }

    /**
     * True when [remote] can be this device's block carried on elsewhere. "Take over here" copies the state
     * exactly (mode, subject, plan, round, phase start); afterwards the other device can only move forward
     * (pauses push the start later, phases advance the round). Anything else is a different block.
     * An idle [remote] (ended elsewhere) is ambiguous and keeps the old behaviour, unless this block was too
     * old for anyone to take over.
     */
    fun isContinuation(local: TimerState, remote: TimerState?, now: Long): Boolean {
        if (isStale(local, now)) return false
        if (remote == null || remote.status == Status.IDLE) return true
        if (remote.mode != local.mode || remote.subject != local.subject || remote.plan != local.plan) return false
        if (remote.round < local.round) return false
        if (remote.round == local.round && remote.phase == local.phase && remote.phaseStartedAt < local.phaseStartedAt) return false
        return true
    }

    /** The focus time to record for a block that is not continuing elsewhere: (duration, completed), or null. */
    fun recordOnRelease(local: TimerState, remote: TimerState?, now: Long): Pair<Long, Boolean>? {
        if (local.phase != Phase.FOCUS || !local.isActive) return null
        if (isContinuation(local, remote, now)) return null
        return if (local.status == Status.RUNNING && !local.countsUp && now >= local.endAt) local.phaseDurationMs to true
        else local.elapsedAt(now) to local.countsUp
    }
}
