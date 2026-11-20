package com.flowstate.focus.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.service.TimerService
import dagger.hilt.android.AndroidEntryPoint

/*
 * 1.8.8: the four widgets that show and drive the timer (Focus ring, Now focusing, Quick start, Break mode).
 * Every button is a PendingIntent.getForegroundService to TimerService; the time itself is a Chronometer, so
 * nothing in the app wakes up every second.
 */

/** The play / pause / resume action shared by the timer widgets, or null when the button should be hidden. */
private fun FlowstateWidgetProvider.mainAction(context: Context, id: Int, slot: Int, data: TimerWidgetData): PendingIntent? {
    val m = data.model
    return when {
        m.idle && m.remoteLocked -> openApp(context, id, slot, MainActivity.ROUTE_FOCUS)
        m.idle -> timerAction(context, id, slot, TimerService.ACTION_QUICK_START) {
            putExtra(TimerService.EXTRA_MODE, TimerMode.POMODORO.name)
            putExtra(TimerService.EXTRA_MINUTES, data.focusMin)
        }
        m.paused -> timerAction(context, id, slot, TimerService.ACTION_RESUME)
        m.canPause -> timerAction(context, id, slot, TimerService.ACTION_PAUSE)
        else -> null // strict focus: no pause from the home screen
    }
}

private fun mainIcon(m: TimerModel) = if (m.idle || m.paused) R.drawable.widget_ic_play else R.drawable.widget_ic_pause

private fun ringColor(m: TimerModel, p: WidgetPalette) = if (m.isBreak) WidgetColors.TEAL else p.accent

private fun statusDot(m: TimerModel, p: WidgetPalette) = when {
    m.idle -> p.muted
    m.paused -> WidgetColors.AMBER
    else -> WidgetColors.TEAL
}

// ------------------------------------------------------------------------------------------------ 1. Focus ring

@AndroidEntryPoint
class FocusRingWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.FOCUS_RING

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.timer()
        return ids.map { id -> id to views(context, manager, id, data) }
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, data: TimerWidgetData): RemoteViews {
        val m = data.model
        val p = data.palette
        val v = RemoteViews(context.packageName, R.layout.widget_focus_ring)
        applyGlass(v, p)
        val (w, h) = sizeDp(manager, id, 160, 160)
        val ringDp = minOf(w - 24, h - 24 - 46).coerceIn(72, 150).toFloat()
        v.setImageViewBitmap(R.id.ring, WidgetBitmaps.ring(context, ringDp, 7f, if (m.idle) 0f else m.fraction, ringColor(m, p), p.track))

        texts(v, p.sub, R.id.subject)
        texts(v, p.muted, R.id.round)
        v.setTextViewText(R.id.subject, (if (m.idle) "Focus" else m.subject).uppercase())
        v.setTextViewText(R.id.round, if (m.idle) m.roundLabel else if (m.paused) "Paused · ${m.roundLabel}" else m.roundLabel)
        applyTime(v, R.id.time, R.id.time_static, if (m.idle) TimeDisplay.Fixed(if (m.remoteLocked) m.remoteLabel.replaceFirstChar { it.uppercase() } else "Ready") else m.time, p.text)

        // Stop / main / skip. Strict focus hides pause and stop (and skip), like the notification.
        val main = mainAction(context, id, 1, data)
        visible(v, R.id.btn_main, main != null)
        if (main != null) {
            accentButton(v, R.id.btn_main_bg, R.id.btn_main_icon, mainIcon(m), p)
            v.setOnClickPendingIntent(R.id.btn_main, main)
            v.setContentDescription(R.id.btn_main, if (m.idle) "Start focus" else if (m.paused) "Resume" else "Pause")
        }
        val showStop = !m.idle && m.canStop
        visible(v, R.id.btn_stop, showStop)
        if (showStop) {
            glassButton(v, R.id.btn_stop_bg, R.id.btn_stop_icon, R.drawable.widget_ic_stop, p)
            v.setOnClickPendingIntent(R.id.btn_stop, timerAction(context, id, 2, TimerService.ACTION_STOP))
            v.setContentDescription(R.id.btn_stop, if (m.isBreak) "End break" else "Stop")
        }
        val showSkip = !m.idle && m.canSkip
        visible(v, R.id.btn_skip, showSkip)
        if (showSkip) {
            glassButton(v, R.id.btn_skip_bg, R.id.btn_skip_icon, R.drawable.widget_ic_skip, p)
            v.setOnClickPendingIntent(R.id.btn_skip, timerAction(context, id, 3, TimerService.ACTION_SKIP))
            v.setContentDescription(R.id.btn_skip, if (m.isBreak) "Skip break" else "Skip to break")
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_FOCUS))
        return v
    }
}

// ------------------------------------------------------------------------------------------------ 2. Now focusing

@AndroidEntryPoint
class NowFocusingWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.NOW_FOCUSING

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.timer()
        return ids.map { id -> id to views(context, id, data) }
    }

    private fun views(context: Context, id: Int, data: TimerWidgetData): RemoteViews {
        val m = data.model
        val p = data.palette
        val v = RemoteViews(context.packageName, R.layout.widget_now_focusing)
        applyGlass(v, p)
        v.setImageViewBitmap(R.id.ring, WidgetBitmaps.ring(context, 46f, 4.5f, if (m.idle) 0f else m.fraction, ringColor(m, p), p.track))
        texts(v, p.text, R.id.ring_label, R.id.subject)
        texts(v, p.sub, R.id.status)
        texts(v, p.muted, R.id.next_line)
        v.setTextViewText(R.id.ring_label, if (m.idle) "" else m.roundShort)
        tint(v, R.id.status_dot, statusDot(m, p))
        v.setTextViewText(R.id.status, m.status)
        v.setTextViewText(R.id.subject, m.subject)
        v.setTextViewText(R.id.next_line, m.nextLine)
        if (m.idle) {
            // Idle prompt: no clock, just the play button.
            visible(v, R.id.time, false)
            visible(v, R.id.time_static, false)
        } else {
            applyTime(v, R.id.time, R.id.time_static, m.time, p.text)
        }
        val main = mainAction(context, id, 1, data)
        visible(v, R.id.btn_main, main != null)
        if (main != null) {
            accentButton(v, R.id.btn_main_bg, R.id.btn_main_icon, if (m.idle && m.remoteLocked) R.drawable.widget_ic_play else mainIcon(m), p)
            v.setOnClickPendingIntent(R.id.btn_main, main)
            v.setContentDescription(R.id.btn_main, if (m.idle) "Start focus" else if (m.paused) "Resume" else "Pause")
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_FOCUS))
        return v
    }
}
