package com.flowstate.focus.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * 5. Daily progress, 4x2 (1.8.5, redesigned in 1.8.8; the class name is kept so widgets already on the home
 * screen carry over): today's focus, % of the goal with a bar, sessions, streak and this week's bars.
 */
@AndroidEntryPoint
class DailyProgressWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.DAILY

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.dailyProgress()
        return ids.map { id -> id to views(context, manager, id, data) }
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, data: DailyProgressWidgetData): RemoteViews {
        val p = data.palette
        val m = data.model
        val v = RemoteViews(context.packageName, R.layout.widget_daily_progress)
        applyGlass(v, p)
        texts(v, p.sub, R.id.today_label, R.id.week_label, R.id.goal_line, R.id.sessions_summary, R.id.streak_summary)
        texts(v, p.text, R.id.focus_time)
        texts(v, p.accent, R.id.percent)
        tint(v, R.id.divider, p.rim)

        v.setTextViewText(R.id.focus_time, formatFocusDuration(m.focusedMs))
        v.setTextViewText(R.id.percent, if (m.goalMs > 0L) "${m.percent}%" else "")
        v.setTextViewText(R.id.goal_line, if (m.goalMs > 0L) "focused of ${formatGoalDuration(m.goalMs)} goal" else "focused today · set a goal in Flowstate")
        v.setTextViewText(R.id.sessions_summary, emphasise(m.sessions.toString(), if (m.sessions == 1) " session" else " sessions", p.text))
        v.setTextViewText(R.id.streak_summary, emphasise("🔥 ${m.streak}", " day streak", p.text))

        // Bars are drawn for the widget's real size so their round ends are not stretched.
        val (w, h) = sizeDp(manager, id, 300, 150)
        val left = ((w - 32) * 1.6f / 2.6f - 0f).coerceAtLeast(80f)
        v.setImageViewBitmap(R.id.goal_bar, WidgetBitmaps.bar(context, left, 7f, m.percent / 100f, p.accent, p.track))
        val chartW = ((w - 32 - 18) / 2.6f).coerceAtLeast(70f)
        val chartH = (h - 32 - 14 - 8 - 17).toFloat().coerceIn(40f, 160f)
        v.setImageViewBitmap(R.id.week_chart, WidgetBitmaps.weekChart(context, chartW, chartH, m.week, m.todayIndex, m.goalMs, p.accent, p.track))
        val dayIds = intArrayOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6)
        dayIds.forEachIndexed { i, dayId -> v.setTextColor(dayId, if (i == m.todayIndex) p.accent else p.muted) }

        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_STATS))
        return v
    }

    /** "5 sessions" with the number large and bold in the text colour, like the mockup. */
    private fun emphasise(number: String, rest: String, color: Int): CharSequence {
        val s = SpannableString(number + rest)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(RelativeSizeSpan(1.35f), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(color), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}
