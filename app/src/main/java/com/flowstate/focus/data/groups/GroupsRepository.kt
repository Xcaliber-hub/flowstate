package com.flowstate.focus.data.groups

import android.util.Log
import android.content.Context
import com.flowstate.focus.R
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.groups.dailyTotalIst
import com.flowstate.focus.domain.groups.dayKeyIst
import com.flowstate.focus.domain.groups.weeklySessionCount
import com.flowstate.focus.domain.groups.weeklyTotal
import com.flowstate.focus.domain.groups.weeklyWeekKeyIst
import com.flowstate.focus.domain.groups.weeklyWeekStartIst
import com.flowstate.focus.domain.groups.PresetAvatars
import com.flowstate.focus.domain.groups.EmojiPings
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.TimerMode
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
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.Timestamp
import java.util.Date
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.withContext
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
 /** [weeklySessions] and [todayFocusMs] are null when the member's app predates them (shown as "—"). */
 data class GroupMember(val uid: String, val name: String, val weeklyFocusMs: Long, val live: TimerState?, val updatedAt: Long, val avatarId: String = "", val weeklySessions: Int? = null, val todayFocusMs: Long? = null)
 /** An emoji a group friend sent; shown once when the app opens, then cleared. */
 data class EmojiPing(val groupId: String, val groupName: String, val fromUid: String, val fromName: String, val fromAvatarId: String, val emoji: String, val sentAt: Long)
 /** The message a chat message answers, copied in so it shows even when the original is not loaded. */
 data class ChatReply(val id: String, val uid: String, val name: String, val text: String)
 /** One group chat message. [pending] is true until the server has accepted it. */
 /** Someone else writing in the open chat right now. */
 data class TypingMember(val uid: String, val name: String, val avatarId: String)
 data class ChatMessage(val id: String, val uid: String, val name: String, val avatarId: String, val text: String, val sentAt: Long, val pending: Boolean = false, val replyTo: ChatReply? = null, val edited: Boolean = false)
 /** The open group chat, newest message first. [unreadGroups] drives the dot on the Chat button. */
 data class ChatState(val groupId: String? = null, val messages: List<ChatMessage> = emptyList(), val loaded: Boolean = false, val loadingOlder: Boolean = false, val reachedStart: Boolean = false, val error: String? = null, val unreadGroups: Set<String> = emptySet(), val lastMessages: Map<String, ChatMessage> = emptyMap(),
    /** Open chat only: when each member last read it (uid to server time in ms), for "Seen by". */
    val seenBy: Map<String, Long> = emptyMap(),
    /** New messages per group since you last had its chat open, for the count on the Chat button. */
    val unreadCounts: Map<String, Int> = emptyMap(),
    /** Open chat only: other members typing right now, for the typing bubble. */
    val typing: List<TypingMember> = emptyList())
 data class GroupsState(val incomingPings: List<EmojiPing> = emptyList(), val configured: Boolean = false, val user: GroupUser? = null, val groups: List<FocusGroup> = emptyList(), val selectedGroup: FocusGroup? = null, val members: List<GroupMember> = emptyList(), val loading: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null, val profileSaving: Boolean = false, val profileSaveVersion: Long = 0L)

@Singleton
class GroupsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timer: TimerEngine,
    private val sessions: SessionRepository,
    private val account: com.flowstate.focus.data.sync.AccountHolder,
    private val timerLock: com.flowstate.focus.data.sync.TimerLockState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(GroupsState())
    val state: StateFlow<GroupsState> = _state.asStateFlow()
    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()
    // Snapshot callbacks run on the main thread; sign-out may clear these from IO, hence the concurrent map.
    private var chatListener: ListenerRegistration? = null
    private var readsListener: ListenerRegistration? = null
    // Typing (1.4.7): groups/{gid}/typing/{uid} = { name, avatarId, at }. Each entry is kept alive by
    // the typer rewriting it every few seconds; here it expires TYPING_STALE_MS after we last heard of it,
    // timed on this phone's own clock so a wrong clock on either phone can't freeze or hide it.
    private var typingListener: ListenerRegistration? = null
    private val typingHeard = java.util.concurrent.ConcurrentHashMap<String, Pair<TypingMember, Long>>()
    private var typingTicker: kotlinx.coroutines.Job? = null
    @Volatile private var lastTypingWrite = 0L
    @Volatile private var typingDocGroup: String? = null
    /** Newest message time already written to my reads doc, so reading never writes twice for one message. */
    private var readWrittenUpTo = 0L
    /** The chat screen is on screen (not just open behind another app); only then do I mark messages seen. */
    @Volatile private var chatVisible = false

    /** The signed-in user's id, or null when signed out. */
    val currentUid: String? get() = activeUser?.uid
    /** The Firestore instance of the named Firebase app, for CloudSync (1.6.0). Null when unconfigured. */
    val firestore: FirebaseFirestore? get() = db
    // Run before Firebase signs out, while writes are still authorized (CloudSync releases the timer lock).
    private val signOutHooks = java.util.concurrent.CopyOnWriteArrayList<suspend (String) -> Unit>()
    fun addSignOutHook(hook: suspend (String) -> Unit) { signOutHooks += hook }
    /** True while [groupId]'s chat is the screen in front of the user, so its pushes stay quiet. */
    fun isChatOnScreen(groupId: String): Boolean = chatVisible && _chat.value.groupId == groupId

    /** Called by the chat screen as it starts and stops being visible. */
    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) _chat.value.groupId?.let { markChatSeen(it) } else clearTyping()
    }
    private val chatById = java.util.concurrent.ConcurrentHashMap<String, ChatMessage>()
    private var chatCursor: DocumentSnapshot? = null
    private val chatSeen = context.getSharedPreferences("group_chat_seen", Context.MODE_PRIVATE)
    private var auth: FirebaseAuth? = null
    private var db: FirebaseFirestore? = null
    private var groupListener: ListenerRegistration? = null
    private var memberListeners = mutableListOf<ListenerRegistration>()
    private val pingListeners = mutableMapOf<String, ListenerRegistration>()
    private val pingsByGroup = mutableMapOf<String, List<EmojiPing>>()
    private var selectedMembers = emptyList<GroupMember>()
    private var syncJob: kotlinx.coroutines.Job? = null
    private var activeUser: GroupUser? = null
    private var groupList = emptyList<FocusGroup>()
    private val authGeneration = AtomicLong(0L)
    // A heartbeat must never overwrite a just-saved avatar with its old snapshot.
    private val publishMutex = Mutex()
    private val random = SecureRandom()
    private val ownership = context.getSharedPreferences("group_session_owners", Context.MODE_PRIVATE)
    // Until the 1.1.4 rules are published, Firestore rejects the new member fields. Fall back to the
    // 1.1.3 payload so presence and weekly totals keep syncing, and retry the full one later.
    @Volatile private var legacyMemberPayloadUntil = 0L
    /** Where the published live timer runs ("android" or "windows"); an extra key the validLive rule allows. */
    @Volatile private var livePlatform: String = com.flowstate.focus.data.sync.PLATFORM_ANDROID

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
    fun signOut() { scope.launch {
        activeUser?.uid?.let { old -> signOutHooks.forEach { hook -> runCatching { hook(old) } } }
        authGeneration.incrementAndGet(); cleanupListeners(); activeUser = null; account.uid = null; groupList = emptyList(); auth?.signOut(); _state.value = GroupsState(configured = _state.value.configured)
    } }
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
    /** Sends [emoji] to [toUid] in [groupId]. A newer ping to the same friend replaces the older one. */
    fun sendEmoji(groupId: String, toUid: String, emoji: String) = scope.launch {
        try {
            val u = requireUser(); val me = activeUser?.takeIf { it.uid == u.uid } ?: error("Sign in first.")
            require(emoji in EmojiPings.ALL) { "Choose one of the emojis." }
            require(toUid != u.uid) { "You can't send an emoji to yourself." }
            dbRequired().collection("groups").document(groupId).collection("pings").document("${u.uid}_$toUid")
                .set(mapOf("fromUid" to u.uid, "fromName" to me.name, "fromAvatarId" to me.avatarId, "toUid" to toUid, "emoji" to emoji, "sentAt" to FieldValue.serverTimestamp())).await()
        } catch (e: CancellationException) { throw e }
        catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) _state.update { it.copy(error = "Couldn't send the emoji. Publish the 1.4.3 Firestore rules in project ${readResource(R.string.flowstate_firebase_project_id)} so emoji pings are allowed.") }
            else setError(e)
        } catch (e: Exception) { setError(e) }
    }
    // ---- Group chat (1.4.5) ----
    // groups/{gid}/messages/{auto-id}. Messages stay forever; the chat listens to the newest
    // CHAT_PAGE and fetches older pages only as you scroll up, which keeps reads low on Spark.

    /** Starts listening to [groupId]'s newest messages. Call when the chat screen opens. */
    fun openChat(groupId: String) {
        val uid = activeUser?.uid ?: return
        if (_chat.value.groupId == groupId && chatListener != null) return
        closeChat()
        com.flowstate.focus.push.ChatNotifications.clear(context, groupId)
        _chat.update { it.copy(groupId = groupId, messages = emptyList(), loaded = false, reachedStart = false, error = null, unreadGroups = it.unreadGroups - groupId, unreadCounts = it.unreadCounts - groupId) }
        chatListener = messagesRef(groupId).orderBy("sentAt", Query.Direction.DESCENDING).limit(CHAT_PAGE)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (activeUser?.uid != uid || _chat.value.groupId != groupId) return@addSnapshotListener
                if (err != null) {
                    Log.w("Flowstate", "Group chat listener failed for $groupId", err)
                    _chat.update { it.copy(error = chatError(err), loaded = true) }
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documents
                val windowOldest = docs.mapNotNull { decodeMessage(it)?.sentAt }.minOrNull() ?: Long.MIN_VALUE
                snap.documentChanges.forEach { change ->
                    val msg = decodeMessage(change.document) ?: return@forEach
                    when (change.type) {
                        // A message that slides out of the newest-50 window is not deleted; keep it.
                        // One removed from inside the window really was deleted.
                        DocumentChange.Type.REMOVED -> if (docs.size < CHAT_PAGE || msg.sentAt >= windowOldest) chatById.remove(msg.id)
                        else -> chatById[msg.id] = msg
                    }
                }
                docs.forEach { d -> decodeMessage(d)?.let { chatById[it.id] = it } }
                if (chatCursor == null) chatCursor = docs.lastOrNull { !it.metadata.hasPendingWrites() }
                val fromServer = !snap.metadata.isFromCache
                _chat.update { it.copy(loaded = true, error = null, reachedStart = it.reachedStart || (fromServer && docs.size < CHAT_PAGE)) }
                publishChat()
                markChatSeen(groupId)
            }
        // Read receipts: one tiny doc per member, groups/{gid}/reads/{uid} = { seenAt }.
        readsListener = readsRef(groupId).addSnapshotListener { snap, err ->
            if (activeUser?.uid != uid || _chat.value.groupId != groupId) return@addSnapshotListener
            if (err != null) { Log.w("Flowstate", "Read receipts listener failed for $groupId", err); return@addSnapshotListener }
            val seen = snap?.documents.orEmpty().mapNotNull { d ->
                d.getTimestamp("seenAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time?.let { d.id to it }
            }.toMap()
            _chat.update { it.copy(seenBy = seen) }
        }
        listenTyping(groupId, uid)
    }

    private fun listenTyping(groupId: String, uid: String) {
        typingListener = typingRef(groupId).addSnapshotListener { snap, err ->
            if (activeUser?.uid != uid || _chat.value.groupId != groupId) return@addSnapshotListener
            if (err != null) { Log.w("Flowstate", "Typing listener failed for $groupId", err); return@addSnapshotListener }
            val now = android.os.SystemClock.elapsedRealtime()
            for (c in snap?.documentChanges.orEmpty()) {
                val d = c.document
                if (d.id == uid) continue
                if (c.type == DocumentChange.Type.REMOVED) { typingHeard.remove(d.id); continue }
                if (d.metadata.hasPendingWrites()) continue
                // On first load, skip leftovers from someone whose app died mid-sentence.
                val at = d.getTimestamp("at")?.toDate()?.time
                if (c.type == DocumentChange.Type.ADDED && at != null && System.currentTimeMillis() - at > TYPING_LEFTOVER_MS) continue
                val who = TypingMember(d.id, d.getString("name").orEmpty().ifBlank { "Someone" }, PresetAvatars.fallback(d.getString("avatarId")))
                typingHeard[d.id] = who to now
            }
            publishTyping()
        }
        typingTicker = scope.launch {
            while (isActive) { delay(1_000); if (typingHeard.isNotEmpty() || _chat.value.typing.isNotEmpty()) publishTyping() }
        }
    }

    private fun publishTyping() {
        val now = android.os.SystemClock.elapsedRealtime()
        typingHeard.entries.removeIf { now - it.value.second > TYPING_STALE_MS }
        val list = typingHeard.values.sortedBy { it.second }.map { it.first }
        if (list != _chat.value.typing) _chat.update { it.copy(typing = list) }
    }

    /**
     * Called as the draft changes. A non-empty draft refreshes my typing entry at most every
     * TYPING_REFRESH_MS; an empty draft (or sending, or leaving) clears it straight away.
     */
    fun setTyping(groupId: String, typing: Boolean) {
        val me = activeUser ?: return
        if (_chat.value.groupId != groupId) return
        if (!typing) { clearTyping(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (typingDocGroup == groupId && now - lastTypingWrite < TYPING_REFRESH_MS) return
        lastTypingWrite = now; typingDocGroup = groupId
        typingRef(groupId).document(me.uid)
            .set(mapOf("name" to me.name, "avatarId" to me.avatarId, "at" to FieldValue.serverTimestamp()))
            .addOnFailureListener { e -> Log.w("Flowstate", "Typing update failed", e); if (typingDocGroup == groupId) typingDocGroup = null }
    }

    private fun clearTyping() {
        val gid = typingDocGroup ?: return
        val me = activeUser ?: return
        typingDocGroup = null; lastTypingWrite = 0L
        runCatching { typingRef(gid).document(me.uid).delete() }
    }

    /** Stops listening. Call when the chat screen closes. */
    fun closeChat() {
        clearTyping()
        chatListener?.remove(); chatListener = null
        readsListener?.remove(); readsListener = null
        typingListener?.remove(); typingListener = null
        typingTicker?.cancel(); typingTicker = null
        typingHeard.clear()
        chatById.clear(); chatCursor = null; readWrittenUpTo = 0L
        _chat.update { ChatState(unreadGroups = it.unreadGroups, lastMessages = it.lastMessages, unreadCounts = it.unreadCounts) }
    }

    /** Fetches the next page of older messages, once per scroll to the top. */
    fun loadOlderChat() {
        val groupId = _chat.value.groupId ?: return
        val cursor = chatCursor ?: return
        if (_chat.value.loadingOlder || _chat.value.reachedStart) return
        _chat.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val snap = messagesRef(groupId).orderBy("sentAt", Query.Direction.DESCENDING).startAfter(cursor).limit(CHAT_PAGE).get().await()
                withContext(Dispatchers.Main) {
                    if (_chat.value.groupId != groupId) return@withContext
                    snap.documents.forEach { d -> decodeMessage(d)?.let { chatById[it.id] = it } }
                    snap.documents.lastOrNull()?.let { chatCursor = it }
                    _chat.update { it.copy(reachedStart = snap.size() < CHAT_PAGE) }
                    publishChat()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _chat.update { it.copy(error = chatError(e)) } }
            finally { _chat.update { it.copy(loadingOlder = false) } }
        }
    }

    /** Sends [text] to [groupId], optionally as a reply to [replyTo]. It shows at once and is marked sent when the server accepts it. */
    fun sendChat(groupId: String, text: String, replyTo: ChatMessage? = null): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > CHAT_MAX_CHARS) return false
        val me = activeUser ?: return false
        val ref = messagesRef(groupId).document()
        val data = mutableMapOf<String, Any>("uid" to me.uid, "name" to me.name, "avatarId" to me.avatarId, "text" to clean, "sentAt" to FieldValue.serverTimestamp())
        if (replyTo != null) data["replyTo"] = mapOf(
            "id" to replyTo.id, "uid" to replyTo.uid, "name" to replyTo.name.take(40),
            "text" to replyTo.text.take(CHAT_REPLY_QUOTE_CHARS),
        )
        clearTyping()
        ref.set(data)
            .addOnSuccessListener { requestPush(groupId, ref.id) }
            .addOnFailureListener { e ->
                Log.w("Flowstate", "Group chat send failed", e)
                chatById.remove(ref.id); publishChat()
                _chat.update { it.copy(error = chatError(e)) }
            }
        return true
    }

    /** Changes the text of one of your own messages for everyone; it is marked "Edited". */
    fun editChat(groupId: String, messageId: String, text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > CHAT_MAX_CHARS) return false
        val me = activeUser ?: return false
        val old = chatById[messageId] ?: return false
        if (old.uid != me.uid) return false
        chatById[messageId] = old.copy(text = clean, edited = true); publishChat()
        messagesRef(groupId).document(messageId)
            .update(mapOf("text" to clean, "editedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { e ->
                Log.w("Flowstate", "Group chat edit failed", e)
                if (_chat.value.groupId == groupId && chatById.containsKey(messageId)) { chatById[messageId] = old; publishChat() }
                _chat.update { it.copy(error = chatError(e)) }
            }
        return true
    }

    /** Deletes one of your own messages for everyone. */
    fun deleteChat(groupId: String, messageId: String) {
        val removed = chatById.remove(messageId); publishChat()
        messagesRef(groupId).document(messageId).delete().addOnFailureListener { e ->
            if (removed != null && _chat.value.groupId == groupId) { chatById[messageId] = removed; publishChat() }
            _chat.update { it.copy(error = chatError(e)) }
        }
    }
    fun clearChatError() { _chat.update { it.copy(error = null) } }

    /**
     * Asks the push relay (a free Cloudflare Worker, see push-worker/) to notify the rest of the group.
     * The relay checks my Firebase ID token and reads the message itself, so nobody can push as someone else.
     * A blank flowstate_push_url leaves pushes off; chat itself works the same either way.
     */
    private fun requestPush(groupId: String, messageId: String) {
        val url = readResource(R.string.flowstate_push_url)
        if (url.isBlank()) return
        scope.launch {
            // 1.7.2: a dropped connection, a cold Worker (5xx) or an expired token used to lose the push
            // for good. Retry twice with a short backoff; a 401 retries once with a fresh ID token.
            var forceRefresh = false
            for (attempt in 0 until PUSH_ATTEMPTS) {
                val result = runCatching { postPush(url, groupId, messageId, forceRefresh) }
                val code = result.getOrNull()
                when {
                    code == null && result.isSuccess -> return@launch // signed out: nothing to send
                    code != null && code in 200..299 -> return@launch
                    code == 401 && !forceRefresh -> forceRefresh = true
                    code != null && code in 400..499 && code != 408 && code != 429 -> {
                        Log.w("Flowstate", "Push relay answered $code"); return@launch
                    }
                    else -> Log.w("Flowstate", "Push relay attempt ${attempt + 1} failed (${code ?: result.exceptionOrNull()})")
                }
                if (attempt < PUSH_ATTEMPTS - 1) kotlinx.coroutines.delay(2_000L * (attempt + 1))
            }
        }
    }

    /** One POST to the relay: the HTTP status, or null when signed out. */
    private suspend fun postPush(url: String, groupId: String, messageId: String, forceRefresh: Boolean): Int? {
        val token = authRequired().currentUser?.getIdToken(forceRefresh)?.await()?.token ?: return null
        val conn = (java.net.URL(url.trimEnd('/') + "/notify").openConnection() as java.net.HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 8_000; conn.readTimeout = 8_000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            val body = org.json.JSONObject().put("groupId", groupId).put("messageId", messageId).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun messagesRef(groupId: String) = dbRequired().collection("groups").document(groupId).collection("messages")
    private fun readsRef(groupId: String) = dbRequired().collection("groups").document(groupId).collection("reads")
    private fun typingRef(groupId: String) = dbRequired().collection("groups").document(groupId).collection("typing")
    private fun decodeMessage(d: DocumentSnapshot): ChatMessage? {
        val text = d.getString("text")?.takeIf { it.isNotBlank() } ?: return null
        // A just-sent message has no server time yet; estimate it so it sorts at the bottom.
        val sent = d.getTimestamp("sentAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: System.currentTimeMillis()
        val reply = (d.get("replyTo") as? Map<*, *>)?.let { r ->
            val id = r["id"] as? String ?: return@let null
            ChatReply(id, r["uid"] as? String ?: "", (r["name"] as? String)?.ifBlank { null } ?: "Focus member", r["text"] as? String ?: "")
        }
        return ChatMessage(d.id, d.getString("uid").orEmpty(), d.getString("name").orEmpty().ifBlank { "Focus member" },
            PresetAvatars.fallback(d.getString("avatarId")), text, sent, d.metadata.hasPendingWrites(), reply, edited = d.contains("editedAt"))
    }
    private fun publishChat() {
        _chat.update { it.copy(messages = chatById.values.sortedWith(compareByDescending<ChatMessage> { m -> m.sentAt }.thenByDescending { m -> m.id })) }
    }
    private fun markChatSeen(groupId: String) {
        if (!chatVisible) return
        val newest = chatById.values.maxOfOrNull { it.sentAt } ?: return
        if (newest > chatSeen.getLong(groupId, 0L)) chatSeen.edit().putLong(groupId, newest).apply()
        _chat.update { it.copy(unreadGroups = it.unreadGroups - groupId, unreadCounts = it.unreadCounts - groupId) }
        // Tell the group I've seen up to here, but only for someone else's message the server has accepted.
        val me = activeUser?.uid ?: return
        val newestOthers = chatById.values.filter { it.uid != me && !it.pending }.maxOfOrNull { it.sentAt } ?: return
        if (newestOthers <= readWrittenUpTo) return
        readWrittenUpTo = newestOthers
        readsRef(groupId).document(me).set(mapOf("seenAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { e -> Log.w("Flowstate", "Read receipt failed", e); readWrittenUpTo = 0L }
    }
    /** Watches only the newest message of [group] to light the unread dot (one read per message). */
    private fun attachChatBadge(group: FocusGroup, uid: String): ListenerRegistration =
        messagesRef(group.id).orderBy("sentAt", Query.Direction.DESCENDING).limit(1).addSnapshotListener { snap, err ->
            if (err != null || activeUser?.uid != uid) return@addSnapshotListener
            val latest = snap?.documents?.firstOrNull()?.let(::decodeMessage)
            val unread = latest != null && latest.uid != uid && _chat.value.groupId != group.id && latest.sentAt > chatSeen.getLong(group.id, 0L)
            _chat.update { it.copy(
                unreadGroups = if (unread) it.unreadGroups + group.id else it.unreadGroups - group.id,
                lastMessages = if (latest != null) it.lastMessages + (group.id to latest) else it.lastMessages - group.id,
                unreadCounts = if (unread) it.unreadCounts else it.unreadCounts - group.id,
            ) }
            if (unread) countUnread(group.id, uid)
        }

    /**
     * Counts messages newer than the last one you saw with a server-side count, which Firestore bills
     * as one read per 1,000 messages instead of one per message. Runs only when a new message lands.
     */
    private fun countUnread(groupId: String, uid: String) {
        val since = chatSeen.getLong(groupId, 0L)
        scope.launch {
            try {
                val n = messagesRef(groupId).whereGreaterThan("sentAt", Timestamp(Date(since)))
                    .count().get(AggregateSource.SERVER).await().count
                if (activeUser?.uid == uid && _chat.value.groupId != groupId && groupId in _chat.value.unreadGroups)
                    _chat.update { it.copy(unreadCounts = it.unreadCounts + (groupId to n.toInt().coerceAtLeast(1))) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("Flowstate", "Unread count failed for $groupId", e) }
        }
    }
    private fun chatError(t: Throwable): String =
        if (t is FirebaseFirestoreException && t.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
            "Chat isn't allowed yet. Publish the 1.4.8 Firestore rules in project ${readResource(R.string.flowstate_firebase_project_id)}."
        else safeMessage(t)

    /** Marks a ping as seen: it is deleted so it never shows again. */
    fun dismissPing(ping: EmojiPing) {
        val uid = activeUser?.uid ?: return
        // The same friend can have a ping waiting in more than one shared group; clear them all.
        val groups = pingsByGroup.filterValues { list -> list.any { it.fromUid == ping.fromUid } }.keys.toList()
        groups.forEach { id -> pingsByGroup[id] = pingsByGroup[id].orEmpty().filterNot { it.fromUid == ping.fromUid } }
        publishPings()
        scope.launch {
            groups.forEach { id -> runCatching { dbRequired().collection("groups").document(id).collection("pings").document("${ping.fromUid}_$uid").delete().await() } }
        }
    }
    fun clearError() { _state.update { it.copy(error = null, notice = null) } }

    private suspend fun acceptAuth(uid: String?, name: String?) {
        val generation = authGeneration.incrementAndGet()
        cleanupListeners()
        if (uid == null || authRequired().currentUser?.uid != uid) { activeUser = null; account.uid = null; groupList = emptyList(); _state.value = GroupsState(configured = _state.value.configured); return }
        account.uid = uid
        val profileDoc = runCatching { dbRequired().collection("users").document(uid).get().await() }.getOrNull()
        val user = GroupUser(uid, (profileDoc?.getString("name") ?: name).orEmpty().ifBlank { "Focus member" }, PresetAvatars.fallback(profileDoc?.get("avatarId") as? String))
        if (generation != authGeneration.get() || authRequired().currentUser?.uid != uid) return
        activeUser = user; _state.update { it.copy(user = user, loading = true) }
        refreshGroups()
        syncJob?.cancel(); syncJob = scope.launch {
            var initial = true
            val heartbeat = launch { while (isActive) { delay(30_000L); publishCurrent() } }
            val rollover = launch { while (isActive) { delay(60_000L); publishCurrent() } }
            // The other device's timer (1.6.0) is part of the key, so presence follows a take-over at once.
            try { combine(timer.state, sessions.sessionsSince(0L), timerLock.remote) { ts, ss, _ -> ts to ss }.collect { (ts, ss) ->
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
            attachPings(groupList, listenerUid)
            val previous = _state.value.selectedGroup
            val oldId = previous?.id
            val chosen = groupList.firstOrNull { it.id == oldId } ?: groupList.firstOrNull()
            _state.update { it.copy(groups = groupList, selectedGroup = chosen, loading = false) }
            if (chosen != null && (chosen.id != oldId || chosen.memberIds != previous?.memberIds)) attachMembers(chosen)
            else if (chosen == null) { clearMemberListeners(); _state.update { it.copy(members = emptyList(), loading = false) } }
        }
    }
    /** Listens for emoji pings addressed to this user in every group they are in. */
    private fun attachPings(groups: List<FocusGroup>, uid: String) {
        val ids = groups.map { it.id }.toSet()
        (pingListeners.keys - ids).forEach { id -> pingListeners.remove(id)?.remove(); pingsByGroup.remove(id) }
        groups.filter { it.id !in pingListeners }.forEach { g ->
            pingListeners[g.id] = dbRequired().collection("groups").document(g.id).collection("pings").whereEqualTo("toUid", uid)
                .addSnapshotListener { snap, err ->
                    if (activeUser?.uid != uid) return@addSnapshotListener
                    if (err != null) {
                        // A failed listener never recovers on its own (for example, it started before the
                        // 1.4.3 rules were published). Drop it and try again shortly.
                        Log.w("Flowstate", "Emoji ping listener failed for group ${g.id}", err)
                        pingListeners.remove(g.id)?.remove()
                        scope.launch(Dispatchers.Main) { delay(PING_RETRY_MS); if (activeUser?.uid == uid && g.id !in pingListeners) attachPings(groupList, uid) }
                        return@addSnapshotListener
                    }
                    pingsByGroup[g.id] = snap?.documents.orEmpty().mapNotNull { d ->
                        val emoji = d.getString("emoji")?.takeIf { it in EmojiPings.ALL } ?: return@mapNotNull null
                        EmojiPing(g.id, g.name, d.getString("fromUid").orEmpty(), d.getString("fromName").orEmpty().ifBlank { "A friend" },
                            PresetAvatars.fallback(d.getString("fromAvatarId")), emoji, d.getTimestamp("sentAt")?.toDate()?.time ?: System.currentTimeMillis())
                    }
                    publishPings()
                }
        }
        publishPings()
    }
    private fun publishPings() {
        // A friend in two groups may have pinged in both; show only their newest.
        val all = pingsByGroup.values.flatten().groupBy { it.fromUid }.values.mapNotNull { list -> list.maxByOrNull { it.sentAt } }.sortedBy { it.sentAt }
        _state.update { it.copy(incomingPings = all) }
    }
    /** Re-listens for pings from scratch; called whenever the app comes to the foreground. */
    fun refreshPings() {
        val uid = activeUser?.uid ?: return
        pingListeners.values.forEach { it.remove() }; pingListeners.clear()
        attachPings(groupList, uid)
    }
    private fun clearPingListeners() { pingListeners.values.forEach { it.remove() }; pingListeners.clear(); pingsByGroup.clear() }
    private fun attachMembers(group: FocusGroup) {
        clearMemberListeners(); selectedMembers = emptyList()
        val u = activeUser ?: return
        memberListeners += attachChatBadge(group, u.uid)
        memberListeners += dbRequired().collection("groups").document(group.id).collection("members").addSnapshotListener { snap, err ->
            if (authRequired().currentUser?.uid != u.uid || activeUser?.uid != u.uid) return@addSnapshotListener
            if (err != null) { setError(err); _state.update { it.copy(loading = false) }; return@addSnapshotListener }
            selectedMembers = snap?.documents.orEmpty().mapNotNull { d ->
                val uid = d.id; if (uid !in group.memberIds) return@mapNotNull null; val week = d.getString("weekKey").orEmpty(); val liveMap = d.get("live") as? Map<*, *>
                val nowMs = System.currentTimeMillis(); val thisWeek = week == weekKey(nowMs)
                // Missing fields mean an older app version: keep null so the UI shows "—". A stale
                // week or day means nothing was focused since, so it reads as zero.
                val sessionsThisWeek = d.getLong("weeklySessions")?.let { if (thisWeek) it.toInt().coerceAtLeast(0) else 0 }
                val today = d.getLong("todayFocusMs")?.let { if (d.getString("dayKey") == dayKeyIst(nowMs)) it.coerceAtLeast(0L) else 0L }
                GroupMember(uid, d.getString("name").orEmpty(), if (thisWeek) d.getLong("weeklyFocusMs") ?: 0L else 0L, liveMap?.let(::decodeTimer), d.getTimestamp("updatedAt")?.toDate()?.time ?: 0L, PresetAvatars.fallback(d.get("avatarId") as? String), sessionsThisWeek, today)
            }.sortedWith(compareByDescending<GroupMember> { it.weeklyFocusMs }.thenBy { it.name.lowercase() })
            if (_state.value.selectedGroup?.id == group.id) _state.update { it.copy(members = selectedMembers, loading = false) }
        }
    }
    private suspend fun writeMember(groupId: String, user: GroupUser, total: Long, week: Long = weekStartIst(System.currentTimeMillis()), live: TimerState = timer.state.value, weeklySessions: Int = 0, todayMs: Long = 0L, dayKey: String = dayKeyIst(System.currentTimeMillis())) {
        if (authRequired().currentUser?.uid != user.uid || activeUser != user) return
        val doc = dbRequired().collection("groups").document(groupId).collection("members").document(user.uid)
        // This is an app-owned snapshot, not a partial update. Replace it so obsolete
        // legacy keys cannot survive merge and violate the rules' allowed-key list.
        val legacy = mapOf("name" to user.name, "avatarId" to user.avatarId, "photoUrl" to "", "weeklyFocusMs" to total, "weekKey" to weekKey(week), "live" to encodeTimer(live), "updatedAt" to FieldValue.serverTimestamp())
        if (System.currentTimeMillis() >= legacyMemberPayloadUntil) {
            try {
                doc.set(legacy + mapOf("weeklySessions" to weeklySessions.coerceAtLeast(0), "todayFocusMs" to todayMs.coerceAtLeast(0L), "dayKey" to dayKey)).await()
                return
            } catch (e: CancellationException) { throw e }
            catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                legacyMemberPayloadUntil = System.currentTimeMillis() + LEGACY_RETRY_MS
            }
        }
        cloudOperation("Sync group groups/$groupId/members/${user.uid}") { doc.set(legacy).await() }
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
        // Merged history (1.6.0): this phone's sessions plus those synced from the account's other devices.
        val owned = allRows.filter { it.ownerUid == uid || (it.ownerUid == null && ownership.getString("owner_${it.id}", null) == uid) }
        val week = weeklyWeekStartIst(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val intervals = owned.map { it.startedAt to it.durationMs }
        val total = weeklyTotal(intervals, now)
        val count = weeklySessionCount(owned.map { it.startedAt }, now)
        val today = dailyTotalIst(intervals, now)
        val dayKey = dayKeyIst(now)
        // While another device runs the timer, publish its live state so this phone never overwrites it with "idle".
        val remote = timerLock.remote.value
        val live = if (!ts.isActive && remote != null) remote.state else ts
        livePlatform = if (!ts.isActive && remote != null) remote.platform else com.flowstate.focus.data.sync.PLATFORM_ANDROID
        groupList.forEach { g -> if (uid == authRequired().currentUser?.uid) writeMember(g.id, user, total, week, live, count, today, dayKey) }
    }
    private fun encodeTimer(t: TimerState): Map<String, Any> = mapOf("mode" to t.mode.name, "phase" to t.phase.name, "status" to t.status.name, "phaseDurationMs" to t.phaseDurationMs, "phaseStartedAt" to t.phaseStartedAt, "pausedAt" to t.pausedAt, "subject" to t.subject.take(80), "platform" to livePlatform)
    private fun decodeTimer(m: Map<*, *>): TimerState? = runCatching { TimerState(mode = TimerMode.fromName(m["mode"] as? String), phase = Phase.valueOf(m["phase"] as String), status = com.flowstate.focus.domain.Status.valueOf(m["status"] as String), phaseDurationMs = (m["phaseDurationMs"] as Number).toLong(), phaseStartedAt = (m["phaseStartedAt"] as Number).toLong(), pausedAt = (m["pausedAt"] as? Number)?.toLong() ?: 0, subject = m["subject"] as? String ?: "") }.getOrNull()
    private fun cleanupListeners() { groupListener?.remove(); groupListener = null; clearMemberListeners(); clearPingListeners(); closeChat(); _chat.value = ChatState(); syncJob?.cancel(); syncJob = null }
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
    private fun weekStartIst(now: Long): Long = weeklyWeekStartIst(now)
    private fun weekKey(ms: Long): String = weeklyWeekKeyIst(ms)
    private companion object { const val APP_NAME="flowstate-groups"; const val MAX_GROUP_SIZE=20; const val WEEK_MS=7*24*60*60*1000L; const val ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; const val LEGACY_RETRY_MS=15*60_000L; const val PUSH_ATTEMPTS=3 }
}

private const val PING_RETRY_MS = 15_000L
private const val CHAT_PAGE = 50L
/** How often a typer rewrites their typing entry, and how long others keep showing it after the last rewrite. */
private const val TYPING_REFRESH_MS = 4_000L
private const val TYPING_STALE_MS = 7_000L
/** An entry older than this when the chat opens is a leftover, not someone typing. */
private const val TYPING_LEFTOVER_MS = 15_000L
internal const val CHAT_MAX_CHARS = 500
/** How much of the original a reply quotes. */
internal const val CHAT_REPLY_QUOTE_CHARS = 140
