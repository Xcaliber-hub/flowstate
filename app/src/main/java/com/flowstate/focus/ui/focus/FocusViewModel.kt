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
    sessionRepo: SessionRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())
    private val since = LocalDate.now().minusDays(400).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val ui: StateFlow<FocusUiState> = combine(
        engine.state, settingsRepo.settings, selection, sessionRepo.sessionsSince(since),
    ) { timer, settings, sel, sessions ->
        val today = LocalDate.now()
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    init {
        // The process may have been killed mid-session; make sure the notification service is back.
        if (engine.state.value.isActive) startService()
    }

    private fun defaultMinutes(mode: TimerMode, s: AppSettings) =
        if (mode == TimerMode.COUNTDOWN) s.countdownMin else s.focusMin

    fun setMode(mode: TimerMode) = selection.update { it.copy(mode = mode, durationMin = null) }
    fun setDuration(minutes: Int) = selection.update { it.copy(durationMin = minutes) }
    fun setStrict(strict: Boolean) = selection.update { it.copy(strict = strict) }
    fun setSubject(subject: String) = selection.update { it.copy(subject = subject.take(40)) }

    fun start() {
        val s = ui.value
        val st = s.settings
        val plan = when (s.mode) {
            TimerMode.POMODORO -> Plan(s.durationMin * 60_000L, st.shortBreakMin * 60_000L, st.longBreakMin * 60_000L, st.rounds)
            TimerMode.COUNTDOWN -> Plan(focusMs = s.durationMin * 60_000L)
            else -> Plan()
        }
        engine.start(s.mode, plan, s.strict, s.subject.trim())
        startService()
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun forceStop() = engine.stop(force = true)

    private fun startService() =
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
}
