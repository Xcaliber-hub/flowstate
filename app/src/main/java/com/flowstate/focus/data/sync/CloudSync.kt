package com.flowstate.focus.data.sync

import android.content.Context
import android.util.Log
import com.flowstate.focus.data.db.SessionDao
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.6.0: keeps the account's devices in step, on the free Spark plan (no Cloud Functions).
 *
 * One-device timer: users/{uid}/state/activeTimer names the device running the timer. Starting or
 * resuming here claims it; pausing updates it; stopping releases it. When another device claims it
 * (its "Take over here"), this phone stops its copy without recording it (the other device continues
 * the same block and records it once) and the Focus screen shows "Running on your PC".
 *
 * Synced stats: every session recorded while signed in is uploaded to users/{uid}/sessions/{deviceId}_{startedAt};
 * sessions from the account's other devices are pulled into Room (deduplicated by cloudId), so history,
 * streaks, the daily goal and group totals use the merged history. Deleting a session marks it
 * deleted, and every device drops it.
 *
 * Signed out, none of this runs and the app behaves exactly as before.
 */
@OptIn(FlowPreview::class)
@Singleton
class CloudSync @Inject constructor(
    @ApplicationContext context: Context,
    private val groups: GroupsRepository,
    private val engine: TimerEngine,
    private val dao: SessionDao,
    private val device: DeviceIdentity,
    private val lock: TimerLockState,
    private val account: AccountHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    // Pre-1.6 ownership map written by GroupsRepository: owner_<sessionId> = uid or "unassigned".
    private val ownership = context.getSharedPreferences("group_session_owners", Context.MODE_PRIVATE)
    private val lockMutex = Mutex()
    private val uploadMutex = Mutex()
    private var lockListener: ListenerRegistration? = null
    private var sessionsListener: ListenerRegistration? = null
    @Volatile private var uid: String? = null
    @Volatile private var holdingLock = false
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        // Before Firebase signs out, give the timer back so the PC isn't left showing "Running on your phone".
        groups.addSignOutHook { oldUid -> releaseOnSignOut(oldUid) }
        scope.launch {
            var previous: String? = null
            groups.state.map { it.user?.uid }.distinctUntilChanged().collect { next ->
                stopListeners()
                uid = next
                if (next == null) {
                    lock.remote.value = null
                    holdingLock = false
                    if (previous != null) onSignedOut(previous!!)
                } else onSignedIn(next)
                previous = next
            }
        }
        scope.launch {
            engine.state.map { LockKey(it) }.distinctUntilChanged().drop(1).collect {
                runCatching { onLocalTimerChanged() }.onFailure { Log.w(TAG, "Timer lock update failed", it) }
            }
        }
        scope.launch {
            dao.since(0L).debounce(1_500).collect { uid?.let { u -> runCatching { uploadPending(u) }.onFailure { Log.w(TAG, "Session upload failed", it) } } }
        }
        // Retry uploads that failed while offline or before the rules were published.
        scope.launch { while (isActive) { delay(5 * 60_000L); uid?.let { u -> runCatching { uploadPending(u) } } } }
        // A holder that vanished mid-session (its countdown ended long ago) stops locking this phone.
        scope.launch {
            while (isActive) {
                delay(30_000L)
                lock.remote.value?.let { if (it.isStale(System.currentTimeMillis())) lock.remote.value = null }
            }
        }
    }

    private fun db(): FirebaseFirestore? = groups.firestore

    // ---------------------------------------------------------------- sign-in / sign-out

    private suspend fun onSignedIn(u: String) {
        val db = db() ?: return
        runCatching { backfillOnce(u) }.onFailure { Log.w(TAG, "Backfill failed", it) }
        var first = true
        lockListener = db.collection("users").document(u).collection("state").document("activeTimer")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (uid != u) return@addSnapshotListener
                if (err != null) { Log.w(TAG, "Timer lock listener failed", err); return@addSnapshotListener }
                if (snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                val fromServer = !snap.metadata.isFromCache
                val initial = first && fromServer
                if (fromServer) first = false
                scope.launch { handleLock(u, snap, fromServer, initial) }
            }
        val since = prefs.getLong(lastPullKey(u), 0L)
        sessionsListener = db.collection("users").document(u).collection("sessions")
            .whereGreaterThan("updatedAt", Timestamp(Date(since))).orderBy("updatedAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (uid != u) return@addSnapshotListener
                if (err != null) { Log.w(TAG, "Session sync listener failed", err); return@addSnapshotListener }
                val changes = snap?.documentChanges.orEmpty().filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }
                if (changes.isEmpty()) return@addSnapshotListener
                scope.launch { applyPulled(u, changes.map { it.document }) }
            }
    }

    private suspend fun onSignedOut(oldUid: String) {
        // Sessions pulled from the other devices belong to that account, not to this phone.
        val me = device.deviceId()
        dao.deletePulled(me)
        prefs.edit().remove(lastPullKey(oldUid)).apply()
    }

    private suspend fun releaseOnSignOut(oldUid: String) {
        if (!holdingLock) return
        val db = db() ?: return
        // Awaited briefly: once signed out, a queued write would wait for this account to sign in again.
        runCatching {
            withTimeoutOrNull(4_000L) {
                db.collection("users").document(oldUid).collection("state").document("activeTimer")
                    .set(encodeActiveTimer(TimerState(), device.deviceId(), device.deviceName, PLATFORM_ANDROID) + ("updatedAt" to FieldValue.serverTimestamp()))
                    .await()
            }
        }
        holdingLock = false
    }

    private fun stopListeners() {
        lockListener?.remove(); lockListener = null
        sessionsListener?.remove(); sessionsListener = null
    }

    // ---------------------------------------------------------------- one-device timer lock

    private data class LockKey(val status: Status, val phase: Phase, val started: Long, val paused: Long, val duration: Long, val subject: String) {
        constructor(t: TimerState) : this(t.status, t.phase, t.phaseStartedAt, t.pausedAt, t.phaseDurationMs, t.subject)
    }

    private suspend fun handleLock(u: String, snap: DocumentSnapshot, fromServer: Boolean, initial: Boolean) = lockMutex.withLock {
        if (uid != u) return@withLock
        val me = device.deviceId()
        val updatedAt = snap.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
        val remote = snap.data?.let { decodeActiveTimer(it, updatedAt) }
        val now = System.currentTimeMillis()
        val local = engine.state.value
        if (remote != null && remote.deviceId == me) holdingLock = remote.state.status != Status.IDLE
        val otherHolds = remote != null && remote.deviceId != me && remote.state.status != Status.IDLE && !remote.isStale(now)
        if (otherHolds) {
            holdingLock = false
            // Only a server-confirmed claim stops a session here; a cached copy may be old.
            if (local.isActive && fromServer) {
                engine.releaseForHandoff()
                lock.notice.value = "Your session moved to your ${remote!!.deviceLabel}."
            }
            if (!local.isActive || fromServer) lock.remote.value = remote
            return@withLock
        }
        lock.remote.value = null
        if (initial && local.isActive) {
            // The other device acted after this phone last wrote the lock: it ended or moved the session.
            val lastWrite = prefs.getLong(KEY_LOCK_WRITTEN, 0L)
            if (remote != null && remote.deviceId != me && remote.updatedAt > lastWrite) engine.releaseForHandoff()
            else writeLock(u, local)
        }
    }

    private suspend fun onLocalTimerChanged() = lockMutex.withLock {
        val u = uid ?: return@withLock
        val t = engine.state.value
        if (t.isActive) writeLock(u, t) else if (holdingLock) writeLock(u, TimerState())
    }

    /** Not awaited: Firestore keeps the write and sends it when online. */
    private suspend fun writeLock(u: String, t: TimerState) {
        val db = db() ?: return
        db.collection("users").document(u).collection("state").document("activeTimer")
            .set(encodeActiveTimer(t, device.deviceId(), device.deviceName, PLATFORM_ANDROID) + ("updatedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { e -> Log.w(TAG, "Timer lock write failed", e) }
        holdingLock = t.isActive
        prefs.edit().putLong(KEY_LOCK_WRITTEN, System.currentTimeMillis()).apply()
    }

    /**
     * "Take over here": continues the other device's session on this phone. Adopted at once (so the
     * caller can start the timer service right away); the other device stops when it sees the claim.
     */
    fun takeOver(): Boolean {
        val u = uid ?: return false
        val remote = lock.remote.value ?: return false
        engine.adopt(remote.state)
        lock.remote.value = null
        scope.launch { lockMutex.withLock { writeLock(u, engine.state.value) } }
        return true
    }

    // ---------------------------------------------------------------- session sync

    private suspend fun cloudIdFor(row: SessionEntity) = "${device.deviceId()}_${row.startedAt}"

    /**
     * Once per account on this phone: sessions recorded while signed in to it before 1.6.0 are claimed,
     * and so are sessions recorded while signed out, unless another account has used this phone.
     */
    private suspend fun backfillOnce(u: String) {
        val key = "backfilled_$u"
        if (prefs.getBoolean(key, false)) return
        val owners = ownership.all.values.filterIsInstance<String>().filter { it != "unassigned" }.toSet() + dao.owners()
        val onlyMe = owners.all { it == u }
        val ids = dao.unsynced().filter { it.origin == null && it.ownerUid == null }.filter { row ->
            val owner = ownership.getString("owner_${row.id}", null)
            owner == u || (onlyMe && (owner == null || owner == "unassigned"))
        }.map { it.id }
        ids.chunked(500).forEach { dao.claim(it, u) }
        uploadPending(u)
        prefs.edit().putBoolean(key, true).apply()
    }

    private suspend fun uploadPending(u: String) {
        if (!uploadMutex.tryLock()) return
        try {
            val db = db() ?: return
            val me = device.deviceId()
            val pending = dao.unsynced().filter { it.ownerUid == u }
            pending.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { row ->
                    val ref = db.collection("users").document(u).collection("sessions").document(cloudIdFor(row))
                    batch.set(ref, sessionFields(row, me))
                }
                // Marked as synced straight away: Firestore keeps the batch on disk until it reaches the
                // server. If the server rejects it (rules not published yet), the rows are retried later.
                chunk.forEach { dao.markSynced(it.id, cloudIdFor(it), u, me) }
                val ids = chunk.map { it.id }
                batch.commit().addOnFailureListener { e ->
                    Log.w(TAG, "Session upload rejected", e)
                    if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) scope.launch { dao.unmarkSynced(ids) }
                }
            }
        } finally { uploadMutex.unlock() }
    }

    private fun sessionFields(row: SessionEntity, me: String): Map<String, Any> = mapOf(
        "startedAt" to row.startedAt, "durationMs" to row.durationMs.coerceIn(0L, 86_400_000L),
        "mode" to row.mode.take(20), "subject" to row.subject.take(80), "completed" to row.completed,
        "deviceId" to me, "platform" to PLATFORM_ANDROID, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp(),
    )

    private suspend fun applyPulled(u: String, docs: List<DocumentSnapshot>) {
        if (uid != u) return
        val me = device.deviceId()
        val add = mutableListOf<SessionEntity>()
        var newest = prefs.getLong(lastPullKey(u), 0L)
        docs.forEach { d ->
            d.getTimestamp("updatedAt")?.toDate()?.time?.let { if (it > newest) newest = it }
            if (d.getBoolean("deleted") == true) { dao.deleteByCloudId(d.id); return@forEach }
            val origin = d.getString("deviceId").orEmpty()
            if (origin == me) return@forEach
            add += SessionEntity(
                startedAt = d.getLong("startedAt") ?: return@forEach,
                durationMs = d.getLong("durationMs") ?: 0L,
                mode = d.getString("mode") ?: "POMODORO",
                subject = d.getString("subject").orEmpty(),
                completed = d.getBoolean("completed") ?: false,
                cloudId = d.id, ownerUid = u, origin = origin.ifBlank { "unknown" },
            )
        }
        if (add.isNotEmpty()) dao.insertAllIgnoring(add)
        prefs.edit().putLong(lastPullKey(u), newest).apply()
    }

    /** Called after a session is deleted on this phone: every device drops it. */
    fun onSessionDeleted(row: SessionEntity) {
        val u = uid ?: return
        val cloudId = row.cloudId ?: return
        if (row.ownerUid != null && row.ownerUid != u) return
        val db = db() ?: return
        db.collection("users").document(u).collection("sessions").document(cloudId)
            .set(mapOf("deleted" to true, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Session delete sync failed", e) }
    }

    private fun lastPullKey(u: String) = "last_pull_$u"

    private companion object {
        const val TAG = "Flowstate"
        const val KEY_LOCK_WRITTEN = "lock_written_at"
    }
}
