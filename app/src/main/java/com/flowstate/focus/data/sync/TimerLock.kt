package com.flowstate.focus.data.sync

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore(name = "device")
private val DEVICE_ID = stringPreferencesKey("device_id")

/** A stable id for this installation (DataStore), used by the timer lock and in synced session ids. */
@Singleton
class DeviceIdentity @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    suspend fun deviceId(): String = cached ?: mutex.withLock {
        cached ?: run {
            val saved = context.deviceStore.data.first()[DEVICE_ID]
            val id = saved ?: ("android-" + UUID.randomUUID().toString())
            if (saved == null) context.deviceStore.edit { it[DEVICE_ID] = id }
            cached = id
            id
        }
    }

    val deviceName: String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(60).ifBlank { "Phone" }
}

/**
 * The signed-in account id, readable without depending on GroupsRepository (TimerEngine stamps each
 * recorded session with it). Remembered across restarts so a session recorded at launch, before
 * Firebase Auth has restored, still gets its owner.
 */
@Singleton
class AccountHolder @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    @Volatile var uid: String? = prefs.getString("account_uid", null)
        set(value) {
            field = value
            prefs.edit().putString("account_uid", value).apply()
        }
}

/** The session another device of this account is running (users/{uid}/state/activeTimer). */
data class RemoteTimer(val deviceId: String, val deviceName: String, val platform: String, val state: TimerState, val updatedAt: Long) {
    val onPc: Boolean get() = platform == "windows"
    /** "PC" or "phone", for "Running on your PC". */
    val deviceLabel: String get() = if (onPc) "PC" else "phone"

    /** A lock whose holder vanished: a fixed-length phase that ended long ago, or a pause left for half a day. */
    fun isStale(now: Long): Boolean = when (state.status) {
        Status.IDLE -> true
        Status.RUNNING -> !state.countsUp && now - state.endAt > 10 * 60_000L
        Status.PAUSED -> now - updatedAt > 12 * 60 * 60_000L
    }
}

/** Shared between CloudSync (writer), GroupsRepository (live snapshot) and the Focus screen. */
@Singleton
class TimerLockState @Inject constructor() {
    /** Non-null while another device holds the timer. */
    val remote = MutableStateFlow<RemoteTimer?>(null)
    /** A one-off message for the Focus screen, e.g. "Your session moved to your PC." */
    val notice = MutableStateFlow<String?>(null)
}

const val PLATFORM_ANDROID = "android"

/** users/{uid}/state/activeTimer without updatedAt (the caller adds the server timestamp). Same format on Windows. */
fun encodeActiveTimer(t: TimerState, deviceId: String, deviceName: String, platform: String): Map<String, Any> = mapOf(
    "deviceId" to deviceId, "deviceName" to deviceName.take(60), "platform" to platform,
    "status" to t.status.name, "phase" to t.phase.name, "mode" to t.mode.name, "subject" to t.subject.take(80),
    "phaseDurationMs" to t.phaseDurationMs, "phaseStartedAt" to t.phaseStartedAt, "pausedAt" to t.pausedAt,
    "endsAt" to if (t.status == Status.RUNNING && t.phaseDurationMs > 0) t.endAt else 0L,
    "remainingMs" to if (t.phaseDurationMs > 0) t.remainingAt(System.currentTimeMillis()) else 0L,
    "round" to t.round.toLong(), "focusMs" to t.plan.focusMs, "shortBreakMs" to t.plan.shortBreakMs,
    "longBreakMs" to t.plan.longBreakMs, "rounds" to t.plan.rounds.toLong(),
    "strict" to t.strict, "standaloneBreak" to t.standaloneBreak,
)

fun decodeActiveTimer(m: Map<String, Any?>, updatedAt: Long): RemoteTimer? = runCatching {
    fun long(k: String) = (m[k] as? Number)?.toLong()
    val state = TimerState(
        mode = TimerMode.fromName(m["mode"] as? String),
        phase = Phase.valueOf(m["phase"] as? String ?: "IDLE"),
        status = Status.valueOf(m["status"] as? String ?: "IDLE"),
        phaseDurationMs = long("phaseDurationMs") ?: 0L,
        phaseStartedAt = long("phaseStartedAt") ?: 0L,
        pausedAt = long("pausedAt") ?: 0L,
        strict = m["strict"] as? Boolean ?: false,
        subject = m["subject"] as? String ?: "",
        round = (long("round") ?: 0L).toInt(),
        plan = Plan(
            focusMs = long("focusMs") ?: Plan().focusMs,
            shortBreakMs = long("shortBreakMs") ?: Plan().shortBreakMs,
            longBreakMs = long("longBreakMs") ?: Plan().longBreakMs,
            rounds = (long("rounds") ?: Plan().rounds.toLong()).toInt(),
        ),
        standaloneBreak = m["standaloneBreak"] as? Boolean ?: false,
    )
    RemoteTimer(m["deviceId"] as? String ?: "", m["deviceName"] as? String ?: "", m["platform"] as? String ?: PLATFORM_ANDROID, state, updatedAt)
}.getOrNull()
