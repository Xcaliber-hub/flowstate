package com.flowstate.desktop.cloud

import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState

data class CloudUser(val uid: String, val name: String, val avatarId: String, val email: String)
data class Group(val id: String, val name: String, val memberIds: List<String>)
data class Member(
    val uid: String,
    val name: String,
    val avatarId: String,
    val weeklyFocusMs: Long,
    val live: TimerState?,
    /** "android" or "windows" when the member's app says where the timer runs. */
    val livePlatform: String?,
    val updatedAt: Long,
    val weeklySessions: Int?,
    val todayFocusMs: Long?,
) {
    fun isLiveFocusing(now: Long) = now - updatedAt <= 90_000L && live?.isFocusInProgress == true
}
data class ChatReply(val id: String, val uid: String, val name: String, val text: String)
data class ChatMessage(val id: String, val uid: String, val name: String, val avatarId: String, val text: String, val sentAt: Long, val pending: Boolean = false, val replyTo: ChatReply? = null, val edited: Boolean = false)

/** The session another device of this account is running (users/{uid}/state/activeTimer). */
data class RemoteTimer(val deviceId: String, val deviceName: String, val platform: String, val state: TimerState, val updatedAt: Long) {
    val onPhone: Boolean get() = platform == "android"
    val deviceLabel: String get() = if (onPhone) "phone" else "PC"
}

data class ChatUi(
    val groupId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    /** uid to the server time they last read the chat, for "Seen by". */
    val seenBy: Map<String, Long> = emptyMap(),
)

data class CloudState(
    val configured: Boolean = false,
    val user: CloudUser? = null,
    val restoring: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val groups: List<Group> = emptyList(),
    val selectedGroupId: String? = null,
    val members: List<Member> = emptyList(),
    val lastMessages: Map<String, ChatMessage> = emptyMap(),
    val unreadGroups: Set<String> = emptySet(),
    val chat: ChatUi = ChatUi(),
    /** Set while another device holds the timer. */
    val remote: RemoteTimer? = null,
    /** True when the timer lock could not be read or written (e.g. rules not published yet). */
    val lockUnavailable: Boolean = false,
) {
    val selectedGroup: Group? get() = groups.firstOrNull { it.id == selectedGroupId }
}

// ---- Shared wire formats (identical to Android) ----

/** The live snapshot written into groups/{gid}/members/{uid}.live; "platform" is extra and allowed by validLive. */
fun encodeLive(t: TimerState, platform: String): Map<String, Any?> = mapOf(
    "mode" to t.mode.name, "phase" to t.phase.name, "status" to t.status.name,
    "phaseDurationMs" to t.phaseDurationMs, "phaseStartedAt" to t.phaseStartedAt, "pausedAt" to t.pausedAt,
    "subject" to t.subject.take(80), "platform" to platform,
)

fun decodeLive(m: Map<String, Any?>): TimerState? = runCatching {
    TimerState(
        mode = TimerMode.fromName(m["mode"] as? String),
        phase = Phase.valueOf(m["phase"] as String),
        status = Status.valueOf(m["status"] as String),
        phaseDurationMs = (m["phaseDurationMs"] as Number).toLong(),
        phaseStartedAt = (m["phaseStartedAt"] as Number).toLong(),
        pausedAt = (m["pausedAt"] as? Number)?.toLong() ?: 0L,
        subject = m["subject"] as? String ?: "",
    )
}.getOrNull()

/** users/{uid}/state/activeTimer, without updatedAt (set to the server time on write). */
fun encodeActiveTimer(t: TimerState, deviceId: String, deviceName: String, platform: String): Map<String, Any?> = mapOf(
    "deviceId" to deviceId, "deviceName" to deviceName.take(60), "platform" to platform,
    "status" to t.status.name, "phase" to t.phase.name, "mode" to t.mode.name, "subject" to t.subject.take(80),
    "phaseDurationMs" to t.phaseDurationMs, "phaseStartedAt" to t.phaseStartedAt, "pausedAt" to t.pausedAt,
    "endsAt" to if (t.status == Status.RUNNING && t.phaseDurationMs > 0) t.endAt else 0L,
    "remainingMs" to if (t.phaseDurationMs > 0) t.remainingAt(System.currentTimeMillis()) else 0L,
    "round" to t.round, "focusMs" to t.plan.focusMs, "shortBreakMs" to t.plan.shortBreakMs,
    "longBreakMs" to t.plan.longBreakMs, "rounds" to t.plan.rounds,
    "strict" to t.strict, "standaloneBreak" to t.standaloneBreak,
)

fun decodeActiveTimer(d: FsDoc): RemoteTimer? = runCatching {
    val state = TimerState(
        mode = TimerMode.fromName(d.string("mode")),
        phase = Phase.valueOf(d.string("phase") ?: "IDLE"),
        status = Status.valueOf(d.string("status") ?: "IDLE"),
        phaseDurationMs = d.long("phaseDurationMs") ?: 0L,
        phaseStartedAt = d.long("phaseStartedAt") ?: 0L,
        pausedAt = d.long("pausedAt") ?: 0L,
        strict = d.bool("strict") ?: false,
        subject = d.string("subject").orEmpty(),
        round = (d.long("round") ?: 0L).toInt(),
        plan = Plan(
            focusMs = d.long("focusMs") ?: Plan().focusMs,
            shortBreakMs = d.long("shortBreakMs") ?: Plan().shortBreakMs,
            longBreakMs = d.long("longBreakMs") ?: Plan().longBreakMs,
            rounds = (d.long("rounds") ?: Plan().rounds.toLong()).toInt(),
        ),
        standaloneBreak = d.bool("standaloneBreak") ?: false,
    )
    RemoteTimer(d.string("deviceId").orEmpty(), d.string("deviceName").orEmpty(), d.string("platform") ?: "android", state, d.millis("updatedAt") ?: 0L)
}.getOrNull()

/** A lock whose holder vanished: a fixed-length phase that ended long ago, or a pause left for half a day. */
fun RemoteTimer.isStale(now: Long): Boolean = when (state.status) {
    Status.IDLE -> true
    Status.RUNNING -> !state.countsUp && now - state.endAt > 10 * 60_000L
    Status.PAUSED -> now - updatedAt > 12 * 60 * 60_000L
}
