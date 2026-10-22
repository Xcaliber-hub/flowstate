package com.flowstate.focus.data.groups

import android.content.Context
import com.flowstate.focus.R
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.groups.weeklyTotal
import com.flowstate.focus.domain.groups.weeklyWeekKey
import com.flowstate.focus.domain.groups.weeklyWeekStartUtc
import com.flowstate.focus.domain.groups.PresetAvatars
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

 data class GroupUser(val uid: String, val name: String, val avatarId: String = "")
 data class FocusGroup(val id: String, val name: String, val memberIds: List<String>)
 data class GroupMember(val uid: String, val name: String, val weeklyFocusMs: Long, val live: TimerState?, val updatedAt: Long, val avatarId: String = "")
 data class GroupsState(val configured: Boolean = false, val user: GroupUser? = null, val groups: List<FocusGroup> = emptyList(), val selectedGroup: FocusGroup? = null, val members: List<GroupMember> = emptyList(), val loading: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null, val profileSaving: Boolean = false, val profileSaveVersion: Long = 0L)

@Singleton
class GroupsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timer: TimerEngine,
    private val sessions: SessionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(GroupsState())
    val state: StateFlow<GroupsState> = _state.asStateFlow()
    private var auth: FirebaseAuth? = null
    private var db: FirebaseFirestore? = null
    private var groupListener: ListenerRegistration? = null
    private var memberListeners = mutableListOf<ListenerRegistration>()
    private var selectedMembers = emptyList<GroupMember>()
    private var syncJob: kotlinx.coroutines.Job? = null
    private var activeUser: GroupUser? = null
    private var groupList = emptyList<FocusGroup>()
    private val authGeneration = AtomicLong(0L)
    // A heartbeat must never overwrite a just-saved avatar with its old snapshot.
    private val publishMutex = Mutex()
    private val random = SecureRandom()
    private val ownership = context.getSharedPreferences("group_session_owners", Context.MODE_PRIVATE)

    init {
        val appId = readResource(R.string.flowstate_firebase_application_id)
        val apiKey = readResource(R.string.flowstate_firebase_api_key)
        val project = readResource(R.string.flowstate_firebase_project_id)
        val configured = listOf(appId, apiKey, project).all { it.isNotBlank() }
        _state.value = GroupsState(configured = configured)
        if (configured) runCatching {
            val opts = FirebaseOptions.Builder().setApplicationId(appId).setApiKey(apiKey).setProjectId(project).build()
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(context, opts, APP_NAME)!!
            auth = FirebaseAuth.getInstance(app); db = FirebaseFirestore.getInstance(app)
            auth?.addAuthStateListener { a -> scope.launch { val current = a.currentUser; acceptAuth(current?.uid, current?.displayName) } }
        }.onFailure { setError(it) }
    }

    fun signIn(email: String, password: String) = launchBusy { require(emailOk(email) && password.isNotEmpty()) { "Enter a valid email and password." }; authRequired().signInWithEmailAndPassword(email.trim(), password).await() }
    fun signUp(name: String, email: String, password: String) = launchBusy {
        val clean = validName(name); require(emailOk(email)) { "Enter a valid email address." }; require(password.length >= 8 && password.length <= 128) { "Password must be 8–128 characters." }
        val cred = authRequired().createUserWithEmailAndPassword(email.trim(), password).await(); val user = cred.user ?: error("Account was created without a user profile.")
        user.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = clean }).await()
        if (authRequired().currentUser?.uid == user.uid) acceptAuth(user.uid, clean)
    }
    fun resetPassword(email: String) = launchBusy { require(emailOk(email)) { "Enter a valid email address." }; authRequired().sendPasswordResetEmail(email.trim()).await(); _state.update { it.copy(notice = "If an account matches this email, password reset instructions have been requested. Check your inbox and spam folder.") } }
    fun signOut() { scope.launch { authGeneration.incrementAndGet(); cleanupListeners(); activeUser = null; groupList = emptyList(); auth?.signOut(); _state.value = GroupsState(configured = _state.value.configured) } }
    fun updateProfile(name: String, avatarId: String) = launchBusy {
        _state.update { it.copy(profileSaving = true) }
        try {
            publishMutex.withLock {
                val u = authRequired().currentUser ?: error("Sign in first.")
                val clean = validName(name)
                require(PresetAvatars.isValid(avatarId)) { "Choose an approved avatar." }
                val generation = authGeneration.get()
                val profile = GroupUser(u.uid, clean, avatarId)
                // Firestore is authoritative: never report a rejected choice as saved.
                cloudOperation("Save profile users/${u.uid}") {
                    dbRequired().collection("users").document(u.uid)
                        .set(mapOf("name" to clean, "avatarId" to avatarId), SetOptions.merge()).await()
                }
                if (authRequired().currentUser?.uid != u.uid || generation != authGeneration.get()) return@withLock
                activeUser = profile
                _state.update { it.copy(user = profile) }
                // Auth display name is only a fallback; its failure must not undo the saved avatar.
                try { u.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = clean }).await() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Canonical profile above is already persisted. */ }
                try { publishCurrentLocked() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    _state.update { it.copy(notice = "Your profile was saved, but group sync failed. Retry Save to sync your groups.") }
                    throw e
                }
                if (authRequired().currentUser?.uid == u.uid && generation == authGeneration.get()) {
                    _state.update { it.copy(profileSaveVersion = it.profileSaveVersion + 1L, notice = "Profile saved.", error = null) }
                }
            }
        } finally { _state.update { it.copy(profileSaving = false) } }
    }
    fun createGroup(name: String) = launchBusy {
        val u = requireUser(); val clean = validName(name); val code = (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val ref = dbRequired().collection("groups").document(code)
        dbRequired().runTransaction { tx -> tx.set(ref, mapOf("name" to clean, "memberIds" to listOf(u.uid), "createdAt" to FieldValue.serverTimestamp())); null }.await()
        groupList = groupList + FocusGroup(code, clean, listOf(u.uid)); attachGroups(groupList)
        selectGroup(code)
        publishCurrent()
    }
    fun joinGroup(code: String) = launchBusy {
        val u = requireUser(); val id = code.trim().uppercase(Locale.ROOT); require(id.matches(Regex("[A-Z0-9]{12}"))) { "Enter a 12-character group code." }
        val ref = dbRequired().collection("groups").document(id)
        dbRequired().runTransaction { tx ->
            val snap = tx.get(ref); check(snap.exists()) { "Group code not found." }
            val members = (snap.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            check(members.contains(u.uid) || members.size < MAX_GROUP_SIZE) { "This group is full." }
            if (!members.contains(u.uid)) tx.update(ref, "memberIds", members + u.uid); null
        }.await()
        refreshGroups(); selectGroup(id); publishCurrent()
    }
    fun selectGroup(id: String) { val g = groupList.firstOrNull { it.id == id } ?: return; _state.value = _state.value.copy(selectedGroup = g, members = emptyList(), loading = true); attachMembers(g) }
    fun leaveGroup() = launchBusy {
        val u = requireUser(); val g = _state.value.selectedGroup ?: error("Select a group first."); val ref = dbRequired().collection("groups").document(g.id)
        dbRequired().runTransaction { tx ->
            val snap = tx.get(ref); check(snap.exists()) { "Group no longer exists." }
            val members = (snap.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (members.contains(u.uid)) tx.update(ref, "memberIds", members - u.uid); null
        }.await()
        runCatching { ref.collection("members").document(u.uid).delete().await() }
        refreshGroups()
    }
    fun clearError() { _state.update { it.copy(error = null, notice = null) } }

    private suspend fun acceptAuth(uid: String?, name: String?) {
        val generation = authGeneration.incrementAndGet()
        cleanupListeners()
        if (uid == null || authRequired().currentUser?.uid != uid) { activeUser = null; groupList = emptyList(); _state.value = GroupsState(configured = _state.value.configured); return }
        val profileDoc = runCatching { dbRequired().collection("users").document(uid).get().await() }.getOrNull()
        val user = GroupUser(uid, (profileDoc?.getString("name") ?: name).orEmpty().ifBlank { "Focus member" }, PresetAvatars.fallback(profileDoc?.get("avatarId") as? String))
        if (generation != authGeneration.get() || authRequired().currentUser?.uid != uid) return
        activeUser = user; _state.update { it.copy(user = user, loading = true) }
        refreshGroups()
        syncJob?.cancel(); syncJob = scope.launch {
            var initial = true
            val heartbeat = launch { while (isActive) { delay(30_000L); publishCurrent() } }
            val rollover = launch { while (isActive) { delay(60_000L); publishCurrent() } }
            try { combine(timer.state, sessions.sessionsSince(0L)) { ts, ss -> ts to ss }.collect { (ts, ss) ->
                if (generation != authGeneration.get() || authRequired().currentUser?.uid != uid) return@collect
                val known = ownership.all.keys.filter { it.startsWith("owner_") }.mapNotNull { it.removePrefix("owner_").toLongOrNull() }.toSet()
                if (initial) { ss.forEach { if (it.id !in known) ownership.edit().putString("owner_${it.id}", "unassigned").apply() }; initial = false }
                else ss.forEach { if (it.id !in known) ownership.edit().putString("owner_${it.id}", uid).apply() }
                publishCurrent(ts, ss)
            } } finally { heartbeat.cancel(); rollover.cancel() }
        }
    }
    private suspend fun refreshGroups() {
        val u = activeUser ?: return
        try {
            val docs = dbRequired().collection("groups").whereArrayContains("memberIds", u.uid).get().await()
            groupList = docs.documents.mapNotNull { d -> d.getString("name")?.let { FocusGroup(d.id, it, (d.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()) } }
            attachGroups(groupList)
            publishCurrent()
        } catch (e: Exception) { setError(e); _state.value = _state.value.copy(loading = false) }
    }
    private fun attachGroups(groups: List<FocusGroup>) {
        groupListener?.remove(); val listenerUid = activeUser?.uid ?: return
        groupListener = db?.collection("groups")?.whereArrayContains("memberIds", listenerUid)?.addSnapshotListener { snap, err ->
            if (authRequired().currentUser?.uid != listenerUid || activeUser?.uid != listenerUid) return@addSnapshotListener
            if (err != null) { setError(err); _state.value = _state.value.copy(loading = false); return@addSnapshotListener }
            groupList = snap?.documents.orEmpty().mapNotNull { d -> d.getString("name")?.let { FocusGroup(d.id, it, (d.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()) } }
            val previous = _state.value.selectedGroup
            val oldId = previous?.id
            val chosen = groupList.firstOrNull { it.id == oldId } ?: groupList.firstOrNull()
            _state.update { it.copy(groups = groupList, selectedGroup = chosen, loading = false) }
            if (chosen != null && (chosen.id != oldId || chosen.memberIds != previous?.memberIds)) attachMembers(chosen)
            else if (chosen == null) { clearMemberListeners(); _state.update { it.copy(members = emptyList(), loading = false) } }
        }
    }
    private fun attachMembers(group: FocusGroup) {
        clearMemberListeners(); selectedMembers = emptyList()
        val u = activeUser ?: return
        memberListeners += dbRequired().collection("groups").document(group.id).collection("members").addSnapshotListener { snap, err ->
            if (authRequired().currentUser?.uid != u.uid || activeUser?.uid != u.uid) return@addSnapshotListener
            if (err != null) { setError(err); _state.update { it.copy(loading = false) }; return@addSnapshotListener }
            selectedMembers = snap?.documents.orEmpty().mapNotNull { d ->
                val uid = d.id; if (uid !in group.memberIds) return@mapNotNull null; val week = d.getString("weekKey").orEmpty(); val liveMap = d.get("live") as? Map<*, *>
                GroupMember(uid, d.getString("name").orEmpty(), if (week == weekKey(System.currentTimeMillis())) d.getLong("weeklyFocusMs") ?: 0L else 0L, liveMap?.let(::decodeTimer), d.getTimestamp("updatedAt")?.toDate()?.time ?: 0L, PresetAvatars.fallback(d.get("avatarId") as? String))
            }.sortedWith(compareByDescending<GroupMember> { it.weeklyFocusMs }.thenBy { it.name.lowercase() })
            if (_state.value.selectedGroup?.id == group.id) _state.update { it.copy(members = selectedMembers, loading = false) }
        }
    }
    private suspend fun writeMember(groupId: String, user: GroupUser, total: Long, week: Long = weekStartUtc(System.currentTimeMillis()), live: TimerState = timer.state.value) {
        if (authRequired().currentUser?.uid != user.uid || activeUser != user) return
        cloudOperation("Sync group groups/$groupId/members/${user.uid}") {
            // This is an app-owned snapshot, not a partial update. Replace it so obsolete
            // legacy keys cannot survive merge and violate the rules' allowed-key list.
            dbRequired().collection("groups").document(groupId).collection("members").document(user.uid)
                .set(mapOf("name" to user.name, "avatarId" to user.avatarId, "photoUrl" to "", "weeklyFocusMs" to total, "weekKey" to weekKey(week), "live" to encodeTimer(live), "updatedAt" to FieldValue.serverTimestamp())).await()
        }
    }
    private suspend fun publishCurrent(ts: TimerState = timer.state.value, rows: List<com.flowstate.focus.data.db.SessionEntity>? = null) {
        try { publishMutex.withLock { publishCurrentLocked(ts, rows) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { setError(e) }
    }
    // All callers hold publishMutex; resolve activeUser only after acquiring it.
    private suspend fun publishCurrentLocked(ts: TimerState = timer.state.value, rows: List<com.flowstate.focus.data.db.SessionEntity>? = null) {
        val uid = authRequired().currentUser?.uid ?: return
        val user = activeUser?.takeIf { it.uid == uid } ?: return
        val allRows = rows ?: runCatching { sessions.all() }.getOrDefault(emptyList())
        val owned = allRows.filter { ownership.getString("owner_${it.id}", null) == uid }
        val week = weeklyWeekStartUtc(System.currentTimeMillis())
        val total = weeklyTotal(owned.map { it.startedAt to it.durationMs }, System.currentTimeMillis())
        groupList.forEach { g -> if (uid == authRequired().currentUser?.uid) writeMember(g.id, user, total, week, ts) }
    }
    private fun encodeTimer(t: TimerState): Map<String, Any> = mapOf("mode" to t.mode.name, "phase" to t.phase.name, "status" to t.status.name, "phaseDurationMs" to t.phaseDurationMs, "phaseStartedAt" to t.phaseStartedAt, "pausedAt" to t.pausedAt, "subject" to t.subject.take(80))
    private fun decodeTimer(m: Map<*, *>): TimerState? = runCatching { TimerState(mode = com.flowstate.focus.domain.TimerMode.valueOf(m["mode"] as String), phase = Phase.valueOf(m["phase"] as String), status = com.flowstate.focus.domain.Status.valueOf(m["status"] as String), phaseDurationMs = (m["phaseDurationMs"] as Number).toLong(), phaseStartedAt = (m["phaseStartedAt"] as Number).toLong(), pausedAt = (m["pausedAt"] as? Number)?.toLong() ?: 0, subject = m["subject"] as? String ?: "") }.getOrNull()
    private fun cleanupListeners() { groupListener?.remove(); groupListener = null; clearMemberListeners(); syncJob?.cancel(); syncJob = null }
    private fun clearMemberListeners() { memberListeners.forEach { it.remove() }; memberListeners.clear() }
    private fun requireUser() = authRequired().currentUser ?: error("Sign in first.")
    private fun authRequired() = auth ?: error("Firebase is not configured.")
    private fun dbRequired() = db ?: error("Firebase is not configured.")
    private fun launchBusy(block: suspend () -> Unit) { scope.launch { _state.update { it.copy(busy = true, error = null, notice = null) }; try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { setError(e) } finally { _state.update { it.copy(busy = false, loading = false) } } } }
    private fun setError(t: Throwable) { _state.value = _state.value.copy(error = safeMessage(t)) }
    private suspend fun <T> cloudOperation(operation: String, block: suspend () -> T): T {
        try { return block() }
        catch (e: CancellationException) { throw e }
        catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                throw IllegalStateException("$operation was denied. Check the published 12-avatar rules in Firebase project ${readResource(R.string.flowstate_firebase_project_id)}, Cloud Firestore database (default). Your previous saved data has not been cleared.", e)
            }
            throw e
        }
    }
    private fun safeMessage(t: Throwable): String = when {
        t is FirebaseFirestoreException && t.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Cloud Firestore denied access in project ${readResource(R.string.flowstate_firebase_project_id)}, database (default). Check this account's membership and the published 12-avatar rules in that exact project."
        t is java.net.UnknownHostException || t is java.io.IOException -> "Network unavailable. Check your connection and try again."
        else -> t.localizedMessage?.takeIf { it.length <= 600 } ?: "Something went wrong. Please try again."
    }
    private fun readResource(id: Int): String = runCatching { context.getString(id).takeIf { it.isNotBlank() } ?: "" }.getOrDefault("")
    private fun validName(s: String): String = s.trim().also { require(it.length in 1..40 && it.none(Char::isISOControl)) { "Name must be 1–40 characters." } }
    private fun emailOk(s: String) = s.trim().length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(s.trim()).matches()
    private fun weekStartUtc(now: Long): Long = weeklyWeekStartUtc(now)
    private fun weekKey(ms: Long): String = weeklyWeekKey(ms)
    private companion object { const val APP_NAME="flowstate-groups"; const val MAX_GROUP_SIZE=20; const val WEEK_MS=7*24*60*60*1000L; const val ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }
}
