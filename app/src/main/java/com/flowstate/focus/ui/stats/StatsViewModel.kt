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
    /** The coarse subject the numbers are read for; null means every subject. */
    val subject: String? = null,
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
    todayProvider: com.flowstate.focus.data.time.TodayProvider,
) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)
    private val subjectFilter = MutableStateFlow<String?>(null)
    private val since = LocalDate.now().minusDays(400).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val ui: StateFlow<StatsUiState> = combine(
        combine(period, subjectFilter) { p, subject -> StatsPeriodSubject(p, subject) },
        settingsRepo.settings,
        sessionRepo.sessionsSince(since),
        todayProvider.today,
    ) { ps, settings, all, today ->
        val p = ps.period
        val subject = ps.subject
        val perDay = StatsCalculator.perDay(all)
        val inPeriod = StatsCalculator.inPeriod(all, p, today)
        // One coarse subject chosen: read only that subject's focus time. Null means every subject.
        val scoped = if (subject.isNullOrBlank()) inPeriod else inPeriod.filter { StatsCalculator.subjectIs(it.subject, subject ?: "") }
        val completed = scoped.count { it.completed }
        val completion = if (scoped.isEmpty()) 0f else completed / scoped.size.toFloat()
        val goalMs = settings.dailyGoalMin * 60_000L
        val weekMs = (0..6).sumOf { perDay[today.minusDays(it.toLong())] ?: 0L }
        StatsUiState(
            period = p,
            subject = subject,
            buckets = StatsCalculator.buckets(p, scoped, today),
            totalMs = scoped.sumOf { it.durationMs },
            sessionCount = scoped.size,
            averageMs = if (scoped.isEmpty()) 0L else scoped.sumOf { it.durationMs } / scoped.size,
            completionPercent = (completion * 100).toInt(),
            streak = StatsCalculator.streak(perDay, today),
            productivityScore = StatsCalculator.productivityScore(weekMs, goalMs, completion),
            perDay = perDay,
            goalMs = goalMs,
            distribution = StatsCalculator.bySubject(scoped),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(p: StatsPeriod) = period.update { p }

    /** The coarse subject the statistics are read for; null is every subject. */
    fun setSubject(subject: String?) = subjectFilter.update { if (subject.isNullOrBlank()) null else subject.trim() }

    /** Period plus the coarse subject a statistics read is scoped to. */
    private data class StatsPeriodSubject(val period: StatsPeriod, val subject: String?)
}
