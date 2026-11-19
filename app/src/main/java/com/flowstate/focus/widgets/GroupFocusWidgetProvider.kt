package com.flowstate.focus.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * 8. Group focus, 4x2 (1.8.5, redesigned in 1.8.8; the class name is kept so widgets already on the home screen
 * carry over): the selected group, "N of M live", and up to three live members with avatar, subject, a bar and
 * their minutes. The status messages (not configured, signed out, no group, nobody focusing) are unchanged.
 */
@AndroidEntryPoint
class GroupFocusWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.GROUP

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.groupFocus()
        return ids.map { id -> id to views(context, manager, id, data) }
    }

    private fun views(context: Context, manager: AppWidgetManager, widgetId: Int, data: GroupFocusWidgetData): RemoteViews {
        val p = data.palette
        val v = RemoteViews(context.packageName, R.layout.widget_group_focus)
        applyGlass(v, p)
        texts(v, p.sub, R.id.group_label, R.id.live_suffix, R.id.status_message)
        texts(v, p.text, R.id.group_name, R.id.live_count)
        v.setImageViewResource(R.id.live_bg, glassPill(p))

        v.setTextViewText(R.id.group_name, if (data.groupId == null) "Flowstate" else data.groupName)
        visible(v, R.id.live_pill, data.groupId != null)
        if (data.groupId != null) {
            tint(v, R.id.live_dot, if (data.focusCount > 0) WidgetColors.TEAL else p.muted)
            v.setTextViewText(R.id.live_count, data.focusCount.toString())
            v.setTextViewText(R.id.live_suffix, " of ${data.memberCount} live")
        }
        v.setTextViewText(R.id.status_message, data.statusMessage.orEmpty())
        visible(v, R.id.status_message, data.statusMessage != null)

        val rows = listOf(
            intArrayOf(R.id.member1, R.id.avatar1, R.id.member1_name, R.id.member1_subject, R.id.member1_bar, R.id.member1_minutes),
            intArrayOf(R.id.member2, R.id.avatar2, R.id.member2_name, R.id.member2_subject, R.id.member2_bar, R.id.member2_minutes),
            intArrayOf(R.id.member3, R.id.avatar3, R.id.member3_name, R.id.member3_subject, R.id.member3_bar, R.id.member3_minutes),
        )
        val (w, _) = sizeDp(manager, widgetId, 300, 150)
        // Avatar 32 + gaps; the bar takes 1.6 of 2.6 parts of what is left after the minutes column.
        val barDp = ((w - 32 - 32 - 10 - 8 - 12 - 34) * 1.6f / 2.6f).coerceAtLeast(40f)
        rows.forEachIndexed { i, r ->
            val member = data.focusingMembers.getOrNull(i)
            visible(v, r[0], member != null)
            if (member == null) return@forEachIndexed
            v.setImageViewBitmap(r[1], WidgetBitmaps.avatar(context, 32f, member.avatarId, member.name, p.accent, p.onAccent, if (p.dark) 0x8CFFFFFF.toInt() else 0xE6FFFFFF.toInt()))
            v.setTextViewText(r[2], member.name)
            v.setTextColor(r[2], p.text)
            v.setTextViewText(r[3], member.subject)
            v.setTextColor(r[3], p.muted)
            v.setImageViewBitmap(r[4], WidgetBitmaps.bar(context, barDp, 6f, memberBarFraction(member.elapsedMinutes), p.accent, p.track))
            v.setTextViewText(r[5], "${member.elapsedMinutes}m")
            v.setTextColor(r[5], p.text)
        }

        val open = openApp(context, widgetId, 0, MainActivity.ROUTE_GROUPS) {
            if (data.groupId != null) putExtra(EXTRA_OPEN_GROUP, data.groupId)
        }
        v.setOnClickPendingIntent(R.id.widget_root, open)
        return v
    }

    companion object {
        const val EXTRA_OPEN_GROUP = "com.flowstate.focus.widgets.OPEN_GROUP"
    }
}
