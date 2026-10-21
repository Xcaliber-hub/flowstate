package com.flowstate.focus.service

import android.content.Context
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerEvent
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for the timer, shared by the UI, the foreground service and the
 * accessibility service.
 *
 * Design rules that keep it glitch-free:
 *  - Only absolute timestamps are stored. Nothing counts ticks.
 *  - One coroutine sleeps until the phase ends instead of waking every second.
 *  - Every state change is persisted, so a killed process resumes where it left off.
 *  - If the process was dead for much longer than the phase, the session is closed instead of
 *    replaying hours of phantom Pomodoros.
 */
@Singleton
class TimerEngine @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionRepository,
    private val alerts: AlertNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("timer_state", Context.MODE_PRIVATE)
    private val lock = Any()
    private var completionJob: Job? = null

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    init {
        restore()
    }

    fun start(mode: TimerMode, plan: Plan, strict: Boolean, subject: String) {
        synchronized(lock) {
            if (_state.value.status != Status.IDLE) return@synchronized
            val duration = if (mode.countsUp) 0L else plan.focusMs
            publish(
                TimerState(
                    mode = mode,
                    phase = Phase.FOCUS,
                    status = Status.RUNNING,
                    phaseDurationMs = duration,
                    phaseStartedAt = System.currentTimeMillis(),
                    strict = strict,
                    subject = subject,
                    plan = plan,
                )
            )
            scheduleCompletion()
        }
    }

    /** Ignored while a strict focus block is running. */
    fun pause() {
        synchronized(lock) {
            val s = _state.value
            if (s.status != Status.RUNNING || (s.strict && s.phase == Phase.FOCUS)) return@synchronized
            completionJob?.cancel()
            publish(s.copy(status = Status.PAUSED, pausedAt = System.currentTimeMillis()))
        }
    }

    fun resume() {
        synchronized(lock) {
            val s = _state.value
            if (s.status != Status.PAUSED) return@synchronized
            val shift = System.currentTimeMillis() - s.pausedAt
            publish(s.copy(status = Status.RUNNING, phaseStartedAt = s.phaseStartedAt + shift, pausedAt = 0L))
            scheduleCompletion()
        }
    }

    /** Ends the session. A strict focus block can only be ended with [force] (the hold-to-quit button). */
    fun stop(force: Boolean = false) {
        synchronized(lock) {
            val s = _state.value
            if (s.status == Status.IDLE) return@synchronized
            if (s.strict && s.phase == Phase.FOCUS && !force) return@synchronized
            completionJob?.cancel()
            val now = System.currentTimeMillis()
            if (s.phase == Phase.FOCUS) {
                val elapsed = s.elapsedAt(now)
                record(s, elapsed, completed = s.countsUp)
                // Flow mode earns a break worth a fifth of the time you just focused.
                if (s.mode == TimerMode.FLOW && elapsed >= 5 * 60_000L) {
                    publish(
                        s.copy(
                            phase = Phase.SHORT_BREAK,
                            status = Status.RUNNING,
                            phaseDurationMs = (elapsed / 5).coerceAtMost(30 * 60_000L),
                            phaseStartedAt = now,
                            pausedAt = 0L,
                            strict = false,
                        )
                    )
                    scheduleCompletion()
                    return@synchronized
                }
            }
            finish()
        }
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

    private fun onPhaseFinished() {
        synchronized(lock) {
            val s = _state.value
            if (s.status != Status.RUNNING || s.countsUp) return@synchronized
            // Chain from the real end time so a late wake-up does not shift the schedule.
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
                                phaseStartedAt = endAt,
                                round = round,
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
                    if (s.mode == TimerMode.POMODORO) {
                        publish(s.copy(phase = Phase.FOCUS, phaseDurationMs = s.plan.focusMs, phaseStartedAt = endAt))
                        alerts.alert(TimerEvent.BREAK_DONE_FOCUS_NEXT)
                        scheduleCompletion()
                    } else {
                        finish()
                        alerts.alert(TimerEvent.SESSION_DONE)
                    }
                }
                Phase.IDLE -> Unit
            }
        }
    }

    private fun finish() = publish(TimerState())

    private fun publish(s: TimerState) {
        _state.value = s
        persist(s)
    }

    private fun record(s: TimerState, durationMs: Long, completed: Boolean) {
        if (durationMs < MIN_RECORD_MS) return
        val entity = SessionEntity(
            startedAt = s.phaseStartedAt,
            durationMs = durationMs,
            mode = s.mode.name,
            subject = s.subject,
            completed = completed,
        )
        scope.launch { sessions.insert(entity) }
    }

    private fun persist(s: TimerState) {
        prefs.edit().apply {
            clear()
            if (s.status != Status.IDLE) {
                putString("mode", s.mode.name)
                putString("phase", s.phase.name)
                putString("status", s.status.name)
                putLong("duration", s.phaseDurationMs)
                putLong("started", s.phaseStartedAt)
                putLong("pausedAt", s.pausedAt)
                putBoolean("strict", s.strict)
                putString("subject", s.subject)
                putInt("round", s.round)
                putLong("pFocus", s.plan.focusMs)
                putLong("pShort", s.plan.shortBreakMs)
                putLong("pLong", s.plan.longBreakMs)
                putInt("pRounds", s.plan.rounds)
            }
        }.apply()
    }

    private fun restore() {
        val restored = runCatching {
            val status = Status.valueOf(prefs.getString("status", null) ?: return)
            if (status == Status.IDLE) return
            TimerState(
                mode = TimerMode.valueOf(prefs.getString("mode", null) ?: return),
                phase = Phase.valueOf(prefs.getString("phase", null) ?: return),
                status = status,
                phaseDurationMs = prefs.getLong("duration", 0L),
                phaseStartedAt = prefs.getLong("started", 0L),
                pausedAt = prefs.getLong("pausedAt", 0L),
                strict = prefs.getBoolean("strict", false),
                subject = prefs.getString("subject", "") ?: "",
                round = prefs.getInt("round", 0),
                plan = Plan(
                    focusMs = prefs.getLong("pFocus", Plan().focusMs),
                    shortBreakMs = prefs.getLong("pShort", Plan().shortBreakMs),
                    longBreakMs = prefs.getLong("pLong", Plan().longBreakMs),
                    rounds = prefs.getInt("pRounds", Plan().rounds),
                ),
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
    }

    private companion object {
        const val MIN_RECORD_MS = 30_000L
        const val ABANDON_GRACE_MS = 10 * 60_000L
    }
}
