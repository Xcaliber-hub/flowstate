package com.flowstate.desktop

import com.flowstate.desktop.cloud.AuthClient
import com.flowstate.desktop.cloud.CloudController
import com.flowstate.desktop.store.DesktopSettings
import com.flowstate.desktop.store.DeviceIdentity
import com.flowstate.desktop.store.SessionStore
import com.flowstate.desktop.store.SettingsStore
import com.flowstate.desktop.store.SyncPrefsStore
import com.flowstate.desktop.store.TaskStore
import com.flowstate.desktop.timer.DesktopTimerEngine
import com.flowstate.desktop.timer.TimerAlerts
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.TimerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Screen(val label: String) { FOCUS("Focus"), STATS("Stats"), TASKS("Tasks"), GROUPS("Groups"), SETTINGS("Settings") }

/** What the user picked on the idle Focus screen. Null duration means "the default from Settings". */
data class FocusSelection(val mode: TimerMode = TimerMode.POMODORO, val durationMin: Int? = null, val strict: Boolean? = null, val subject: String = "")

/** Wires the stores, timer and cloud together. One per process. */
class AppModel(alerts: TimerAlerts) {
    val settingsStore = SettingsStore()
    val sessions = SessionStore()
    val tasks = TaskStore()
    val syncPrefs = SyncPrefsStore()
    val auth = AuthClient()
    val engine = DesktopTimerEngine(alerts, settingsStore) { row ->
        // A session belongs to the account signed in when it was recorded; signed out, it stays unassigned.
        sessions.insert(row.copy(ownerUid = auth.session?.uid, origin = DeviceIdentity.deviceId))
    }
    val cloud = CloudController(engine, sessions, syncPrefs, auth)

    val screen = MutableStateFlow(Screen.FOCUS)
    /** A newer Windows installer on GitHub, checked once at launch. */
    val update = MutableStateFlow<DesktopUpdate?>(null)

    init {
        Thread { update.value = UpdateCheck.latest() }.apply { isDaemon = true }.start()
    }
    val selection = MutableStateFlow(FocusSelection())

    fun defaultMinutes(mode: TimerMode, s: DesktopSettings) = if (mode == TimerMode.COUNTDOWN) s.countdownMin else s.focusMin

    fun startFocus() {
        if (engine.state.value.isActive || cloud.state.value.remote != null) return
        val st = settingsStore.settings.value
        val sel = selection.value
        val minutes = sel.durationMin ?: defaultMinutes(sel.mode, st)
        val plan = when (sel.mode) {
            TimerMode.POMODORO -> Plan(minutes * 60_000L, st.shortBreakMin * 60_000L, st.longBreakMin * 60_000L, st.rounds)
            TimerMode.COUNTDOWN -> Plan(focusMs = minutes * 60_000L)
            TimerMode.STOPWATCH -> Plan()
        }
        engine.start(sel.mode, plan, sel.strict ?: st.strictByDefault, sel.subject.trim())
    }

    fun startBreak(minutes: Int) {
        if (engine.state.value.isActive || cloud.state.value.remote != null) return
        engine.startBreak(minutes.coerceIn(1, 60) * 60_000L, selection.value.mode)
    }

    fun startFocusFromBreak() { if (!engine.skipBreak()) startFocus() }

    fun setMode(mode: TimerMode) = selection.update { it.copy(mode = mode, durationMin = null) }
    fun setDuration(min: Int) = selection.update { it.copy(durationMin = min) }
    fun setSubject(subject: String) = selection.update { it.copy(subject = subject.take(40)) }
    fun setStrict(strict: Boolean) = selection.update { it.copy(strict = strict) }
}
