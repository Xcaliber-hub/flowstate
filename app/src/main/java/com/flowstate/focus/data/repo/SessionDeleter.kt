package com.flowstate.focus.data.repo

import com.flowstate.focus.data.sync.CloudSync
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes a recorded session the one way every screen does it (1.7.2: shared by the Focus screen's
 * "Today's sessions" and Settings › This week's sessions): removed from Room, so history, stats and
 * streaks recompute from the flows, and a synced session is marked deleted in the cloud so the
 * account's other devices drop it too.
 */
@Singleton
class SessionDeleter @Inject constructor(
    private val sessions: SessionRepository,
    private val cloudSync: CloudSync,
) {
    suspend fun delete(id: Long) {
        val row = sessions.byId(id)
        sessions.delete(id)
        if (row != null) cloudSync.onSessionDeleted(row)
    }
}
