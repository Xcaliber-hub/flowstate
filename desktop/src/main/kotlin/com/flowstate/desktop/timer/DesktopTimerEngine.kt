package com.flowstate.desktop.timer

import com.flowstate.desktop.store.AppDirs
import com.flowstate.desktop.store.JsonFile
import com.flowstate.desktop.store.SettingsStore
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerEvent
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.domain.normalizeCheckInMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the timer tells the user about: phase ends and inactivity check-ins. */
interface TimerAlerts {
    fun alert(event: TimerEvent)
    fun checkIn(timeoutMs: Long)
    fun cancelCheckIn()
    fun autoPaused()
}

@Serializable
private data class SavedTimer(
    val mode: String = "POMODORO",
    val phase: String = "IDLE",
    val status: String = "IDLE",
    val duration: Long = 0L,
    val started: Long = 0L,
    val pausedAt: Long = 0L,
    val strict: Boolean = false,
    val subject: String = "",
    val round: Int = 0,
    val pFocus: Long = Plan().focusMs,
    val pShort: Long = Plan().shortBreakMs,
    val pLong: Long = Plan().longBreakMs,
    val pRounds: Int = Plan().rounds,
    val standaloneBreak: Boolean = false,
    val checkInAt: Long = 0L,
    val autoPaused: Boolean = false,
)

/**
 * The Windows timer: a line-for-line port of the Android TimerEngine (absolute timestamps, one
 * coroutine sleeping until the phase ends, every change persisted, check-ins), plus [adopt] and
 * [releaseForHandoff] for moving a running session between devices.
 */
class DesktopTimerEngine(
    private val alerts: TimerAlerts,
    settings: SettingsStore,
    private val onRecord: (SessionEntity) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = JsonFile(AppDirs.file("timer.json"), SavedTimer.serializer()) { SavedTimer() }
    private val lock = Any()
    private var completionJob: Job? = null
    private var checkInJob: Job? = null
    @Volatile private var checkInsEnabled = true
    @Volatile private var checkInIntervalMs = 60 * 60_000L

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    init {
        restore()
        scope.launch {
            settings.settings.map { it.inactivityCheck to it.checkInMinutes }.distinctUntilChanged().collect { (enabled, minutes) ->
                synchronized(lock) {
                    checkInsEnabled = enabled
                    checkInIntervalMs = normalizeCheckInMinutes(minutes) * 60_000L
                    if (!enabled && _state.value.checkInAt > 0L) publish(_state.value.copy(checkInAt = 0L))
                    else scheduleCheckIn()
                }
            }
        }
    }

    fun confirmPresence() = synchronized(lock) {
        val s = _state.value
        if (s.checkInAt != 0L) publish(s.copy(checkInAt = 0L))
    }

    fun start(mode: TimerMode, plan: Plan, strict: Boolean, subject: String) = synchronized(lock) {
        if (_state.value.status != Status.IDLE) return@synchronized
        publish(
            TimerState(
                mode = mode, phase = Phase.FOCUS, status = Status.RUNNING,
                phaseDurationMs = if (mode.countsUp) 0L else plan.focusMs,
                phaseStartedAt = System.currentTimeMillis(), strict = strict, subject = subject, plan = plan,
            )
        )
        scheduleCompletion()
    }

    fun startBreak(durationMs: Long, mode: TimerMode) = synchronized(lock) {
        if (_state.value.status != Status.IDLE || durationMs <= 0L) return@synchronized
        publish(
            TimerState(
                mode = mode, phase = Phase.SHORT_BREAK, status = Status.RUNNING, phaseDurationMs = durationMs,
                phaseStartedAt = System.currentTimeMillis(), standaloneBreak = true,
            )
        )
        scheduleCompletion()
    }

    /** Ends the current break; inside a Pomodoro cycle the next focus round starts (returns true). */
    fun skipBreak(): Boolean = synchronized(lock) {
        val s = _state.value
        if (!s.isBreak) return@synchronized false
        completionJob?.cancel()
        if (s.mode == TimerMode.POMODORO && !s.standaloneBreak) {
            publish(s.copy(phase = Phase.FOCUS, status = Status.RUNNING, phaseDurationMs = s.plan.focusMs, phaseStartedAt = System.currentTimeMillis(), pausedAt = 0L))
            scheduleCompletion()
            return@synchronized true
        }
        finish()
        false
    }

    /** Skips the rest of the current phase: a focus block is recorded as far as it got, then the cycle moves on. */
    fun skipPhase() = synchronized(lock) {
        val s = _state.value
        if (!s.isActive) return@synchronized
        if (s.isBreak) { skipBreak(); return@synchronized }
        if (s.strict) return@synchronized
        if (s.mode != TimerMode.POMODORO) { stop(); return@synchronized }
        completionJob?.cancel()
        val now = System.currentTimeMillis()
        record(s, s.elapsedAt(now), completed = false)
        val round = s.round + 1
        val long = round % s.plan.rounds.coerceAtLeast(1) == 0
        publish(
            s.copy(
                phase = if (long) Phase.LONG_BREAK else Phase.SHORT_BREAK, status = Status.RUNNING,
                phaseDurationMs = if (long) s.plan.longBreakMs else s.plan.shortBreakMs, phaseStartedAt = now, pausedAt = 0L, round = round,
            )
        )
        scheduleCompletion()
    }

    fun pause() = synchronized(lock) {
        val s = _state.value
        if (s.status != Status.RUNNING || (s.strict && s.phase == Phase.FOCUS)) return@synchronized
        completionJob?.cancel()
        publish(s.copy(status = Status.PAUSED, pausedAt = System.currentTimeMillis()))
    }

    fun resume() = synchronized(lock) {
        val s = _state.value
        if (s.status != Status.PAUSED) return@synchronized
        val shift = System.currentTimeMillis() - s.pausedAt
        publish(s.copy(status = Status.RUNNING, phaseStartedAt = s.phaseStartedAt + shift, pausedAt = 0L, checkInAt = 0L, autoPaused = false))
        scheduleCompletion()
    }

    fun stop(force: Boolean = false) = synchronized(lock) {
        val s = _state.value
        if (s.status == Status.IDLE) return@synchronized
        if (s.strict && s.phase == Phase.FOCUS && !force) return@synchronized
        completionJob?.cancel()
        if (s.phase == Phase.FOCUS) record(s, s.elapsedAt(System.currentTimeMillis()), completed = s.countsUp)
        finish()
    }

    /**
     * Continues a session that was running on another device: same phase, same absolute start
     * (so the remaining time carries over), same subject and cycle. Nothing is recorded here for the
     * time already spent: the whole block is recorded once, by whichever device finishes it.
     */
    fun adopt(remote: TimerState) = synchronized(lock) {
        if (remote.status == Status.IDLE) return@synchronized
        completionJob?.cancel()
        publish(remote.copy(checkInAt = 0L, autoPaused = false))
        scheduleCompletion()
    }

    /** Another device took the session over: stop here without recording, it continues there. */
    fun releaseForHandoff() = synchronized(lock) {
        if (_state.value.status == Status.IDLE) return@synchronized
        completionJob?.cancel()
        finish()
    }

    private fun scheduleCompletion() {
        completionJob?.cancel()
        val s = _state.value
        if (s.status != Status.RUNNING || s.countsUp) return
        completionJob = scope.launch {
            delay((s.endAt - System.currentTimeMillis()).coerceAtLeast(0L))
            onPhaseFinished()
        }
    }

    private fun scheduleCheckIn() {
        checkInJob?.cancel()
        val s = _state.value
        if (!checkInsEnabled || !s.isFocusRunning) return
        val now = System.currentTimeMillis()
        if (s.checkInAt > 0L) {
            checkInJob = scope.launch {
                delay((s.checkInAt + CHECK_IN_TIMEOUT_MS - now).coerceAtLeast(0L))
                onCheckInTimeout()
            }
            return
        }
        val elapsed = s.elapsedAt(now)
        val interval = checkInIntervalMs
        val next = (elapsed / interval + 1) * interval
        if (!s.countsUp && next >= s.phaseDurationMs) return
        checkInJob = scope.launch {
            delay(next - elapsed)
            onCheckInDue()
        }
    }

    private fun onCheckInDue() = synchronized(lock) {
        val s = _state.value
        if (!checkInsEnabled || !s.isFocusRunning || s.checkInAt > 0L) return@synchronized
        publish(s.copy(checkInAt = System.currentTimeMillis()))
        alerts.checkIn(CHECK_IN_TIMEOUT_MS)
    }

    private fun onCheckInTimeout() = synchronized(lock) {
        val s = _state.value
        if (!s.awaitingCheckIn || s.phase != Phase.FOCUS) return@synchronized
        completionJob?.cancel()
        publish(s.copy(status = Status.PAUSED, pausedAt = s.checkInAt, checkInAt = 0L, autoPaused = true))
        alerts.autoPaused()
    }

    private fun onPhaseFinished() = synchronized(lock) {
        val s = _state.value
        if (s.status != Status.RUNNING || s.countsUp) return@synchronized
        val endAt = s.endAt
        when (s.phase) {
            Phase.FOCUS -> {
                record(s, s.phaseDurationMs, completed = true)
                if (s.mode == TimerMode.POMODORO) {
                    val round = s.round + 1
                    val long = round % s.plan.rounds.coerceAtLeast(1) == 0
                    publish(
                        s.copy(
                            phase = if (long) Phase.LONG_BREAK else Phase.SHORT_BREAK,
                            phaseDurationMs = if (long) s.plan.longBreakMs else s.plan.shortBreakMs,
                            phaseStartedAt = endAt, round = round,
                        )
                    )
                    alerts.alert(TimerEvent.FOCUS_DONE_BREAK_NEXT)
                    scheduleCompletion()
                } else {
                    finish()
                    alerts.alert(TimerEvent.SESSION_DONE)
                }
            }
            Phase.SHORT_BREAK, Phase.LONG_BREAK -> {
                if (s.mode == TimerMode.POMODORO && !s.standaloneBreak) {
                    publish(s.copy(phase = Phase.FOCUS, phaseDurationMs = s.plan.focusMs, phaseStartedAt = endAt))
                    alerts.alert(TimerEvent.BREAK_DONE_FOCUS_NEXT)
                    scheduleCompletion()
                } else {
                    finish()
                    alerts.alert(TimerEvent.BREAK_DONE)
                }
            }
            Phase.IDLE -> Unit
        }
    }

    private fun finish() = publish(TimerState())

    private fun publish(next: TimerState) {
        val s = if (next.checkInAt > 0L && !next.isFocusRunning) next.copy(checkInAt = 0L) else next
        if (_state.value.checkInAt > 0L && s.checkInAt == 0L) alerts.cancelCheckIn()
        _state.value = s
        persist(s)
        scheduleCheckIn()
    }

    private fun record(s: TimerState, durationMs: Long, completed: Boolean) {
        if (durationMs < MIN_RECORD_MS) return
        onRecord(SessionEntity(startedAt = s.phaseStartedAt, durationMs = durationMs, mode = s.mode.name, subject = s.subject, completed = completed))
    }

    private fun persist(s: TimerState) {
        file.save(
            if (s.status == Status.IDLE) SavedTimer() else SavedTimer(
                mode = s.mode.name, phase = s.phase.name, status = s.status.name, duration = s.phaseDurationMs,
                started = s.phaseStartedAt, pausedAt = s.pausedAt, strict = s.strict, subject = s.subject, round = s.round,
                pFocus = s.plan.focusMs, pShort = s.plan.shortBreakMs, pLong = s.plan.longBreakMs, pRounds = s.plan.rounds,
                standaloneBreak = s.standaloneBreak, checkInAt = s.checkInAt, autoPaused = s.autoPaused,
            )
        )
    }

    private fun restore() {
        val saved = file.load()
        val restored = runCatching {
            val status = Status.valueOf(saved.status)
            if (status == Status.IDLE) return
            TimerState(
                mode = TimerMode.fromName(saved.mode), phase = Phase.valueOf(saved.phase), status = status,
                phaseDurationMs = saved.duration, phaseStartedAt = saved.started, pausedAt = saved.pausedAt,
                strict = saved.strict, subject = saved.subject, round = saved.round,
                plan = Plan(saved.pFocus, saved.pShort, saved.pLong, saved.pRounds),
                standaloneBreak = saved.standaloneBreak, checkInAt = saved.checkInAt, autoPaused = saved.autoPaused,
            )
        }.getOrNull() ?: return
        val abandoned = restored.status == Status.RUNNING && !restored.countsUp &&
            System.currentTimeMillis() - restored.endAt > ABANDON_GRACE_MS
        if (abandoned) {
            if (restored.phase == Phase.FOCUS) record(restored, restored.phaseDurationMs, completed = true)
            publish(TimerState())
            return
        }
        _state.value = restored
        scheduleCompletion()
        scheduleCheckIn()
    }

    companion object {
        const val MIN_RECORD_MS = 30_000L
        const val ABANDON_GRACE_MS = 10 * 60_000L
        const val CHECK_IN_TIMEOUT_MS = 2 * 60_000L
    }
}
