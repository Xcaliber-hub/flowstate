package com.flowstate.focus.widgets

import android.content.Context
import android.content.res.Configuration
import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.data.groups.GroupsState
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.ThemeMode
import com.flowstate.focus.domain.groups.PresetAvatars
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

internal data class DailyProgressWidgetData(
    val focusedMs: Long,
    val goalMs: Long,
    val progressPercent: Int,
    val sessionCount: Int,
    val streakDays: Int,
    val isDark: Boolean,
    val accentColor: Int,
)

internal data class GroupFocusMemberWidgetData(
    val uid: String,
    val name: String,
    val avatarId: String,
    val elapsedMinutes: Long,
)

internal data class GroupFocusWidgetData(
    val groupId: String?,
    val groupName: String,
    val focusCount: Int,
    val memberCount: Int,
    val focusingMembers: List<GroupFocusMemberWidgetData>,
    val statusMessage: String?,
    val isDark: Boolean,
    val accentColor: Int,
)

@Singleton
class FlowstateWidgetDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val groups: GroupsRepository,
) {
    internal suspend fun dailyProgress(): DailyProgressWidgetData {
        val prefs = settings.settings.first()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rows = sessions.all()
        val perDay = StatsCalculator.perDay(rows, zone)
        val focused = perDay[today] ?: 0L
        val goal = prefs.dailyGoalMin.coerceAtLeast(0) * 60_000L
        val count = rows.count { StatsCalculator.dateOf(it.startedAt, zone) == today }
        return DailyProgressWidgetData(
            focusedMs = focused,
            goalMs = goal,
            progressPercent = progressPercent(focused, goal),
            sessionCount = count,
            streakDays = StatsCalculator.streak(perDay, today),
            isDark = isDarkTheme(prefs),
            accentColor = widgetAccent(prefs.accentIndex),
        )
    }

    internal suspend fun groupFocus(): GroupFocusWidgetData {
        val prefs = settings.settings.first()
        val appState = awaitGroupsState()
        val dark = isDarkTheme(prefs)
        val accent = widgetAccent(prefs.accentIndex)
        if (!appState.configured) {
            return GroupFocusWidgetData(null, "GROUP FOCUS", 0, 0, emptyList(), "Group sync isn't configured", dark, accent)
        }
        val user = appState.user
        if (user == null) {
            return GroupFocusWidgetData(null, "GROUP FOCUS", 0, 0, emptyList(), "Open Flowstate and sign in", dark, accent)
        }
        val group = appState.selectedGroup
        if (group == null) {
            val message = if (appState.loading) "Loading your group…" else "Join or create a group in Flowstate"
            return GroupFocusWidgetData(null, "GROUP FOCUS", 0, 0, emptyList(), message, dark, accent)
        }

        val now = System.currentTimeMillis()
        val active = appState.members.filter { isFreshlyFocusing(it, now) }
        val preview = active.take(3).map { member ->
            val started = member.live?.phaseStartedAt ?: now
            GroupFocusMemberWidgetData(
                uid = member.uid,
                name = member.name.ifBlank { "Focus member" },
                avatarId = PresetAvatars.fallback(member.avatarId),
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
            isDark = dark,
            accentColor = accent,
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

    private fun isDarkTheme(prefs: AppSettings): Boolean = when (prefs.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun widgetAccent(index: Int): Int {
        // Brighter equivalents of the app's seven accent presets stay readable on translucent glass.
        val colors = intArrayOf(
            0xFFB7A4F5.toInt(), 0xFF62D0BB.toInt(), 0xFFFF83B2.toInt(),
            0xFF82D38B.toInt(), 0xFF82B7FF.toInt(), 0xFFFFB36E.toInt(),
            0xFFB6C4C9.toInt(),
        )
        return colors[index.coerceIn(colors.indices)]
    }

    private companion object {
        const val GROUP_WIDGET_LOAD_TIMEOUT_MS = 8_000L
    }
}

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
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
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
