package com.flowstate.focus.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
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
class DailyProgressWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var dataSource: FlowstateWidgetDataSource

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetRefresh.ACTION_DAILY) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyProgressWidgetProvider::class.java))
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
                val data = dataSource.dailyProgress()
                withContext(Dispatchers.Main) {
                    appWidgetIds.forEach { id -> manager.updateAppWidget(id, views(context, id, data)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update Daily progress widget", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun views(context: Context, widgetId: Int, data: DailyProgressWidgetData): RemoteViews {
        val layout = if (data.isDark) R.layout.widget_daily_progress else R.layout.widget_daily_progress_light
        val remote = RemoteViews(context.packageName, layout)
        val primary = if (data.isDark) Color.rgb(249, 248, 255) else Color.rgb(28, 27, 34)
        val secondary = if (data.isDark) Color.rgb(205, 202, 216) else Color.rgb(88, 85, 99)
        remote.setTextColor(R.id.today_label, secondary)
        remote.setTextColor(R.id.percent, primary)
        remote.setTextColor(R.id.focus_time, primary)
        remote.setTextColor(R.id.focus_subtitle, secondary)
        remote.setTextColor(R.id.goal_label, secondary)
        remote.setTextColor(R.id.sessions_summary, primary)
        remote.setTextColor(R.id.streak_summary, primary)
        remote.setTextColor(R.id.percent, data.accentColor)
        remote.setTextColor(R.id.focus_time, data.accentColor)
        remote.setTextViewText(R.id.percent, "${data.progressPercent}%")
        remote.setTextViewText(R.id.focus_time, formatFocusDuration(data.focusedMs))
        remote.setTextViewText(R.id.goal_label, if (data.goalMs > 0L) "Goal ${formatGoalDuration(data.goalMs)}" else "Set a daily goal in Flowstate")
        remote.setTextViewText(R.id.sessions_summary, "${data.sessionCount} ${if (data.sessionCount == 1) "session" else "sessions"}")
        remote.setTextViewText(R.id.streak_summary, "${data.streakDays} day ${if (data.streakDays == 1) "streak" else "streak"}")
        remote.setProgressBar(R.id.goal_progress, 100, data.progressPercent, false)
        remote.setOnClickPendingIntent(R.id.widget_root, openApp(context, widgetId))
        return remote
    }

    private fun openApp(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE + widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "FlowstateWidget"
        private const val REQUEST_CODE_BASE = 4_100
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
