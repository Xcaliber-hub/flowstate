package com.flowstate.focus.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 1.8.8: the ten home-screen widgets. [minIntervalMs] coalesces bursts of refresh requests per widget kind. */
enum class WidgetKind(val provider: Class<*>, val minIntervalMs: Long) {
    FOCUS_RING(FocusRingWidgetProvider::class.java, 0L),
    NOW_FOCUSING(NowFocusingWidgetProvider::class.java, 0L),
    QUICK_START(QuickStartWidgetProvider::class.java, 0L),
    EXAM(ExamCountdownWidgetProvider::class.java, 2_000L),
    DAILY(DailyProgressWidgetProvider::class.java, 5_000L),
    STREAK(StreakHeatmapWidgetProvider::class.java, 5_000L),
    TASKS(TodayTasksWidgetProvider::class.java, 0L),
    GROUP(GroupFocusWidgetProvider::class.java, 30_000L),
    SYLLABUS(SyllabusRingsWidgetProvider::class.java, 2_000L),
    BREAK(BreakModeWidgetProvider::class.java, 0L);

    companion object {
        /** Widgets that show the running timer. */
        val TIMER: Set<WidgetKind> = setOf(FOCUS_RING, NOW_FOCUSING, QUICK_START, BREAK)
        /** Widgets built from the session history. */
        val HISTORY: Set<WidgetKind> = setOf(DAILY, STREAK)
        val ALL: Set<WidgetKind> = entries.toSet()
    }
}

/**
 * Explicit refresh broadcasts, sent only to widgets that are on the home screen.
 *
 * 1.8.8: requests are coalesced per kind instead of dropped. Until 1.8.7 a request inside the minimum interval
 * was thrown away, so the last change of a burst (say a member starting to focus 10 s after the previous update)
 * could stay unseen until Android's 30-minute update. Now the last request of a burst is delivered at the end
 * of the interval, after a short settle delay so a burst of changes becomes one update.
 */
object WidgetRefresh {
    const val ACTION_REFRESH = "com.flowstate.focus.widgets.REFRESH"
    private const val SETTLE_MS = 250L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private val pending = mutableSetOf<WidgetKind>()
    private val lastSent = mutableMapOf<WidgetKind, Long>()

    @JvmStatic
    fun requestDaily(context: Context) = request(context, WidgetKind.HISTORY)

    @JvmStatic
    fun requestGroup(context: Context) = request(context, setOf(WidgetKind.GROUP))

    @JvmStatic
    fun requestTimer(context: Context) = request(context, WidgetKind.TIMER)

    /** Everything, right away (app resume, theme change, midnight). */
    @JvmStatic
    fun requestAll(context: Context) = request(context, WidgetKind.ALL, immediate = true)

    @JvmStatic
    fun request(context: Context, kinds: Set<WidgetKind>, immediate: Boolean = false) {
        // 1.8.7: called from Activity.onResume, Room inserts and the presence collector. AppWidgetManager is
        // null on devices without app-widget support, and its binder calls can throw while the system
        // server restarts; a widget refresh must never take the app down with it.
        val app = context.applicationContext
        kinds.forEach { kind ->
            runCatching { schedule(app, kind, immediate) }
                .onFailure { android.util.Log.w("FlowstateWidget", "Widget refresh request failed", it) }
        }
    }

    /** Widget ids of [kind] on the home screen; empty when none (or widgets are unsupported). */
    fun ids(context: Context, kind: WidgetKind): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching { manager.getAppWidgetIds(ComponentName(context, kind.provider)) }.getOrNull() ?: IntArray(0)
    }

    fun anyInstalled(context: Context, kinds: Set<WidgetKind>): Boolean = kinds.any { ids(context, it).isNotEmpty() }

    private fun schedule(app: Context, kind: WidgetKind, immediate: Boolean) {
        if (ids(app, kind).isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val delay: Long
        synchronized(lock) {
            if (kind in pending) return
            val last = lastSent[kind] ?: Long.MIN_VALUE / 2
            val wait = (last + kind.minIntervalMs - now).coerceAtLeast(0L)
            delay = if (immediate) wait else maxOf(wait, SETTLE_MS)
            pending += kind
        }
        handler.postDelayed({ send(app, kind) }, delay)
    }

    private fun send(app: Context, kind: WidgetKind) {
        synchronized(lock) {
            pending -= kind
            lastSent[kind] = SystemClock.elapsedRealtime()
        }
        runCatching { app.sendBroadcast(Intent(app, kind.provider).setAction(ACTION_REFRESH)) }
            .onFailure { android.util.Log.w("FlowstateWidget", "Widget refresh broadcast failed", it) }
    }
}
