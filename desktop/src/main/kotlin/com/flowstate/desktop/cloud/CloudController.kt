package com.flowstate.desktop.cloud

import com.flowstate.desktop.FlowstateConfig
import com.flowstate.desktop.store.DeviceIdentity
import com.flowstate.desktop.store.SessionStore
import com.flowstate.desktop.store.SyncPrefsStore
import com.flowstate.desktop.timer.DesktopTimerEngine
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.SessionSyncPlan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.domain.groups.PresetAvatars
import com.flowstate.focus.domain.groups.dailyTotalIst
import com.flowstate.focus.domain.groups.dayKeyIst
import com.flowstate.focus.domain.groups.weeklySessionCount
import com.flowstate.focus.domain.groups.weeklyTotal
import com.flowstate.focus.domain.groups.weeklyWeekKeyIst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale

/**
 * Everything the Windows app does with the cloud, over REST with polling (Spark plan, no Functions):
 * account, profile, groups, leaderboard, chat, the one-device timer lock and session sync.
 * Writes exactly the same documents as Android, so the same Firestore rules accept them.
 */
@OptIn(FlowPreview::class)
class CloudController(
    private val engine: DesktopTimerEngine,
    private val sessions: SessionStore,
    private val syncPrefs: SyncPrefsStore,
    val auth: AuthClient,
) {
    private val db = Firestore { force -> auth.idToken(force) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CloudState(configured = auth.configured))
    val state: StateFlow<CloudState> = _state.asStateFlow()
    private val deviceId = DeviceIdentity.deviceId
    private val random = SecureRandom()
    private var loops: Job? = null
    private val lockMutex = Mutex()
    private val publishMutex = Mutex()
    private val groupsMutex = Mutex()
    /** Keeps one bad poll from emptying the Groups list (1.8.0 desktop fix). */
    private val groupsGuard = EmptyListGuard()
    /** True while this PC is the holder written in the lock doc. */
    @Volatile private var holdingLock = false
    @Volatile var windowFocused: Boolean = true
    @Volatile var groupsVisible: Boolean = false
        set(value) { field = value; if (value) scope.launch { runCatching { refreshMembers() } } }
    @Volatile private var chatNewest = 0L
    @Volatile private var chatFullAt = 0L
    @Volatile private var readWrittenUpTo = 0L

    val uid: String? get() = _state.value.user?.uid

    init {
        auth.session?.let { s ->
            _state.update { it.copy(restoring = true, user = CloudUser(s.uid, s.displayName.ifBlank { "Focus member" }, "", s.email)) }
            scope.launch { onSignedIn() }
        }
    }

    // ---------------------------------------------------------------- account

    fun signIn(email: String, password: String) = busy {
        require(email.trim().contains('@') && password.isNotEmpty()) { "Enter a valid email and password." }
        try { auth.signIn(email, password) } catch (e: Exception) { throw IllegalStateException(AuthClient.friendly(e)) }
        onSignedIn()
    }

    fun signUp(name: String, email: String, password: String) = busy {
        val clean = validName(name)
        require(email.trim().contains('@')) { "Enter a valid email address." }
        require(password.length in 8..128) { "Password must be 8–128 characters." }
        try { auth.signUp(clean, email, password) } catch (e: Exception) { throw IllegalStateException(AuthClient.friendly(e)) }
        runCatching { db.commit(listOf(db.setWrite("users/${auth.session!!.uid}", mapOf("name" to clean, "avatarId" to ""), mask = listOf("name", "avatarId")))) }
        onSignedIn()
    }

    fun resetPassword(email: String) = busy {
        require(email.trim().contains('@')) { "Enter a valid email address." }
        try { auth.sendPasswordReset(email) } catch (e: Exception) { throw IllegalStateException(AuthClient.friendly(e)) }
        _state.update { it.copy(notice = "If an account matches this email, password reset instructions have been requested. Check your inbox and spam folder.") }
    }

    fun signOut() {
        val old = uid
        loops?.cancel(); loops = null
        scope.launch {
            // Leave the timer free for the phone if this PC was holding it.
            if (old != null && holdingLock) runCatching { writeLock(TimerState()) }
            auth.signOut()
            holdingLock = false
            sessions.deletePulled(deviceId)
            // The pulled rows are gone, so the next sign-in pulls the whole history again.
            if (old != null) syncPrefs.update { it.copy(lastPull = it.lastPull - old, pullCursor = it.pullCursor - old) }
            groupsMutex.withLock { groupsGuard.reset() }
            _state.value = CloudState(configured = auth.configured)
        }
    }

    fun updateProfile(name: String, avatarId: String) = busy {
        val u = _state.value.user ?: error("Sign in first.")
        val clean = validName(name)
        require(PresetAvatars.isValid(avatarId)) { "Choose an approved avatar." }
        db.commit(listOf(db.setWrite("users/${u.uid}", mapOf("name" to clean, "avatarId" to avatarId), mask = listOf("name", "avatarId"))))
        auth.rememberName(clean)
        _state.update { it.copy(user = u.copy(name = clean, avatarId = avatarId), notice = "Profile saved.") }
        publishMembers()
    }

    private suspend fun onSignedIn() {
        val s = auth.session ?: return
        val profile = runCatching { db.get("users/${s.uid}") }.getOrElse { e ->
            // A revoked sign-in (password changed elsewhere) signs this PC out.
            if (auth.session == null) { _state.value = CloudState(configured = auth.configured, error = "Your sign-in expired. Sign in again."); return }
            null
        }
        if (auth.session?.uid != s.uid) return
        val user = CloudUser(
            s.uid,
            (profile?.string("name") ?: s.displayName).ifBlank { "Focus member" },
            PresetAvatars.fallback(profile?.string("avatarId")),
            s.email,
        )
        _state.update { it.copy(user = user, restoring = false, error = null) }
        startLoops(s.uid)
    }

    private fun startLoops(uid: String) {
        loops?.cancel()
        loops = scope.launch {
            groupsMutex.withLock { groupsGuard.reset() }
            // Groups: right away, then every 90 s (30 s while the Groups screen is open). A failure never
            // stops the loop; it retries with backoff (5 s, 10 s, 20 s … 5 min) and keeps the last good list.
            launch {
                var failures = 0
                while (isActive) {
                    try {
                        refreshGroups()
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures++
                        groupsMutex.withLock { groupsGuard.onFailure() }
                        onGroupsPollFailed(e, failures)
                    }
                    delay(if (failures > 0) pollBackoffMs(failures) else if (groupsVisible) 30_000L else 90_000L)
                }
            }
            // Timer lock: read first, so a session the phone took over while this PC was closed is not resumed here.
            launch {
                runCatching { pollLock(initial = true) }
                while (isActive) {
                    delay(if (windowFocused) 5_000L else 15_000L)
                    runCatching { pollLock(initial = false) }
                }
            }
            // Claim the lock or release it whenever the local timer changes.
            launch {
                engine.state.map { LockKey(it) }.distinctUntilChanged().drop(1).collect {
                    runCatching { onLocalTimerChanged() }.onFailure { e -> if (e is CloudException && e.permissionDenied) _state.update { s -> s.copy(lockUnavailable = true) } }
                    publishMembersQuietly()
                }
            }
            // Session sync: upload own sessions (with the one-time backfill), pull the other devices'.
            launch {
                runCatching { sessions.deletePulledOfOtherAccounts(deviceId, uid) }
                runCatching { backfill(uid) }.onFailure(::reportSync)
                sessions.all.debounce(1_500).collect { runCatching { uploadPending(uid) }.onFailure(::reportSync); publishMembersQuietly() }
            }
            launch {
                while (isActive) {
                    runCatching { pullSessions(uid) }.onFailure(::reportSync)
                    delay(if (windowFocused) 60_000L else 180_000L)
                }
            }
            // Leaderboard presence heartbeat (others treat a member as live for 90 s after a write).
            launch { while (isActive) { publishMembersQuietly(); delay(45_000L) } }
            launch {
                while (isActive) {
                    delay(if (groupsVisible) 15_000L else 60_000L)
                    try { if (groupsVisible) refreshMembers() } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
                }
            }
            launch {
                while (isActive) {
                    delay(4_000L)
                    if (groupsVisible) runCatching { pollChat() }.onFailure { e -> _state.update { it.copy(chat = it.chat.copy(error = chatError(e), loaded = true)) } }
                }
            }
        }
    }

    // ---------------------------------------------------------------- one-device timer lock

    private data class LockKey(val status: Status, val phase: com.flowstate.focus.domain.Phase, val started: Long, val paused: Long, val duration: Long, val subject: String) {
        constructor(t: TimerState) : this(t.status, t.phase, t.phaseStartedAt, t.pausedAt, t.phaseDurationMs, t.subject)
    }

    private suspend fun pollLock(initial: Boolean) = lockMutex.withLock {
        val u = uid ?: return@withLock
        val doc = try { db.get("users/$u/state/activeTimer") } catch (e: CloudException) {
            if (e.permissionDenied) _state.update { it.copy(lockUnavailable = true) }
            throw e
        }
        _state.update { it.copy(lockUnavailable = false) }
        val remote = doc?.let(::decodeActiveTimer)
        val now = System.currentTimeMillis()
        val local = engine.state.value
        val otherHolds = remote != null && remote.deviceId != deviceId && remote.state.status != Status.IDLE && !remote.isStale(now)
        if (remote != null && remote.deviceId == deviceId) holdingLock = remote.state.status != Status.IDLE
        when {
            otherHolds -> {
                holdingLock = false
                if (local.isActive) {
                    engine.releaseForHandoff(remote!!.state)
                    _state.update { it.copy(notice = "Your session moved to your ${remote!!.deviceLabel}.") }
                }
                _state.update { it.copy(remote = remote) }
            }
            else -> {
                _state.update { it.copy(remote = null) }
                if (initial && local.isActive) {
                    // The other device acted after this PC last wrote the lock: it ended or moved the session.
                    val lastWrite = syncPrefs.get().lockWrittenAt
                    if (remote != null && remote.deviceId != deviceId && remote.updatedAt > lastWrite) {
                        engine.releaseForHandoff(remote.state)
                    } else writeLock(local)
                }
            }
        }
    }

    private suspend fun onLocalTimerChanged() = lockMutex.withLock {
        if (uid == null) return@withLock
        val t = engine.state.value
        if (t.isActive) writeLock(t)
        else if (holdingLock) writeLock(TimerState())
    }

    private suspend fun writeLock(t: TimerState) {
        val u = uid ?: return
        db.commit(listOf(db.setWrite("users/$u/state/activeTimer", encodeActiveTimer(t, deviceId, DeviceIdentity.deviceName, PLATFORM), listOf("updatedAt"))))
        holdingLock = t.isActive
        syncPrefs.update { it.copy(lockWrittenAt = System.currentTimeMillis()) }
    }

    /** "Take over here": continue the other device's session on this PC; that device stops when it sees the lock change. */
    fun takeOver() {
        scope.launch {
            lockMutex.withLock {
                val remote = _state.value.remote ?: return@withLock
                try {
                    engine.adopt(remote.state)
                    writeLock(engine.state.value)
                    _state.update { it.copy(remote = null, notice = "Session moved to this PC.") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    engine.releaseForHandoff()
                    report(e)
                }
            }
            publishMembersQuietly()
        }
    }

    // ---------------------------------------------------------------- session sync

    /**
     * Every sign-in (1.8.1; once per account before): claim this PC's sessions recorded while signed out, unless
     * another account has used this PC, then upload them. Until 1.8.0 a session recorded while signed out after
     * the first sign-in was never uploaded.
     */
    private suspend fun backfill(uid: String) {
        val all = sessions.all.value
        val otherOwners = all.mapNotNull { it.ownerUid }.any { it != uid }
        if (!otherOwners) sessions.claim(all.filter { it.ownerUid == null && it.origin == null || it.ownerUid == null && it.origin == deviceId }.map { it.id }.toSet(), uid)
        uploadPending(uid)
        if (uid !in syncPrefs.get().backfilled) syncPrefs.update { it.copy(backfilled = it.backfilled + uid) }
    }

    private val uploadMutex = Mutex()

    /**
     * Uploads this PC's unsynced sessions, 400 to a commit. 1.8.1: a commit is all-or-nothing, so when the rules
     * reject one, the chunk is retried one session at a time and every accepted session is still marked; until
     * 1.8.0 one rejected session kept the whole chunk off the cloud for good. Each row also gets a cloud id no
     * other row uses. Pending "deleted" marks are retried here too.
     */
    private suspend fun uploadPending(uid: String) = uploadMutex.withLock {
        retryTombstones(uid)
        val all = sessions.all.value
        val pending = all.filter { it.cloudId == null && it.ownerUid == uid }
        if (pending.isEmpty()) return@withLock
        val taken = all.mapNotNull { it.cloudId }.toHashSet()
        val ids = LinkedHashMap<Long, String>()
        pending.forEach { row -> ids[row.id] = SessionSyncPlan.cloudIdFor(deviceId, row.startedAt, row.id, taken).also { taken += it } }
        var rejected: CloudException? = null
        pending.chunked(400).forEach { chunk ->
            val write = { row: SessionEntity -> db.setWrite("users/$uid/sessions/${ids.getValue(row.id)}", sessionFields(row), listOf("updatedAt")) }
            try {
                db.commit(chunk.map(write))
                sessions.markSynced(chunk.associate { it.id to ids.getValue(it.id) }, uid, deviceId)
            } catch (e: CloudException) {
                if (!e.permissionDenied || chunk.size == 1) throw e
                val ok = LinkedHashMap<Long, String>()
                chunk.forEach { row ->
                    try { db.commit(listOf(write(row))); ok[row.id] = ids.getValue(row.id) }
                    catch (one: CloudException) { if (one.permissionDenied) rejected = one else throw one }
                }
                sessions.markSynced(ok, uid, deviceId)
            }
        }
        rejected?.let { throw it }
    }

    private fun sessionFields(row: SessionEntity): Map<String, Any?> = mapOf(
        "startedAt" to row.startedAt, "durationMs" to row.durationMs.coerceIn(0L, 86_400_000L),
        "mode" to row.mode.take(20), "subject" to row.subject.take(80), "completed" to row.completed,
        "deviceId" to deviceId, "platform" to PLATFORM, "deleted" to false,
    )

    /**
     * Pulls sessions changed since the last pull: other devices' new ones, this PC's own that are missing here,
     * and deletions.
     *
     * 1.8.1: paged with a cursor on (updatedAt, document name). Until 1.8.0 each page asked for "updatedAt
     * greater than the newest seen", 300 at a time; a batch upload stamps all its sessions (up to 400) with one
     * server time, so the rest of that batch was skipped for good, or (with sub-millisecond times) the same
     * 300 came back for ever and nothing newer was ever pulled. Nothing here deletes a session that is missing
     * from the cloud; only an explicit "deleted" mark removes one.
     */
    private suspend fun pullSessions(uid: String) {
        var cursor = syncPrefs.get().pullCursor[uid]?.let(::parsePullCursor)
        while (true) {
            val docs = db.runQuery("users/$uid", sessionPullQuery(cursor, PULL_PAGE))
            if (docs.isEmpty()) return
            if (auth.session?.uid != uid) return
            val known = sessions.all.value
            val plan = SessionSyncPlan.plan(
                docs.map(::decodeSessionDoc), uid, deviceId,
                knownCloudIds = known.mapNotNull { it.cloudId }.toHashSet(),
                unsyncedOwnStarts = known.filter { it.cloudId == null && (it.origin == null || it.origin == deviceId) }.map { it.startedAt }.toHashSet(),
                pendingTombstones = syncPrefs.get().pendingTombstones[uid].orEmpty(),
            )
            sessions.deleteByCloudIds(plan.deleteCloudIds)
            sessions.insertRemote(plan.add)
            val last = docs.last()
            val lastAt = last.fields["updatedAt"] as? Instant
            if (lastAt != null) {
                cursor = lastAt to last.name
                val key = formatPullCursor(lastAt, last.name)
                syncPrefs.update { it.copy(pullCursor = it.pullCursor + (uid to key), lastPull = it.lastPull + (uid to lastAt.toEpochMilli())) }
            } else return
            if (docs.size < PULL_PAGE) return
        }
    }

    /** Deletes a session here and marks it deleted in the cloud, so the phone drops it too. */
    fun deleteSession(id: Long) {
        val row = sessions.delete(id) ?: return
        val cid = row.cloudId ?: return
        val owner = row.ownerUid ?: uid ?: return
        // 1.8.1: remembered until the cloud has the mark, so a failed or offline delete is retried and the
        // full re-pull does not bring the session back.
        syncPrefs.update { it.copy(pendingTombstones = it.pendingTombstones + (owner to (it.pendingTombstones[owner].orEmpty() + cid))) }
        val u = uid ?: return
        if (u != owner) return
        scope.launch { runCatching { writeTombstone(u, cid) }.onFailure(::reportSync) }
    }

    private suspend fun writeTombstone(u: String, cid: String) {
        try {
            db.commit(listOf(db.setWrite("users/$u/sessions/$cid", mapOf("deleted" to true), listOf("updatedAt"), mask = listOf("deleted"))))
        } catch (e: CloudException) {
            // Rejected: the session never reached the cloud, so there is nothing to mark. Stop retrying.
            if (!e.permissionDenied) throw e
        }
        syncPrefs.update { p ->
            val left = p.pendingTombstones[u].orEmpty() - cid
            p.copy(pendingTombstones = if (left.isEmpty()) p.pendingTombstones - u else p.pendingTombstones + (u to left))
        }
    }

    private suspend fun retryTombstones(uid: String) {
        syncPrefs.get().pendingTombstones[uid].orEmpty().forEach { cid -> runCatching { writeTombstone(uid, cid) } }
    }

    // ---------------------------------------------------------------- groups

    /**
     * Reloads the groups this account is in. Throws on any failed or partial query, so the caller keeps the
     * last good list. A doc without a name is still listed (by a fallback name), never dropped; an empty
     * result replaces a non-empty list only as [EmptyListGuard] allows.
     */
    private suspend fun refreshGroups() {
        val u = uid?.takeIf { it.isNotBlank() } ?: return
        val docs = db.runQuery("", Q.query("groups", Q.filter("memberIds", "ARRAY_CONTAINS", u)))
        if (uid != u) return // signed out or switched account while the query ran
        val fresh = docs.map { d -> Group(d.id, d.string("name")?.trim()?.ifBlank { null } ?: "Unnamed group", d.strings("memberIds")) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val (groups, changed) = groupsMutex.withLock {
            val shown = groupsGuard.accept(_state.value.groups, fresh) ?: _state.value.groups
            val selected = _state.value.selectedGroupId?.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
            val changed = selected != _state.value.selectedGroupId
            _state.update { it.copy(groups = shown, selectedGroupId = selected) }
            shown to changed
        }
        if (changed) { resetChat(_state.value.selectedGroupId); runCatching { refreshMembers() } }
        runCatching { refreshLastMessages(groups, u) }
        publishMembersQuietly()
    }

    private fun onGroupsPollFailed(e: Throwable, failures: Int) {
        // The token refresh rejected this PC's sign-in (password changed, account disabled): say so instead of
        // polling forever with a stale "signed in" screen.
        if (auth.session == null && _state.value.user != null) {
            loops?.cancel(); loops = null
            _state.value = CloudState(configured = auth.configured, error = "Your sign-in expired. Sign in again.")
            return
        }
        // Stay quiet on a single network blip while a list is shown; surface persistent or access problems.
        if (_state.value.groups.isEmpty() || failures >= 3 || (e is CloudException && e.permissionDenied)) report(e)
    }

    private suspend fun refreshLastMessages(groups: List<Group>, u: String) {
        val seen = syncPrefs.get().chatSeen
        val last = mutableMapOf<String, ChatMessage>()
        groups.forEach { g ->
            runCatching { db.runQuery("groups/${g.id}", Q.query("messages", orderBy = listOf(Q.order("sentAt", true)), limit = 1)).firstOrNull()?.let(::decodeMessage) }
                .getOrNull()?.let { last[g.id] = it }
        }
        val unread = last.filter { (gid, m) -> m.uid != u && m.sentAt > (seen[gid] ?: 0L) && !(groupsVisible && gid == _state.value.chat.groupId) }.keys
        _state.update { it.copy(lastMessages = last, unreadGroups = unread) }
    }

    fun selectGroup(id: String) {
        if (_state.value.selectedGroupId == id) return
        _state.update { it.copy(selectedGroupId = id, members = emptyList()) }
        resetChat(id)
        scope.launch { runCatching { refreshMembers() }.onFailure(::report); runCatching { pollChat() } }
    }

    private suspend fun refreshMembers() {
        val g = _state.value.selectedGroup ?: return
        val docs = db.runQuery("groups/${g.id}", Q.query("members"))
        val now = System.currentTimeMillis()
        val week = weeklyWeekKeyIst(now)
        val members = docs.mapNotNull { d ->
            if (d.id !in g.memberIds) return@mapNotNull null
            val thisWeek = d.string("weekKey") == week
            val liveMap = d.map("live")
            Member(
                uid = d.id, name = d.string("name").orEmpty().ifBlank { "Focus member" }, avatarId = PresetAvatars.fallback(d.string("avatarId")),
                weeklyFocusMs = if (thisWeek) d.long("weeklyFocusMs") ?: 0L else 0L,
                live = liveMap?.let(::decodeLive), livePlatform = liveMap?.get("platform") as? String,
                updatedAt = d.millis("updatedAt") ?: 0L,
                weeklySessions = d.long("weeklySessions")?.let { if (thisWeek) it.toInt().coerceAtLeast(0) else 0 },
                todayFocusMs = d.long("todayFocusMs")?.let { if (d.string("dayKey") == dayKeyIst(now)) it.coerceAtLeast(0L) else 0L },
            )
        }.sortedWith(compareByDescending<Member> { it.weeklyFocusMs }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.uid })
        if (_state.value.selectedGroupId == g.id) _state.update { it.copy(members = members) }
    }

    fun createGroup(name: String) = busy {
        val u = uid ?: error("Sign in first.")
        val clean = validName(name)
        val code = (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        db.commit(listOf(db.setWrite("groups/$code", mapOf("name" to clean, "memberIds" to listOf(u)), listOf("createdAt"), mustExist = false)))
        _state.update { it.copy(selectedGroupId = code) }
        refreshGroups()
        resetChat(code)
        _state.update { it.copy(notice = "Group created. Invite code: $code") }
    }

    fun joinGroup(code: String) = busy {
        val u = uid ?: error("Sign in first.")
        val id = code.trim().uppercase(Locale.ROOT)
        require(id.matches(Regex("[A-Z0-9]{12}"))) { "Enter a 12-character group code." }
        val doc = db.get("groups/$id") ?: error("Group code not found.")
        val members = doc.strings("memberIds")
        check(members.contains(u) || members.size < MAX_GROUP_SIZE) { "This group is full." }
        if (!members.contains(u)) db.commit(listOf(updateMembers(id, members + u, doc)))
        _state.update { it.copy(selectedGroupId = id) }
        refreshGroups()
        resetChat(id)
    }

    fun leaveGroup() = busy {
        val u = uid ?: error("Sign in first.")
        val g = _state.value.selectedGroup ?: error("Select a group first.")
        val doc = db.get("groups/${g.id}")
        if (doc == null) {
            // Deleted elsewhere: an empty list afterwards is real.
            groupsMutex.withLock { groupsGuard.allowNextEmpty() }
            _state.update { st -> st.copy(groups = st.groups.filter { it.id != g.id }, selectedGroupId = null, members = emptyList()) }
            refreshGroups()
            error("Group no longer exists.")
        }
        val members = doc.strings("memberIds")
        if (members.contains(u)) db.commit(listOf(updateMembers(g.id, members - u, doc)))
        runCatching { db.commit(listOf(db.deleteWrite("groups/${g.id}/members/$u"))) }
        // Confirmed leave: drop it now, and let an empty result through if it was the last group.
        groupsMutex.withLock { groupsGuard.allowNextEmpty() }
        _state.update { st -> st.copy(groups = st.groups.filter { it.id != g.id }, selectedGroupId = null, members = emptyList()) }
        refreshGroups()
    }

    /** Only memberIds changes, guarded by the read's updateTime like Android's transaction. */
    private fun updateMembers(gid: String, ids: List<String>, read: FsDoc) = buildJsonObject {
        put("update", buildJsonObject { put("name", db.docName("groups/$gid")); put("fields", fsFields(mapOf("memberIds" to ids))) })
        put("updateMask", buildJsonObject { put("fieldPaths", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("memberIds")) }) })
        read.updateTime?.let { t -> put("currentDocument", buildJsonObject { put("updateTime", t.toString()) }) }
    }

    // ---------------------------------------------------------------- member snapshot (leaderboard + live)

    private fun publishMembersQuietly() { scope.launch { runCatching { publishMembers() }.onFailure(::reportQuiet) } }

    /**
     * Writes groups/{gid}/members/{uid} for every group, the same full snapshot Android writes
     * (it must pass validMember). Totals come from the merged history, so phone and PC agree.
     * The live timer is this PC's, or the other device's while it holds the timer.
     */
    private suspend fun publishMembers() = publishMutex.withLock {
        val u = _state.value.user ?: return@withLock
        val groups = _state.value.groups
        if (groups.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val owned = sessions.all.value.filter { it.ownerUid == u.uid }
        val intervals = owned.map { it.startedAt to it.durationMs }
        val local = engine.state.value
        val remote = _state.value.remote
        val (live, platform) = if (!local.isActive && remote != null) remote.state to remote.platform else local to PLATFORM
        val fields = mapOf(
            "name" to u.name, "avatarId" to u.avatarId, "photoUrl" to "",
            "weeklyFocusMs" to weeklyTotal(intervals, now), "weekKey" to weeklyWeekKeyIst(now),
            "live" to encodeLive(live, platform),
            "weeklySessions" to weeklySessionCount(owned.map { it.startedAt }, now).coerceAtLeast(0),
            "todayFocusMs" to dailyTotalIst(intervals, now).coerceIn(0L, 86_400_000L),
            "dayKey" to dayKeyIst(now),
        )
        // One commit per group, like Android: a group that rejects the write (left, deleted) can't block the others.
        var denied: Exception? = null
        groups.forEach { g ->
            try { db.commit(listOf(db.setWrite("groups/${g.id}/members/${u.uid}", fields, listOf("updatedAt")))) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { denied = e }
        }
        denied?.let { throw it }
    }

    // ---------------------------------------------------------------- chat

    private fun resetChat(groupId: String?) {
        chatNewest = 0L; chatFullAt = 0L; readWrittenUpTo = 0L
        _state.update { it.copy(chat = ChatUi(groupId = groupId)) }
    }

    /** Newest 50 messages every 90 s, and in between only messages newer than the newest one shown. */
    private suspend fun pollChat() {
        val gid = _state.value.selectedGroupId ?: return
        if (_state.value.chat.groupId != gid) resetChat(gid)
        val now = System.currentTimeMillis()
        val full = chatNewest == 0L || now - chatFullAt > 90_000L
        val docs = if (full) db.runQuery("groups/$gid", Q.query("messages", orderBy = listOf(Q.order("sentAt", true)), limit = CHAT_PAGE))
        else db.runQuery("groups/$gid", Q.query("messages", Q.filter("sentAt", "GREATER_THAN", Instant.ofEpochMilli(chatNewest)), listOf(Q.order("sentAt")), CHAT_PAGE))
        if (_state.value.selectedGroupId != gid) return
        val fetched = docs.mapNotNull(::decodeMessage)
        val merged = if (full) {
            chatFullAt = now
            val oldest = fetched.minOfOrNull { it.sentAt } ?: Long.MIN_VALUE
            // Keep older pages; replace the window the server just returned (edits and deletions show up).
            val keepOlder = if (fetched.size >= CHAT_PAGE) _state.value.chat.messages.filter { it.sentAt < oldest } else emptyList()
            keepOlder + fetched + _state.value.chat.messages.filter { it.pending }
        } else {
            val byId = _state.value.chat.messages.associateBy { it.id }.toMutableMap()
            fetched.forEach { byId[it.id] = it }
            byId.values.toList()
        }
        val list = merged.distinctBy { it.id }.sortedWith(compareByDescending<ChatMessage> { it.sentAt }.thenByDescending { it.id })
        chatNewest = list.filter { !it.pending }.maxOfOrNull { it.sentAt } ?: chatNewest
        val seen = if (!full) _state.value.chat.seenBy else runCatching {
            db.runQuery("groups/$gid", Q.query("reads")).mapNotNull { d -> d.millis("seenAt")?.let { d.id to it } }.toMap()
        }.getOrDefault(_state.value.chat.seenBy)
        _state.update { it.copy(chat = it.chat.copy(groupId = gid, messages = list, loaded = true, error = null, seenBy = seen), unreadGroups = it.unreadGroups - gid,
            lastMessages = list.firstOrNull()?.let { m -> it.lastMessages + (gid to m) } ?: it.lastMessages) }
        markSeen(gid, list)
    }

    private suspend fun markSeen(gid: String, list: List<ChatMessage>) {
        val u = uid ?: return
        val newest = list.maxOfOrNull { it.sentAt } ?: return
        if (newest > (syncPrefs.get().chatSeen[gid] ?: 0L)) syncPrefs.update { it.copy(chatSeen = it.chatSeen + (gid to newest)) }
        val newestOthers = list.filter { it.uid != u && !it.pending }.maxOfOrNull { it.sentAt } ?: return
        if (newestOthers <= readWrittenUpTo || !windowFocused) return
        readWrittenUpTo = newestOthers
        runCatching { db.commit(listOf(db.setWrite("groups/$gid/reads/$u", emptyMap(), listOf("seenAt")))) }.onFailure { readWrittenUpTo = 0L }
    }

    fun sendChat(text: String, replyTo: ChatMessage?): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > CHAT_MAX_CHARS) return false
        val me = _state.value.user ?: return false
        val gid = _state.value.selectedGroupId ?: return false
        val id = (1..20).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
        val fields = mutableMapOf<String, Any?>("uid" to me.uid, "name" to me.name, "avatarId" to me.avatarId, "text" to clean)
        val reply = replyTo?.let { ChatReply(it.id, it.uid, it.name.take(40), it.text.take(CHAT_REPLY_QUOTE_CHARS)) }
        if (reply != null) fields["replyTo"] = mapOf("id" to reply.id, "uid" to reply.uid, "name" to reply.name, "text" to reply.text)
        val pending = ChatMessage(id, me.uid, me.name, me.avatarId, clean, System.currentTimeMillis(), pending = true, replyTo = reply)
        _state.update { it.copy(chat = it.chat.copy(messages = listOf(pending) + it.chat.messages)) }
        scope.launch {
            try {
                db.commit(listOf(db.setWrite("groups/$gid/messages/$id", fields, listOf("sentAt"), mustExist = false)))
                requestPush(gid, id)
                pollChat()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(chat = it.chat.copy(messages = it.chat.messages.filterNot { m -> m.id == id }, error = chatError(e))) }
            }
        }
        return true
    }

    fun clearChatError() = _state.update { it.copy(chat = it.chat.copy(error = null)) }

    /** Same relay call as Android, so friends' phones get a push for a message sent from the PC. */
    private suspend fun requestPush(groupId: String, messageId: String) {
        val url = FlowstateConfig.PUSH_URL
        if (url.isBlank()) return
        runCatching {
            val token = auth.idToken()
            withContext(Dispatchers.IO) {
                Http.send("POST", url.trimEnd('/') + "/notify", buildJsonObject { put("groupId", groupId); put("messageId", messageId) }.toString(), mapOf("Authorization" to "Bearer $token"))
            }
        }
    }

    private fun decodeMessage(d: FsDoc): ChatMessage? {
        val text = d.string("text")?.takeIf { it.isNotBlank() } ?: return null
        val reply = d.map("replyTo")?.let { r ->
            val id = r["id"] as? String ?: return@let null
            ChatReply(id, r["uid"] as? String ?: "", (r["name"] as? String)?.ifBlank { null } ?: "Focus member", r["text"] as? String ?: "")
        }
        return ChatMessage(d.id, d.string("uid").orEmpty(), d.string("name").orEmpty().ifBlank { "Focus member" }, PresetAvatars.fallback(d.string("avatarId")),
            text, d.millis("sentAt") ?: System.currentTimeMillis(), false, reply, edited = d.fields.containsKey("editedAt"))
    }

    // ---------------------------------------------------------------- helpers

    fun clearMessages() = _state.update { it.copy(error = null, notice = null) }

    private fun busy(block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    private fun report(e: Throwable) { _state.update { it.copy(error = safeMessage(e)) } }
    private fun reportQuiet(e: Throwable) { if (e is CloudException && e.permissionDenied) report(e) }
    private fun reportSync(e: Throwable) {
        if (e is CloudException && e.permissionDenied)
            _state.update { it.copy(error = "Session sync isn't allowed yet. Publish the 1.6.0 Firestore rules in project ${FlowstateConfig.FIREBASE_PROJECT_ID}.") }
    }

    private fun chatError(e: Throwable): String =
        if (e is CloudException && e.permissionDenied) "Chat isn't allowed yet. Publish the latest Firestore rules in project ${FlowstateConfig.FIREBASE_PROJECT_ID}."
        else safeMessage(e)

    private fun safeMessage(t: Throwable): String = when {
        t is CloudException && t.permissionDenied -> "Cloud Firestore denied access in project ${FlowstateConfig.FIREBASE_PROJECT_ID}. Check this account's membership and the published rules."
        t is CloudException && t.status == 401 -> "Your sign-in expired. Sign in again."
        t is java.io.IOException -> "Network unavailable. Check your connection and try again."
        else -> t.message?.takeIf { it.length <= 600 } ?: "Something went wrong. Please try again."
    }

    private fun validName(s: String): String = s.trim().also { require(it.length in 1..40 && it.none(Char::isISOControl)) { "Name must be 1–40 characters." } }

    companion object {
        const val PLATFORM = "windows"
        const val MAX_GROUP_SIZE = 20
        const val CHAT_PAGE = 50
        const val PULL_PAGE = 300
        const val CHAT_MAX_CHARS = 500
        const val CHAT_REPLY_QUOTE_CHARS = 140
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
