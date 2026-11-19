package com.flowstate.focus.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class GroupFocusWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var dataSource: FlowstateWidgetDataSource

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetRefresh.ACTION_GROUP) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GroupFocusWidgetProvider::class.java))
            if (ids.isNotEmpty()) onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        widgetScope.launch {
            try {
                val data = dataSource.groupFocus()
                withContext(Dispatchers.Main) {
                    appWidgetIds.forEach { id -> manager.updateAppWidget(id, views(context, id, data)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update Group focus widget", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun views(context: Context, widgetId: Int, data: GroupFocusWidgetData): RemoteViews {
        val layout = if (data.isDark) R.layout.widget_group_focus else R.layout.widget_group_focus_light
        val remote = RemoteViews(context.packageName, layout)
        val primary = if (data.isDark) Color.rgb(249, 248, 255) else Color.rgb(28, 27, 34)
        val secondary = if (data.isDark) Color.rgb(205, 202, 216) else Color.rgb(88, 85, 99)
        remote.setTextColor(R.id.group_name, primary)
        remote.setTextColor(R.id.focus_count, data.accentColor)
        remote.setTextColor(R.id.focusing_label, secondary)
        remote.setTextColor(R.id.member_one, primary)
        remote.setTextColor(R.id.member_two, primary)
        remote.setTextColor(R.id.status_message, secondary)
        remote.setTextColor(R.id.open_group, primary)
        remote.setTextViewText(R.id.group_name, data.groupName.uppercase())
        remote.setTextViewText(R.id.focus_count, if (data.groupId == null) "—" else "${data.focusCount} of ${data.memberCount}")
        remote.setTextViewText(R.id.focusing_label, if (data.groupId == null) "" else "focusing")
        remote.setTextViewText(R.id.status_message, data.statusMessage.orEmpty())

        val memberNames = data.focusingMembers
        setAvatar(context, remote, R.id.avatar_one, memberNames.getOrNull(0)?.avatarId)
        setAvatar(context, remote, R.id.avatar_two, memberNames.getOrNull(1)?.avatarId)
        setAvatar(context, remote, R.id.avatar_three, memberNames.getOrNull(2)?.avatarId)
        remote.setViewVisibility(R.id.extra_badge, if (data.focusCount > 3) View.VISIBLE else View.GONE)
        remote.setTextViewText(R.id.extra_badge, if (data.focusCount > 3) "+${data.focusCount - 3}" else "")
        remote.setViewVisibility(R.id.avatar_stack, if (data.focusCount > 0) View.VISIBLE else View.GONE)

        val first = memberNames.getOrNull(0)
        val second = memberNames.getOrNull(1)
        remote.setTextViewText(R.id.member_one, first?.let { "${it.name}  ·  ${it.elapsedMinutes}m" }.orEmpty())
        remote.setTextViewText(R.id.member_two, second?.let { "${it.name}  ·  ${it.elapsedMinutes}m" }.orEmpty())
        remote.setViewVisibility(R.id.member_one, if (first != null) View.VISIBLE else View.GONE)
        remote.setViewVisibility(R.id.member_two, if (second != null) View.VISIBLE else View.GONE)
        remote.setViewVisibility(R.id.status_message, if (data.statusMessage != null || data.groupId == null) View.VISIBLE else View.GONE)

        val open = openGroup(context, widgetId, data.groupId)
        remote.setOnClickPendingIntent(R.id.widget_root, open)
        remote.setOnClickPendingIntent(R.id.open_group, open)
        return remote
    }

    private fun setAvatar(context: Context, remote: RemoteViews, viewId: Int, avatarId: String?) {
        val resourceId = avatarId?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
            ?.let { context.resources.getIdentifier("avatar_$it", "drawable", context.packageName) }
            ?: 0
        if (resourceId == 0) {
            remote.setViewVisibility(viewId, View.GONE)
        } else {
            remote.setImageViewResource(viewId, resourceId)
            remote.setViewVisibility(viewId, View.VISIBLE)
        }
    }

    private fun openGroup(context: Context, widgetId: Int, groupId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (groupId != null) intent.putExtra(EXTRA_OPEN_GROUP, groupId)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE + widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_OPEN_GROUP = "com.flowstate.focus.widgets.OPEN_GROUP"
        private const val TAG = "FlowstateWidget"
        private const val REQUEST_CODE_BASE = 4_200
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
