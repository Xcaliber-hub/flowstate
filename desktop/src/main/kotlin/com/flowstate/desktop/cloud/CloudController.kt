package com.flowstate.desktop.cloud

import com.flowstate.desktop.FlowstateConfig
import com.flowstate.desktop.store.DeviceIdentity
import com.flowstate.desktop.store.SessionStore
import com.flowstate.desktop.store.SyncPrefsStore
import com.flowstate.desktop.timer.DesktopTimerEngine
import com.flowstate.focus.data.db.SessionEntity
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
    private val db = Firestore { auth.idToken() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CloudState(configured = auth.configured))
    val state: StateFlow<CloudState> = _state.asStateFlow()
    private val deviceId = DeviceIdentity.deviceId
    private val random = SecureRandom()
    private var loops: Job? = null
    private val lockMutex = Mutex()
    private val publishMutex = Mutex()
    /** True while this PC is the holder written in the lock doc. */
    @Volatile private var holdingLock = false
    @Volatile var windowFocused: Boolean = true
    @Volatile var groupsVisible: Boolean = false
        set(value) { field = value; if (value) scope.launch { refreshMembers() } }
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
            if (old != null) syncPrefs.update { it.copy(lastPull = it.lastPull - old) }
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
            launch { runCatching { refreshGroups() }.onFailure(::report) }
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
                runCatching { backfillOnce(uid) }.onFailure(::reportSync)
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
                    runCatching { if (groupsVisible) refreshMembers() }
                }
            }
            launch {
                while (isActive) {
                    delay(90_000L)
                    runCatching { refreshGroups() }
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
                    engine.releaseForHandoff()
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
                        engine.releaseForHandoff()
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

    private fun cloudIdFor(row: SessionEntity) = "${deviceId}_${row.startedAt}"

    /** Once per account: claim this PC's sessions recorded while signed out, then upload them. */
    private suspend fun backfillOnce(uid: String) {
        if (uid in syncPrefs.get().backfilled) return
        val all = sessions.all.value
        val otherOwners = all.mapNotNull { it.ownerUid }.any { it != uid }
        if (!otherOwners) sessions.claim(all.filter { it.ownerUid == null && it.origin == null || it.ownerUid == null && it.origin == deviceId }.map { it.id }.toSet(), uid)
        uploadPending(uid)
        syncPrefs.update { it.copy(backfilled = it.backfilled + uid) }
    }

    private val uploadMutex = Mutex()
    private suspend fun uploadPending(uid: String) = uploadMutex.withLock {
        val pending = sessions.all.value.filter { it.cloudId == null && it.ownerUid == uid }
        pending.chunked(400).forEach { chunk ->
            db.commit(chunk.map { row ->
                db.setWrite("users/$uid/sessions/${cloudIdFor(row)}", sessionFields(row), listOf("updatedAt"))
            })
            chunk.forEach { sessions.markSynced(it.id, cloudIdFor(it), uid, deviceId) }
        }
    }

    private fun sessionFields(row: SessionEntity): Map<String, Any?> = mapOf(
        "startedAt" to row.startedAt, "durationMs" to row.durationMs.coerceIn(0L, 86_400_000L),
        "mode" to row.mode.take(20), "subject" to row.subject.take(80), "completed" to row.completed,
        "deviceId" to deviceId, "platform" to PLATFORM, "deleted" to false,
    )

    /** Pulls sessions changed since the last pull: other devices' new ones, and deletions. */
    private suspend fun pullSessions(uid: String) {
        while (true) {
            val since = syncPrefs.get().lastPull[uid] ?: 0L
            val docs = db.runQuery("users/$uid", Q.query("sessions", Q.filter("updatedAt", "GREATER_THAN", Instant.ofEpochMilli(since)), listOf(Q.order("updatedAt")), 300))
            if (docs.isEmpty()) return
            val add = mutableListOf<SessionEntity>()
            docs.forEach { d ->
                if (d.bool("deleted") == true) { sessions.deleteByCloudId(d.id); return@forEach }
                val origin = d.string("deviceId").orEmpty()
                if (origin == deviceId) return@forEach
                add += SessionEntity(
                    startedAt = d.long("startedAt") ?: return@forEach, durationMs = d.long("durationMs") ?: 0L,
                    mode = d.string("mode") ?: "POMODORO", subject = d.string("subject").orEmpty(), completed = d.bool("completed") ?: false,
                    cloudId = d.id, ownerUid = uid, origin = origin.ifBlank { "unknown" },
                )
            }
            sessions.insertRemote(add)
            val newest = docs.mapNotNull { it.millis("updatedAt") }.maxOrNull() ?: return
            syncPrefs.update { it.copy(lastPull = it.lastPull + (uid to newest)) }
            if (docs.size < 300) return
        }
    }

    /** Deletes a session here and marks it deleted in the cloud, so the phone drops it too. */
    fun deleteSession(id: Long) {
        val row = sessions.delete(id) ?: return
        val u = uid ?: return
        val cid = row.cloudId ?: return
        scope.launch {
            runCatching {
                db.commit(listOf(db.setWrite("users/$u/sessions/$cid", mapOf("deleted" to true), listOf("updatedAt"), mask = listOf("deleted"))))
            }.onFailure(::reportSync)
        }
    }

    // ---------------------------------------------------------------- groups

    private suspend fun refreshGroups() {
        val u = uid ?: return
        val docs = db.runQuery("", Q.query("groups", Q.filter("memberIds", "ARRAY_CONTAINS", u)))
        val groups = docs.mapNotNull { d -> d.string("name")?.let { Group(d.id, it, d.strings("memberIds")) } }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val selected = _state.value.selectedGroupId?.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id
        val changed = selected != _state.value.selectedGroupId
        _state.update { it.copy(groups = groups, selectedGroupId = selected) }
        if (changed) { resetChat(selected); refreshMembers() }
        refreshLastMessages(groups, u)
        publishMembersQuietly()
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
        val doc = db.get("groups/${g.id}") ?: error("Group no longer exists.")
        val members = doc.strings("memberIds")
        if (members.contains(u)) db.commit(listOf(updateMembers(g.id, members - u, doc)))
        runCatching { db.commit(listOf(db.deleteWrite("groups/${g.id}/members/$u"))) }
        _state.update { it.copy(selectedGroupId = null, members = emptyList()) }
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
        const val CHAT_MAX_CHARS = 500
        const val CHAT_REPLY_QUOTE_CHARS = 140
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
