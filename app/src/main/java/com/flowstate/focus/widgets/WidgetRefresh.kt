package com.flowstate.focus.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Explicit refresh broadcasts are only sent when the corresponding widget is installed. */
object WidgetRefresh {
    private const val PREFS = "flowstate_widget_refresh"
    private const val ACTION_REFRESH_DAILY = "com.flowstate.focus.widgets.REFRESH_DAILY"
    private const val ACTION_REFRESH_GROUP = "com.flowstate.focus.widgets.REFRESH_GROUP"

    @JvmStatic
    fun requestDaily(context: Context) = request(context, DailyProgressWidgetProvider::class.java, ACTION_REFRESH_DAILY, "daily", 5_000L)

    @JvmStatic
    fun requestGroup(context: Context) = request(context, GroupFocusWidgetProvider::class.java, ACTION_REFRESH_GROUP, "group", 30_000L)

    @JvmStatic
    fun requestAll(context: Context) {
        request(context, DailyProgressWidgetProvider::class.java, ACTION_REFRESH_DAILY, "daily", 0L)
        request(context, GroupFocusWidgetProvider::class.java, ACTION_REFRESH_GROUP, "group", 0L)
    }

    private fun request(context: Context, receiver: Class<*>, action: String, key: String, minIntervalMs: Long) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        if (manager.getAppWidgetIds(ComponentName(appContext, receiver)).isEmpty()) return

        val now = System.currentTimeMillis()
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(preferences) {
            val last = preferences.getLong("last_$key", 0L)
            if (minIntervalMs > 0L && now - last < minIntervalMs) return
            preferences.edit().putLong("last_$key", now).apply()
        }
        appContext.sendBroadcast(Intent(appContext, receiver).setAction(action))
    }

    internal const val ACTION_DAILY = ACTION_REFRESH_DAILY
    internal const val ACTION_GROUP = ACTION_REFRESH_GROUP
}
