package com.flowstate.focus.ui.focus

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.data.sync.CloudSync
import com.flowstate.focus.data.sync.RemoteTimer
import com.flowstate.focus.data.sync.TimerLockState
import com.flowstate.focus.service.TimerEngine
import com.flowstate.focus.service.TimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class FocusUiState(
    val timer: TimerState = TimerState(),
    val settings: AppSettings = AppSettings(),
    val mode: TimerMode = TimerMode.POMODORO,
    val durationMin: Int = 25,
    val strict: Boolean = false,
    val subject: String = "",
    val todayMs: Long = 0L,
    val streak: Int = 0,
    /** Focus time for the last seven days, oldest first. */
    val week: List<Long> = List(7) { 0L },
    val todaySessions: List<SessionEntity> = emptyList(),
    /** 1.6.0: set while another device of this account runs the timer. */
    val remote: RemoteTimer? = null,
    /** A one-off message, e.g. "Your session moved to your PC." */
    val notice: String? = null,
)

/** What the user has picked on the idle screen. Null means "use the default from settings". */
private data class Selection(
    val mode: TimerMode = TimerMode.POMODORO,
    val durationMin: Int? = null,
    val strict: Boolean? = null,
    val subject: String = "",
)

@HiltViewModel
class FocusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TimerEngine,
    settingsRepo: SettingsRepository,
    private val sessionRepo: SessionRepository,
    private val cloudSync: CloudSync,
    private val deleter: com.flowstate.focus.data.repo.SessionDeleter,
    private val timerLock: TimerLockState,
    todayProvider: com.flowstate.focus.data.time.TodayProvider,
    private val prefill: com.flowstate.focus.service.FocusPrefill,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())
    private val since = LocalDate.now().minusDays(400).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val ui: StateFlow<FocusUiState> = combine(
        combine(engine.state, timerLock.remote, timerLock.notice, todayProvider.today) { timer, remote, notice, today -> Quad(timer, remote, notice, today) },
        settingsRepo.settings, selection, sessionRepo.sessionsSince(since),
    ) { (timer, remote, notice, today), settings, sel, sessions ->
        val perDay = StatsCalculator.perDay(sessions)
        val mode = if (timer.isActive) timer.mode else sel.mode
        FocusUiState(
            timer = timer,
            settings = settings,
            mode = mode,
            durationMin = sel.durationMin ?: defaultMinutes(mode, settings),
            strict = sel.strict ?: settings.strictByDefault,
            subject = sel.subject,
            todayMs = perDay[today] ?: 0L,
            streak = StatsCalculator.streak(perDay, today),
            week = (6 downTo 0).map { perDay[today.minusDays(it.toLong())] ?: 0L },
            todaySessions = sessions.filter { StatsCalculator.dateOf(it.startedAt) == today }.reversed(),
            remote = remote,
            notice = notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    private data class Quad(val timer: TimerState, val remote: RemoteTimer?, val notice: String?, val today: LocalDate)

    init {
        // The process may have been killed mid-session; make sure the notification service is back.
        if (engine.state.value.isActive) startService()
        // 1.8.0 "Focus on this": a syllabus chapter becomes the next session's subject, taken once.
        // Only the idle selection changes; a session that is already running keeps its own subject.
        viewModelScope.launch {
            prefill.subject.collect { chapter ->
                if (chapter != null) {
                    setSubject(chapter)
                    prefill.subject.value = null
                }
            }
        }
    }

    private fun defaultMinutes(mode: TimerMode, s: AppSettings) =
        if (mode == TimerMode.COUNTDOWN) s.countdownMin else s.focusMin

    fun setMode(mode: TimerMode) = selection.update { it.copy(mode = mode, durationMin = null) }
    fun setDuration(minutes: Int) = selection.update { it.copy(durationMin = minutes) }
    fun setStrict(strict: Boolean) = selection.update { it.copy(strict = strict) }
    fun setSubject(subject: String) = selection.update { it.copy(subject = subject.take(SUBJECT_MAX)) }

    /**
     * Starts a focus block from what the user picked on the idle screen. Reads the selection directly
     * rather than [ui], which may still show the break that was just ended.
     */
    fun start() {
        // Never start a second session on top of one that is running or paused, here or on another device.
        if (engine.state.value.isActive || timerLock.remote.value != null) return
        val st = ui.value.settings
        val sel = selection.value
        val mode = sel.mode
        val durationMin = sel.durationMin ?: defaultMinutes(mode, st)
        val plan = when (mode) {
            TimerMode.POMODORO -> Plan(durationMin * 60_000L, st.shortBreakMin * 60_000L, st.longBreakMin * 60_000L, st.rounds)
            TimerMode.COUNTDOWN -> Plan(focusMs = durationMin * 60_000L)
            TimerMode.STOPWATCH -> Plan()
        }
        engine.start(mode, plan, sel.strict ?: st.strictByDefault, sel.subject.trim())
        startService()
    }

    /** Starts a standalone break. A notification is posted when it ends, also in the background. */
    fun startBreak(minutes: Int) {
        if (engine.state.value.isActive || timerLock.remote.value != null) return
        engine.startBreak(minutes.coerceIn(1, MAX_BREAK_MIN) * 60_000L, selection.value.mode)
        startService()
    }

    /** From a break: jump into the next Pomodoro round, or end the break and start a fresh session. */
    fun startFocusFromBreak() {
        if (!engine.skipBreak()) start()
    }

    /** Hold-to-delete on a past session. Stats and streaks recompute from the Room flow. */
    fun deleteSession(id: Long) {
        // Shared with Settings › This week's sessions; a synced session is also marked deleted in the cloud.
        viewModelScope.launch { deleter.delete(id) }
    }

    /** "Take over here": moves the session running on the other device to this phone. */
    fun takeOver() {
        if (cloudSync.takeOver()) startService()
    }

    fun clearNotice() { timerLock.notice.value = null }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun forceStop() = engine.stop(force = true)

    companion object {
        val BreakPresetsMin = listOf(5, 10, 15)
        const val MAX_BREAK_MIN = 60
    }

    private fun startService() =
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
}

/** Longest session subject; long enough for every syllabus chapter name (57), under the 80 the cloud allows. */
const val SUBJECT_MAX = 60
