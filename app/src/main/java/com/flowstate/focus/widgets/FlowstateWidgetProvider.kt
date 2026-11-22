package com.flowstate.focus.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.service.TimerService
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 1.8.8: shared plumbing of the ten widgets. Each widget is a small `@AndroidEntryPoint` subclass that only
 * loads its data and builds its RemoteViews.
 *
 * Injection order (the 1.8.7 fix, kept): Hilt generates `Hilt_<Widget>` between the subclass and this class; its
 * onReceive injects [dataSource] and then calls this onReceive, which calls AppWidgetProvider.onReceive FIRST
 * (dispatching onUpdate / onEnabled / options changes) and only then handles the refresh and widget actions.
 * Subclasses must not override onReceive; they handle their own actions in [onAction].
 */
abstract class FlowstateWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var dataSource: FlowstateWidgetDataSource

    internal abstract val kind: WidgetKind

    /** Builds the views for every id in [ids]. Runs off the main thread. */
    internal abstract suspend fun build(context: Context, manager: AppWidgetManager, ids: IntArray): List<Pair<Int, RemoteViews>>

    /** Handles a widget-specific broadcast (e.g. ticking a task). Return true when [action] was handled. */
    internal open suspend fun onAction(context: Context, intent: Intent): Boolean = false

    /** Actions [onAction] understands; anything else is left to AppWidgetProvider. */
    internal open val actions: Set<String> = emptySet()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        when {
            action == WidgetRefresh.ACTION_REFRESH -> updateAll(context)
            action in actions -> {
                val pending = goAsync()
                widgetScope.launch {
                    try {
                        onAction(context, intent)
                        render(context, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Widget action $action failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        WidgetAlarms.scheduleMidnight(context)
        val pending = goAsync()
        widgetScope.launch {
            try {
                render(context, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Resized: bars and charts are redrawn for the new size.
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        WidgetAlarms.scheduleMidnight(context)
    }

    private fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = WidgetRefresh.ids(context, kind)
        if (ids.isNotEmpty()) onUpdate(context, manager, ids)
    }

    private suspend fun render(context: Context, only: IntArray?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = only ?: WidgetRefresh.ids(context, kind)
        if (ids.isEmpty()) return
        try {
            build(context, manager, ids).forEach { (id, views) ->
                runCatching { manager.updateAppWidget(id, views) }
                    .onFailure { Log.w(TAG, "Could not update ${kind.name} widget $id", it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build ${kind.name} widget", e)
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Glass background, switched at runtime for dark / light. */
    internal fun applyGlass(views: RemoteViews, palette: WidgetPalette, breakTint: Boolean = false) {
        val res = when {
            // Liquid: clear glass with no tint, break mode included.
            palette.liquid && palette.dark -> R.drawable.widget_liquid_dark
            palette.liquid -> R.drawable.widget_liquid_light
            breakTint && palette.dark -> R.drawable.widget_glass_break_dark
            breakTint -> R.drawable.widget_glass_break_light
            palette.dark -> R.drawable.widget_glass_dark
            else -> R.drawable.widget_glass_light
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", res)
    }

    internal fun glassPill(palette: WidgetPalette) = when {
        palette.liquid -> R.drawable.widget_liquid_pill
        palette.dark -> R.drawable.widget_pill_dark
        else -> R.drawable.widget_pill_light
    }
    internal fun glassCircle(palette: WidgetPalette) = when {
        palette.liquid -> R.drawable.widget_liquid_circle
        palette.dark -> R.drawable.widget_circle_dark
        else -> R.drawable.widget_circle_light
    }

    /** A white shape or icon drawn in [color] (ImageView.setColorFilter is remotable on every version). */
    internal fun tint(views: RemoteViews, id: Int, color: Int) = views.setInt(id, "setColorFilter", color)

    internal fun texts(views: RemoteViews, color: Int, vararg ids: Int) = ids.forEach { views.setTextColor(it, color) }

    internal fun visible(views: RemoteViews, id: Int, show: Boolean) = views.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

    /** A filled accent circle button with an icon in the on-accent colour. */
    internal fun accentButton(views: RemoteViews, bgId: Int, iconId: Int, icon: Int, palette: WidgetPalette) {
        views.setImageViewResource(bgId, R.drawable.widget_shape_circle)
        tint(views, bgId, palette.accent)
        views.setImageViewResource(iconId, icon)
        tint(views, iconId, palette.onAccent)
    }

    /** A glass circle button with an icon in the text colour. */
    internal fun glassButton(views: RemoteViews, bgId: Int, iconId: Int, icon: Int, palette: WidgetPalette) {
        views.setImageViewResource(bgId, glassCircle(palette))
        views.setImageViewResource(iconId, icon)
        tint(views, iconId, palette.text)
    }

    /**
     * Shows a running phase in a Chronometer that ticks on the launcher's side (count down to the end, or count up
     * for the stopwatch), and a paused one as fixed text.
     */
    internal fun applyTime(views: RemoteViews, chronoId: Int, staticId: Int, time: TimeDisplay, color: Int) {
        views.setTextColor(chronoId, color)
        views.setTextColor(staticId, color)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        when (time) {
            is TimeDisplay.CountDown -> {
                views.setChronometerCountDown(chronoId, true)
                views.setChronometer(chronoId, nowElapsed + (time.endAt - nowWall), null, true)
                visible(views, chronoId, true)
                visible(views, staticId, false)
            }
            is TimeDisplay.CountUp -> {
                views.setChronometerCountDown(chronoId, false)
                views.setChronometer(chronoId, nowElapsed - (nowWall - time.startedAt), null, true)
                visible(views, chronoId, true)
                visible(views, staticId, false)
            }
            is TimeDisplay.Fixed -> {
                views.setChronometer(chronoId, nowElapsed, null, false)
                views.setTextViewText(staticId, time.text)
                visible(views, chronoId, false)
                visible(views, staticId, true)
            }
        }
    }

    /** Widget width/height in dp as the launcher reports it (portrait), with fallbacks for launchers that do not. */
    internal fun sizeDp(manager: AppWidgetManager, id: Int, defaultW: Int, defaultH: Int): Pair<Int, Int> {
        val o = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: defaultW
        val h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: defaultH
        return w to h
    }

    // ------------------------------------------------------------------------------------------ intents

    /** A unique data URI per widget, id and slot, so PendingIntents never overwrite each other's extras. */
    private fun uri(widgetId: Int, slot: String) = Uri.parse("flowstate-widget://${kind.name.lowercase()}/$widgetId/$slot")

    private fun requestCode(widgetId: Int, slot: Int) = REQUEST_BASE + kind.ordinal * 100_000 + (widgetId % 5_000) * 20 + slot

    /** Opens Flowstate, optionally on a screen ([MainActivity.EXTRA_OPEN_ROUTE]). */
    internal fun openApp(context: Context, widgetId: Int, slot: Int, route: String? = null, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(uri(widgetId, "open$slot"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
        intent.configure()
        return PendingIntent.getActivity(context, requestCode(widgetId, slot), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A timer action handled by [TimerService], started as a foreground service (allowed from a widget tap). */
    internal fun timerAction(context: Context, widgetId: Int, slot: Int, action: String, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, TimerService::class.java)
            .setAction(action)
            .setData(uri(widgetId, "timer$slot"))
        intent.configure()
        return PendingIntent.getForegroundService(context, requestCode(widgetId, slot), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A broadcast back to this provider (e.g. tick a task). */
    internal fun selfAction(context: Context, widgetId: Int, slot: Int, action: String, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, this::class.java)
            .setAction(action)
            .setData(uri(widgetId, "self$slot"))
        intent.configure()
        return PendingIntent.getBroadcast(context, requestCode(widgetId, slot), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    internal companion object {
        const val TAG = "FlowstateWidget"
        private const val REQUEST_BASE = 40_000
        val widgetScope = com.flowstate.focus.util.AppScopes.background(Dispatchers.IO, "Widgets")
    }
}
