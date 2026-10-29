package com.flowstate.focus.ui.dock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DailyGoalState(
    /** Focus already saved for today; the running block is added on top of it in the UI. */
    val recordedTodayMs: Long = 0L,
    val goalMin: Int = 120,
    val timer: TimerState = TimerState(),
)

/** Feeds the water bar in the side dock: today's saved focus, the goal and the live timer. */
@HiltViewModel
class DailyGoalViewModel @Inject constructor(
    engine: TimerEngine,
    settingsRepo: SettingsRepository,
    sessionRepo: SessionRepository,
) : ViewModel() {
    // Two days back covers a session that started before midnight; perDay files it by its start date.
    private val since = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val state: StateFlow<DailyGoalState> = combine(
        engine.state, settingsRepo.settings, sessionRepo.sessionsSince(since),
    ) { timer, settings, sessions ->
        DailyGoalState(
            recordedTodayMs = StatsCalculator.perDay(sessions)[LocalDate.now()] ?: 0L,
            goalMin = settings.dailyGoalMin,
            timer = timer,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyGoalState())
}
