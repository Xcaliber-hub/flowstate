package com.flowstate.focus.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import dagger.hilt.android.AndroidEntryPoint

/* 1.8.8: Exam countdown, Streak heatmap, Today's tasks and Syllabus rings. */

// ------------------------------------------------------------------------------------------------ 4. Exam countdown

@AndroidEntryPoint
class ExamCountdownWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.EXAM

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.exam()
        return ids.map { id -> id to views(context, manager, id, data) }
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, data: ExamWidgetData): RemoteViews {
        val p = data.palette
        val m = data.model
        val v = RemoteViews(context.packageName, R.layout.widget_exam_countdown)
        applyGlass(v, p)
        texts(v, p.sub, R.id.exam_name, R.id.days_label, R.id.syllabus_label, R.id.empty_hint)
        texts(v, p.text, R.id.days)
        texts(v, p.muted, R.id.date, R.id.syllabus_chapters)
        texts(v, p.accent, R.id.syllabus_pct)
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_EXAMS))
        if (m == null) {
            v.setTextViewText(R.id.exam_name, "EXAMS")
            v.setTextViewText(R.id.days, "—")
            v.setTextViewText(R.id.days_label, "no exam yet")
            visible(v, R.id.date, false)
            visible(v, R.id.syllabus_block, false)
            visible(v, R.id.empty_hint, true)
            return v
        }
        visible(v, R.id.empty_hint, false)
        visible(v, R.id.date, true)
        v.setTextViewText(R.id.exam_name, m.name.uppercase())
        v.setTextViewText(R.id.days, if (m.daysLeft == 0L) "Today" else m.daysLeft.toString())
        v.setTextViewText(R.id.days_label, when (m.daysLeft) { 0L -> "good luck"; 1L -> "day to go"; else -> "days to go" })
        v.setTextViewText(R.id.date, m.dateLine)
        val pct = m.syllabusPercent
        visible(v, R.id.syllabus_block, pct != null)
        if (pct != null) {
            val (w, _) = sizeDp(manager, id, 160, 160)
            v.setTextViewText(R.id.syllabus_pct, "$pct%")
            v.setImageViewBitmap(R.id.syllabus_bar, WidgetBitmaps.bar(context, (w - 32).coerceAtLeast(60).toFloat(), 6f, pct / 100f, p.accent, p.track))
            v.setTextViewText(R.id.syllabus_chapters, "${m.chaptersDone} of ${m.chapters} chapters done")
        }
        return v
    }
}

// ------------------------------------------------------------------------------------------------ 6. Streak heatmap

@AndroidEntryPoint
class StreakHeatmapWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.STREAK

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.streak()
        return ids.map { id -> id to views(context, id, data) }
    }

    private fun views(context: Context, id: Int, data: StreakWidgetData): RemoteViews {
        val p = data.palette
        val m = data.model
        val v = RemoteViews(context.packageName, R.layout.widget_streak_heatmap)
        applyGlass(v, p)
        texts(v, p.sub, R.id.title, R.id.streak_unit)
        texts(v, p.muted, R.id.best)
        texts(v, p.text, R.id.streak_count)
        v.setTextViewText(R.id.streak_count, m.current.toString())
        v.setTextViewText(R.id.streak_unit, if (m.current == 1) "day" else "days")
        v.setTextViewText(R.id.best, "Best ${m.best}")
        v.setImageViewBitmap(R.id.heatmap, WidgetBitmaps.heatmap(context, 16f, 4f, m.levels, p.accent, p.track))
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_STATS))
        return v
    }
}

// ------------------------------------------------------------------------------------------------ 7. Today's tasks

@AndroidEntryPoint
class TodayTasksWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.TASKS
    override val actions: Set<String> = setOf(ACTION_TOGGLE)

    override suspend fun onAction(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_TOGGLE) return false
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0) return false
        dataSource.toggleTask(taskId)
        return true
    }

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.tasks()
        return ids.map { id -> id to views(context, id, data) }
    }

    private fun views(context: Context, id: Int, data: TasksWidgetData): RemoteViews {
        val p = data.palette
        val m = data.model
        val v = RemoteViews(context.packageName, R.layout.widget_today_tasks)
        applyGlass(v, p)
        texts(v, p.sub, R.id.title, R.id.empty)
        texts(v, p.muted, R.id.count)
        texts(v, p.text, R.id.add_label)
        v.setImageViewResource(R.id.add_bg, glassPill(p))
        v.setTextViewText(R.id.count, if (m.total == 0) "" else "${m.done} of ${m.total}")
        visible(v, R.id.empty, m.items.isEmpty())

        val rows = listOf(
            Row(R.id.task1, R.id.task1_box, R.id.task1_fill, R.id.task1_check, R.id.task1_title),
            Row(R.id.task2, R.id.task2_box, R.id.task2_fill, R.id.task2_check, R.id.task2_title),
            Row(R.id.task3, R.id.task3_box, R.id.task3_fill, R.id.task3_check, R.id.task3_title),
            Row(R.id.task4, R.id.task4_box, R.id.task4_fill, R.id.task4_check, R.id.task4_title),
        )
        rows.forEachIndexed { i, row ->
            val item = m.items.getOrNull(i)
            visible(v, row.root, item != null)
            if (item == null) return@forEachIndexed
            v.setImageViewResource(row.box, if (p.dark) R.drawable.widget_check_outline_dark else R.drawable.widget_check_outline_light)
            visible(v, row.box, !item.done)
            visible(v, row.fill, item.done)
            visible(v, row.check, item.done)
            if (item.done) {
                tint(v, row.fill, p.accent)
                tint(v, row.check, p.onAccent)
            }
            val title: CharSequence = if (item.done) {
                SpannableString(item.title).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            } else item.title
            v.setTextViewText(row.title, title)
            v.setTextColor(row.title, if (item.done) p.muted else p.text)
            v.setContentDescription(row.root, (if (item.done) "Done: " else "To do: ") + item.title)
            v.setOnClickPendingIntent(row.root, selfAction(context, id, 1 + i, ACTION_TOGGLE) { putExtra(EXTRA_TASK_ID, item.id) })
        }
        v.setOnClickPendingIntent(R.id.add_task, openApp(context, id, 6, MainActivity.ROUTE_TASKS) { putExtra(MainActivity.EXTRA_ADD_TASK, true) })
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_TASKS))
        return v
    }

    private data class Row(val root: Int, val box: Int, val fill: Int, val check: Int, val title: Int)

    companion object {
        const val ACTION_TOGGLE = "com.flowstate.focus.widgets.TOGGLE_TASK"
        const val EXTRA_TASK_ID = "com.flowstate.focus.widgets.TASK_ID"
    }
}

// ------------------------------------------------------------------------------------------------ 9. Syllabus rings

@AndroidEntryPoint
class SyllabusRingsWidgetProvider : FlowstateWidgetProvider() {
    override val kind = WidgetKind.SYLLABUS

    override suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>> {
        val data = dataSource.syllabus()
        return ids.map { id -> id to views(context, id, data) }
    }

    private fun views(context: Context, id: Int, data: SyllabusWidgetData): RemoteViews {
        val p = data.palette
        val m = data.model
        val v = RemoteViews(context.packageName, R.layout.widget_syllabus_rings)
        applyGlass(v, p)
        texts(v, p.sub, R.id.title, R.id.up_next)
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context, id, 0, MainActivity.ROUTE_SYLLABUS))
        val cols = listOf(
            intArrayOf(R.id.ring1_col, R.id.ring1, R.id.ring1_pct, R.id.ring1_label),
            intArrayOf(R.id.ring2_col, R.id.ring2, R.id.ring2_pct, R.id.ring2_label),
            intArrayOf(R.id.ring3_col, R.id.ring3, R.id.ring3_pct, R.id.ring3_label),
            intArrayOf(R.id.ring4_col, R.id.ring4, R.id.ring4_pct, R.id.ring4_label),
        )
        if (m == null) {
            v.setTextViewText(R.id.title, "SYLLABUS")
            cols.forEach { visible(v, it[0], false) }
            v.setTextViewText(R.id.up_next, "Open Flowstate to load the syllabus")
            return v
        }
        v.setTextViewText(R.id.title, "SYLLABUS · ${m.examChip.uppercase()}")
        val colors = WidgetColors.syllabusRingColors(p.accent, m.rings.size)
        // Each ring fills its column (fitCenter), so four rings (IAT) simply come out smaller on a 2x2.
        val ringDp = if (m.rings.size >= 4) 36f else 46f
        cols.forEachIndexed { i, c ->
            val ring = m.rings.getOrNull(i)
            visible(v, c[0], ring != null)
            if (ring == null) return@forEachIndexed
            v.setImageViewBitmap(c[1], WidgetBitmaps.ring(context, ringDp, 4.5f, ring.percent / 100f, colors[i], p.track))
            v.setTextViewText(c[2], ring.percent.toString())
            v.setTextViewTextSize(c[2], android.util.TypedValue.COMPLEX_UNIT_SP, if (m.rings.size >= 4) 11.5f else 13f)
            v.setTextColor(c[2], p.text)
            v.setTextViewText(c[3], ring.label)
            v.setTextColor(c[3], p.sub)
        }
        val next = m.upNext
        if (next == null) {
            v.setTextViewText(R.id.up_next, "Every chapter done")
        } else {
            val text = SpannableString("Up next · $next")
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 10, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.ForegroundColorSpan(p.text), 10, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.setTextViewText(R.id.up_next, text)
        }
        return v
    }
}
