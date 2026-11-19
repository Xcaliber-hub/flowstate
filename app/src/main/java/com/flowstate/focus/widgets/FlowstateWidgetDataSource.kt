package com.flowstate.focus.widgets

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateFormat
import com.flowstate.focus.data.exams.ExamRepository
import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.data.groups.GroupsState
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.data.repo.TaskRepository
import com.flowstate.focus.data.sync.TimerLockState
import com.flowstate.focus.data.syllabus.SyllabusRepository
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.ThemeMode
import com.flowstate.focus.domain.groups.PresetAvatars
import com.flowstate.focus.service.TimerEngine
import com.flowstate.focus.ui.syllabus.SyllabusViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class DailyProgressWidgetData(val model: DailyModel, val palette: WidgetPalette)

internal data class GroupFocusMemberWidgetData(
    val uid: String,
    val name: String,
    val avatarId: String,
    val subject: String,
    val elapsedMinutes: Long,
)

internal data class GroupFocusWidgetData(
    val groupId: String?,
    val groupName: String,
    val focusCount: Int,
    val memberCount: Int,
    val focusingMembers: List<GroupFocusMemberWidgetData>,
    val statusMessage: String?,
    val palette: WidgetPalette,
)

internal data class TimerWidgetData(
    val model: TimerModel,
    val palette: WidgetPalette,
    /** Settings › Focus: strict mode by default. Quick-start sessions follow it. */
    val strictByDefault: Boolean,
    val focusMin: Int,
)

internal data class StreakWidgetData(val model: StreakModel, val palette: WidgetPalette)
internal data class TasksWidgetData(val model: TasksModel, val palette: WidgetPalette)
internal data class ExamWidgetData(val model: ExamModel?, val palette: WidgetPalette)
internal data class SyllabusWidgetData(val model: SyllabusModel?, val palette: WidgetPalette)

/**
 * Reads what the widgets show from the same repositories the app's screens use. Injected into every provider
 * through [FlowstateWidgetProvider] (Hilt fills it in during onReceive, before any widget code runs).
 */
@Singleton
class FlowstateWidgetDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val groups: GroupsRepository,
    private val timer: TimerEngine,
    private val timerLock: TimerLockState,
    private val tasks: TaskRepository,
    private val exams: ExamRepository,
    private val syllabus: SyllabusRepository,
) {
    private suspend fun prefs(): AppSettings = settings.settings.first()

    internal suspend fun palette(): WidgetPalette = palette(prefs())

    internal fun palette(prefs: AppSettings): WidgetPalette {
        val dark = isDarkTheme(prefs)
        return WidgetColors.palette(dark, accent(prefs, dark))
    }

    /**
     * The accent the app itself uses: the wallpaper's (Material You) when dynamic colour is on and supported,
     * otherwise the chosen preset, bright on dark glass and the app's own shade on light glass.
     */
    private fun accent(prefs: AppSettings, dark: Boolean): Int {
        if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val res = if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            runCatching { return context.getColor(res) }
        }
        return WidgetColors.accentFor(prefs.accentIndex, dark)
    }

    private fun isDarkTheme(prefs: AppSettings): Boolean = when (prefs.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark(context)
    }

    internal suspend fun timer(): TimerWidgetData {
        val p = prefs()
        val remote = timerLock.remote.value?.takeIf { !it.isStale(System.currentTimeMillis()) }
        val model = TimerModels.map(
            s = timer.state.value,
            now = System.currentTimeMillis(),
            remoteLabel = remote?.deviceLabel,
            zone = ZoneId.systemDefault(),
            use24h = DateFormat.is24HourFormat(context),
            idleFocusMin = p.focusMin,
        )
        return TimerWidgetData(model, palette(p), p.strictByDefault, p.focusMin)
    }

    internal suspend fun dailyProgress(): DailyProgressWidgetData {
        val p = prefs()
        val zone = ZoneId.systemDefault()
        val model = StatsModels.daily(sessions.all(), p.dailyGoalMin.coerceAtLeast(0) * 60_000L, LocalDate.now(zone), zone)
        return DailyProgressWidgetData(model, palette(p))
    }

    internal suspend fun streak(): StreakWidgetData {
        val p = prefs()
        val zone = ZoneId.systemDefault()
        val perDay = StatsCalculator.perDay(sessions.all(), zone)
        return StreakWidgetData(StatsModels.streak(perDay, p.dailyGoalMin.coerceAtLeast(0) * 60_000L, LocalDate.now(zone)), palette(p))
    }

    internal suspend fun tasks(): TasksWidgetData {
        val zone = ZoneId.systemDefault()
        return TasksWidgetData(TaskModels.today(tasks.observe().first(), LocalDate.now(zone), zone), palette())
    }

    /** Ticks or unticks a task from the widget. */
    internal suspend fun toggleTask(id: Long) {
        val task = tasks.byId(id) ?: return
        tasks.update(task.copy(done = !task.done))
    }

    internal suspend fun exam(): ExamWidgetData {
        val today = LocalDate.now()
        val model = SyllabusModels.exam(exams.exams.first(), today, syllabus.load(), syllabus.progress.first())
        return ExamWidgetData(model, palette())
    }

    internal suspend fun syllabus(): SyllabusWidgetData {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val syl = syllabus.load()
        val examId = selectedSyllabusExam(context)
        val exam = syl.exam(examId) ?: syl.exams.firstOrNull()
            ?: return SyllabusWidgetData(null, palette())
        val date = SyllabusViewModel.matchExams(syl, exams.exams.first(), today)[exam.id]?.date
        return SyllabusWidgetData(SyllabusModels.rings(exam, syllabus.progress.first(), date, zone), palette())
    }

    internal suspend fun groupFocus(): GroupFocusWidgetData {
        val prefs = prefs()
        val appState = awaitGroupsState()
        val palette = palette(prefs)
        if (!appState.configured) {
            return GroupFocusWidgetData(null, "Group focus", 0, 0, emptyList(), "Group sync isn't configured", palette)
        }
        val user = appState.user
            ?: return GroupFocusWidgetData(null, "Group focus", 0, 0, emptyList(), "Open Flowstate and sign in", palette)
        val group = appState.selectedGroup
        if (group == null) {
            val message = if (appState.loading) "Loading your group…" else "Join or create a group in Flowstate"
            return GroupFocusWidgetData(null, "Group focus", 0, 0, emptyList(), message, palette)
        }

        val now = System.currentTimeMillis()
        val active = appState.members.filter { isFreshlyFocusing(it, now) }
            .sortedByDescending { now - (it.live?.phaseStartedAt ?: now) }
        val preview = active.take(3).map { member ->
            val started = member.live?.phaseStartedAt ?: now
            GroupFocusMemberWidgetData(
                uid = member.uid,
                name = if (member.uid == user.uid) "You" else member.name.ifBlank { "Focus member" },
                avatarId = PresetAvatars.fallback(member.avatarId),
                subject = member.live?.subject.orEmpty().ifBlank { "Focus" },
                elapsedMinutes = ((now - started).coerceAtLeast(0L) / 60_000L),
            )
        }
        return GroupFocusWidgetData(
            groupId = group.id,
            groupName = group.name,
            focusCount = active.size,
            memberCount = group.memberIds.size,
            focusingMembers = preview,
            statusMessage = if (active.isEmpty()) "No one is focusing right now" else null,
            palette = palette,
        )
    }

    private suspend fun awaitGroupsState(): GroupsState {
        val current = groups.state.value
        if (!current.configured) return current
        if (current.user != null && !current.loading && (current.selectedGroup == null || current.members.isNotEmpty())) return current
        return withTimeoutOrNull(GROUP_WIDGET_LOAD_TIMEOUT_MS) {
            groups.state.first { state ->
                !state.configured || (state.user != null && !state.loading && (state.selectedGroup == null || state.members.isNotEmpty()))
            }
        } ?: groups.state.value
    }

    private companion object {
        const val GROUP_WIDGET_LOAD_TIMEOUT_MS = 8_000L
    }
}

internal fun systemDark(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The exam picked on the Syllabus tab (SharedPreferences "syllabus_ui", written by SyllabusViewModel). */
internal fun selectedSyllabusExam(context: Context): String =
    context.getSharedPreferences(SyllabusViewModel.PREFS, Context.MODE_PRIVATE)
        .getString(SyllabusViewModel.KEY_EXAM, null) ?: SyllabusViewModel.DEFAULT_EXAM

internal fun progressPercent(focusedMs: Long, goalMs: Long): Int =
    if (goalMs <= 0L) 0 else ((focusedMs.coerceAtLeast(0L) * 100L) / goalMs).coerceIn(0L, 100L).toInt()

internal fun isFreshlyFocusing(member: GroupMember, nowMs: Long): Boolean {
    val live = member.live ?: return false
    if (!live.isFocusRunning || member.updatedAt <= 0L) return false
    val ageMs = (nowMs - member.updatedAt).coerceAtLeast(0L)
    return ageMs <= LIVE_MEMBER_FRESHNESS_MS
}

internal fun formatFocusDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun formatGoalDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private const val LIVE_MEMBER_FRESHNESS_MS = 2 * 60_000L
