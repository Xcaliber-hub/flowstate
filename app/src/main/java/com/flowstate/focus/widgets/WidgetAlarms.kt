package com.flowstate.focus.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.flowstate.focus.domain.Status
import com.flowstate.focus.service.TimerEngine
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 1.8.8: two cheap, non-waking alarms keep the widgets current without a per-second loop.
 *
 *  - **Midnight**: rolls Today, the streak, the heatmap, the week chart, the exam countdown and today's tasks over
 *    to the new day. It re-arms itself for the next midnight every time it fires (and on app start).
 *  - **Timer step**: while a phase is running and a timer widget is on the home screen, the rings advance about
 *    once a minute. The digits do not need it: they are Chronometers that tick on the launcher's side. The alarm
 *    stops as soon as the timer is paused or idle.
 *
 * Both use non-wakeup alarm types, so with the screen off they wait until the phone wakes up anyway.
 */
object WidgetAlarms {
    internal const val ACTION_MIDNIGHT = "com.flowstate.focus.widgets.MIDNIGHT"
    internal const val ACTION_TIMER_STEP = "com.flowstate.focus.widgets.TIMER_STEP"
    private const val TIMER_STEP_MS = 60_000L

    fun scheduleMidnight(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val next = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000L
        runCatching { am.set(AlarmManager.RTC, next, pending(app, ACTION_MIDNIGHT)) }
    }

    /** Arms (or cancels) the once-a-minute ring step. */
    fun syncTimerStep(context: Context, running: Boolean) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(app, ACTION_TIMER_STEP)
        if (running && WidgetRefresh.anyInstalled(app, setOf(WidgetKind.FOCUS_RING, WidgetKind.NOW_FOCUSING))) {
            runCatching { am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + TIMER_STEP_MS, pi) }
        } else {
            runCatching { am.cancel(pi) }
        }
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_MIDNIGHT) 7_001 else 7_002,
        Intent(context, WidgetAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

@AndroidEntryPoint
class WidgetAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: TimerEngine

    override fun onReceive(context: Context, intent: Intent) {
        // BroadcastReceiver.onReceive is abstract: Hilt's bytecode transform inserts the injecting call itself
        // (same as PhaseAlarmReceiver). Widget providers extend AppWidgetProvider and call super explicitly.
        when (intent.action) {
            WidgetAlarms.ACTION_MIDNIGHT -> {
                WidgetRefresh.requestAll(context)
                WidgetAlarms.scheduleMidnight(context)
            }
            WidgetAlarms.ACTION_TIMER_STEP -> {
                val running = engine.state.value.status == Status.RUNNING
                if (running) WidgetRefresh.request(context, setOf(WidgetKind.FOCUS_RING, WidgetKind.NOW_FOCUSING))
                WidgetAlarms.syncTimerStep(context, running)
            }
        }
    }
}
