package com.flowstate.focus.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.repo.SessionDeleter
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.data.time.TodayProvider
import com.flowstate.focus.domain.SessionDay
import com.flowstate.focus.domain.WeekRange
import com.flowstate.focus.domain.WeekSessions
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.ScreenTopBar
import com.flowstate.focus.ui.components.TopBarSpec
import com.flowstate.focus.ui.components.formatDuration
import com.flowstate.focus.ui.components.screenContentPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class WeekSessionsUi(
    val loaded: Boolean = false,
    val week: WeekRange = WeekRange.of(LocalDate.now()),
    val today: LocalDate = LocalDate.now(),
    val days: List<SessionDay> = emptyList(),
) {
    val totalMs: Long get() = days.sumOf { it.totalMs }
    val count: Int get() = days.sumOf { it.sessions.size }
}

/** 1.7.2 Settings › This week's sessions: Monday–Sunday of the current week, newest first. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeekSessionsViewModel @Inject constructor(
    sessionRepo: SessionRepository,
    todayProvider: TodayProvider,
    private val deleter: SessionDeleter,
) : ViewModel() {
    // Follows the date, so a list left open over Sunday midnight moves to the new week.
    val ui: StateFlow<WeekSessionsUi> = todayProvider.today.flatMapLatest { today ->
        val week = WeekRange.of(today)
        sessionRepo.sessionsSince(week.startMs).map { rows -> WeekSessionsUi(true, week, today, WeekSessions.group(rows, week)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekSessionsUi())

    /** The same delete as the Focus screen's "Today's sessions" (Room, stats, streak and the cloud copy). */
    fun deleteSession(id: Long) {
        viewModelScope.launch { deleter.delete(id) }
    }
}

private fun rangeFormat() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private fun dayFormat() = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())

private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayFormat())
}

@Composable
fun WeekSessionsScreen(liquid: Boolean, vm: WeekSessionsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }
    val range = "${ui.week.monday.format(rangeFormat())} – ${ui.week.sunday.format(rangeFormat())}"

    ScreenTopBar(
        TopBarSpec(
            key = "week_sessions",
            title = "This week's sessions",
            subtitle = if (ui.count == 0) "Mon–Sun · $range" else "${formatDuration(ui.totalMs)} · ${if (ui.count == 1) "1 session" else "${ui.count} sessions"} · $range",
            icon = Icons.Rounded.History,
        ),
    )

    ScreenContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.loaded && ui.days.isEmpty()) {
                item(key = "empty") {
                    Entrance(0) {
                        GlassCard(Modifier.fillMaxWidth(), liquid) {
                            AutoSizeText("No sessions yet this week", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Focus blocks you finish from Monday to Sunday show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (ui.days.isNotEmpty()) {
                item(key = "hint") {
                    Text(
                        "Hold a session to delete it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(ui.days, key = { it.date.toEpochDay() }) { day ->
                GlassCard(Modifier.fillMaxWidth(), liquid) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dayTitle(day.date, ui.today),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatDuration(day.totalMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    day.sessions.forEach { s -> SessionRow(s, onHold = { pendingDelete = it }) }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        SessionDeleteDialog(session, onDismiss = { pendingDelete = null }, onDelete = { vm.deleteSession(it.id) })
    }
}
