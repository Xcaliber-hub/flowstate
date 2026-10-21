package com.flowstate.focus.ui.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.Bucket
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.StatsPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
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
data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val buckets: List<Bucket> = emptyList(),
    val totalMs: Long = 0L,
    val sessionCount: Int = 0,
    val averageMs: Long = 0L,
    val completionPercent: Int = 0,
    val streak: Int = 0,
    val productivityScore: Int = 0,
    val perDay: Map<LocalDate, Long> = emptyMap(),
    val goalMs: Long = 0L,
    /** Focus time per subject for the period, largest first. */
    val distribution: List<Pair<String, Long>> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    sessionRepo: SessionRepository,
) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)
    private val since = LocalDate.now().minusDays(400).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val ui: StateFlow<StatsUiState> = combine(period, settingsRepo.settings, sessionRepo.sessionsSince(since)) { p, settings, all ->
        val today = LocalDate.now()
        val perDay = StatsCalculator.perDay(all)
        val inPeriod = StatsCalculator.inPeriod(all, p, today)
        val completed = inPeriod.count { it.completed }
        val completion = if (inPeriod.isEmpty()) 0f else completed / inPeriod.size.toFloat()
        val goalMs = settings.dailyGoalMin * 60_000L
        val weekMs = (0..6).sumOf { perDay[today.minusDays(it.toLong())] ?: 0L }
        StatsUiState(
            period = p,
            buckets = StatsCalculator.buckets(p, inPeriod, today),
            totalMs = inPeriod.sumOf { it.durationMs },
            sessionCount = inPeriod.size,
            averageMs = if (inPeriod.isEmpty()) 0L else inPeriod.sumOf { it.durationMs } / inPeriod.size,
            completionPercent = (completion * 100).toInt(),
            streak = StatsCalculator.streak(perDay, today),
            productivityScore = StatsCalculator.productivityScore(weekMs, goalMs, completion),
            perDay = perDay,
            goalMs = goalMs,
            distribution = inPeriod
                .groupBy { it.subject.ifBlank { "No subject" } }
                .mapValues { (_, list) -> list.sumOf { it.durationMs } }
                .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(p: StatsPeriod) = period.update { p }
}
