package com.flowstate.focus.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.flowstate.focus.MainActivity
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.ui.theme.AccentColors
import com.flowstate.focus.ui.theme.SlateAccent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** True while a Flowstate screen is on top. The floating tab hides then, so it is only seen outside the app. */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    fun set(visible: Boolean) { _visible.value = visible }
}

/** Running = a light tone of the app's palette, paused = a dark tone. Black & white (Slate) is white / black. */
data class FloatingTimerColors(val running: Int, val paused: Int) {
    companion object {
        fun from(context: Context, s: AppSettings): FloatingTimerColors {
            val accent = AccentColors[s.accentIndex.coerceIn(AccentColors.indices)]
            val dynamic = s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (!dynamic && accent == SlateAccent) return FloatingTimerColors(0xFFFAFAFA.toInt(), 0xFF141414.toInt())
            val seed = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { androidx.compose.material3.dynamicLightColorScheme(context).primary }.getOrDefault(accent)
            } else accent
            val argb = seed.toArgb()
            // A seed with almost no colour (a black & white wallpaper) gets the same white / black pair.
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }
            if (hsl[1] < 0.12f) return FloatingTimerColors(0xFFFAFAFA.toInt(), 0xFF141414.toInt())
            return FloatingTimerColors(
                running = ColorUtils.blendARGB(argb, Color.WHITE, 0.55f),
                paused = ColorUtils.blendARGB(argb, Color.BLACK, 0.45f),
            )
        }
    }
}

/**
 * The floating timer tab. It sits on the left or right edge of the screen while a session is active and
 * Flowstate itself is not on screen. Drag it up and down or across (it snaps to the nearer edge); tap it
 * for a small card with the session and Pause / Resume. Needs "Display over other apps".
 */
class FloatingTimerOverlay(
    private val context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("floating_timer", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Int) = (v * density).roundToInt()

    private var tab: TabView? = null
    private var tabParams: WindowManager.LayoutParams? = null
    private var card: FrameLayout? = null
    private var cardViews: CardViews? = null
    private var state: TimerState = TimerState()
    private var colors = FloatingTimerColors(Color.WHITE, Color.BLACK)
    private var snapAnim: ValueAnimator? = null

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY // minSdk 26

    fun update(s: TimerState, c: FloatingTimerColors, enabled: Boolean, appVisible: Boolean) {
        state = s; colors = c
        val show = enabled && s.isActive && !appVisible && canDraw(context)
        if (!show) { remove(); return }
        if (tab == null) addTab()
        tab?.invalidate()
        cardViews?.let { bindCard(it) }
    }

    fun remove() {
        snapAnim?.cancel()
        hideCard()
        tab?.let { v -> runCatching { wm.removeView(v) } }
        tab = null; tabParams = null
    }

    // ---- Tab ----

    private val tabW get() = dpi(98)
    private val tabH get() = dpi(46)
    private val screenW get() = context.resources.displayMetrics.widthPixels
    private val screenH get() = context.resources.displayMetrics.heightPixels

    private fun addTab() {
        val right = prefs.getBoolean(KEY_RIGHT, true)
        val y = prefs.getInt(KEY_Y, (screenH * 0.42f).roundToInt()).coerceIn(dpi(60), screenH - dpi(140))
        val p = WindowManager.LayoutParams(
            tabW, tabH, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (right) screenW - tabW else 0
            this.y = y
        }
        val v = TabView(context)
        v.dockRight = right
        runCatching { wm.addView(v, p) }.onFailure { return }
        tab = v; tabParams = p
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TabView(context: Context) : View(context) {
        var dockRight = true
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val rect = RectF()
        private val ticker = object : Runnable {
            override fun run() { invalidate(); if (isAttachedToWindow) postDelayed(this, 1_000L) }
        }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false

        init {
            contentDescription = "Flowstate timer. Tap for the session, drag to move."
            setLayerType(LAYER_TYPE_SOFTWARE, null) // for the shadow layer
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
        override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val s = state
            val paused = s.status == Status.PAUSED
            val bg = if (paused) colors.paused else colors.running
            val fg = if (ColorUtils.calculateLuminance(bg) > 0.5) 0xFF141414.toInt() else 0xFFF5F5F5.toInt()
            val r = height / 2f
            val inset = dp(4f)
            // Flat on the docked edge, round on the open side: draw a stadium that runs past the edge.
            if (dockRight) rect.set(inset, inset, width + r, height - inset) else rect.set(-r, inset, width - inset, height - inset)
            fill.color = bg
            fill.setShadowLayer(dp(6f), 0f, dp(2f), 0x40000000)
            canvas.drawRoundRect(rect, r, r, fill)

            val cx = if (dockRight) inset + dp(22f) else width - inset - dp(22f) - dp(50f)
            val cy = height / 2f
            val rr = dp(11f)
            ring.strokeWidth = dp(2.6f)
            ring.color = ColorUtils.setAlphaComponent(fg, 70)
            canvas.drawCircle(cx, cy, rr, ring)
            val now = System.currentTimeMillis()
            val progress = if (s.countsUp || s.phaseDurationMs <= 0L) ((s.elapsedAt(now) % 3_600_000L) / 3_600_000f)
            else (s.elapsedAt(now).toFloat() / s.phaseDurationMs).coerceIn(0f, 1f)
            ring.color = fg
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * progress, false, ring)
            dot.color = fg
            if (paused) {
                val bw = dp(2.4f); val bh = dp(8f)
                canvas.drawRoundRect(cx - dp(3.6f), cy - bh / 2, cx - dp(3.6f) + bw, cy + bh / 2, bw, bw, dot)
                canvas.drawRoundRect(cx + dp(1.2f), cy - bh / 2, cx + dp(1.2f) + bw, cy + bh / 2, bw, bw, dot)
            } else {
                canvas.drawCircle(cx, cy, dp(2.6f), dot)
            }
            text.color = fg
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            val label = clock(if (s.countsUp) s.elapsedAt(now) else s.remainingAt(now))
            text.textSize = text.textSize * (dp(50f) / text.measureText(label).coerceAtLeast(dp(50f)))
            canvas.drawText(label, cx + rr + dp(7f), cy - (text.descent() + text.ascent()) / 2, text)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = tabParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; hideCard() }
                    if (dragging) {
                        p.x = (startX + dx).roundToInt().coerceIn(0, screenW - width)
                        p.y = (startY + dy).roundToInt().coerceIn(dpi(40), screenH - height - dpi(40))
                        runCatching { wm.updateViewLayout(this, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2f)).start()
                    if (dragging) snapToEdge()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); toggleCard() }
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun snapToEdge() {
        val v = tab ?: return; val p = tabParams ?: return
        val right = p.x + v.width / 2 > screenW / 2
        val target = if (right) screenW - v.width else 0
        v.dockRight = right; v.invalidate()
        snapAnim = ValueAnimator.ofInt(p.x, target).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { a ->
                val pp = tabParams ?: return@addUpdateListener
                pp.x = a.animatedValue as Int
                tab?.let { t -> runCatching { wm.updateViewLayout(t, pp) } }
            }
            start()
        }
        prefs.edit().putBoolean(KEY_RIGHT, right).putInt(KEY_Y, p.y).apply()
    }

    // ---- Card ----

    private class CardViews(val root: LinearLayout, val title: TextView, val time: TextView, val bar: View, val barTrack: FrameLayout, val sub: TextView, val pause: TextView, val open: TextView)

    private fun toggleCard() { if (card != null) hideCard() else showCard() }

    private fun hideCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null; cardViews = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCard() {
        val tp = tabParams ?: return
        val width = (screenW - dpi(48)).coerceAtMost(dpi(340))
        val frame = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(18), dpi(20), dpi(18))
            elevation = dp(12f)
        }
        val title = TextView(context).apply { textSize = 13f }
        val time = TextView(context).apply { textSize = 32f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val barTrack = FrameLayout(context)
        val bar = View(context)
        barTrack.addView(bar, FrameLayout.LayoutParams(0, dpi(6)))
        val sub = TextView(context).apply { textSize = 12f }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pause = TextView(context).apply {
            gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setOnClickListener { if (state.status == Status.PAUSED) onResume() else onPause() }
        }
        val open = TextView(context).apply {
            gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text = "Open Flowstate"
            setOnClickListener {
                hideCard()
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        buttons.addView(pause, LinearLayout.LayoutParams(0, dpi(44), 1f).apply { marginEnd = dpi(10) })
        buttons.addView(open, LinearLayout.LayoutParams(0, dpi(44), 1f))
        root.addView(title)
        root.addView(time, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(2) })
        root.addView(barTrack, LinearLayout.LayoutParams(-1, dpi(6)).apply { topMargin = dpi(12) })
        root.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(8) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16) })
        frame.addView(root, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dpi(8), dpi(8), dpi(8), dpi(12)) })
        frame.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideCard(); true } else false }

        val below = tp.y + tabH + dpi(8)
        val y = if (below + dpi(230) < screenH) below else (tp.y - dpi(238)).coerceAtLeast(dpi(40))
        val p = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (tp.x > screenW / 2) screenW - width - dpi(12) else dpi(12)
            this.y = y
            windowAnimations = android.R.style.Animation_Toast
        }
        val views = CardViews(root, title, time, bar, barTrack, sub, pause, open)
        bindCard(views)
        runCatching { wm.addView(frame, p) }.onFailure { return }
        card = frame; cardViews = views
        root.post(object : Runnable {
            override fun run() { cardViews?.let { bindCard(it) }; if (card != null) root.postDelayed(this, 1_000L) }
        })
    }

    private fun bindCard(v: CardViews) {
        val s = state
        val paused = s.status == Status.PAUSED
        val accent = if (paused) colors.paused else colors.running
        // The card itself stays light so it reads on any wallpaper; the palette shows on the bar and button.
        val surface = 0xFFFAFAFA.toInt(); val onSurface = 0xFF141414.toInt(); val muted = 0xFF6E6E6E.toInt()
        // The bar and the Pause button wear the tab's current colour; pure white would vanish on the card, so it reads black.
        val strong = if (ColorUtils.calculateLuminance(accent) > 0.85) 0xFF141414.toInt() else accent
        v.root.background = GradientDrawable().apply { cornerRadius = dp(26f); setColor(surface) }
        val now = System.currentTimeMillis()
        v.title.text = sessionTitle(s); v.title.setTextColor(muted)
        val ms = if (s.countsUp) s.elapsedAt(now) else s.remainingAt(now)
        v.time.text = clock(ms) + if (paused) "  paused" else if (s.countsUp) "" else "  left"
        v.time.setTextColor(onSurface)
        v.barTrack.background = GradientDrawable().apply { cornerRadius = dp(3f); setColor(0xFFE4E4E4.toInt()) }
        v.bar.background = GradientDrawable().apply { cornerRadius = dp(3f); setColor(strong) }
        val frac = if (s.countsUp || s.phaseDurationMs <= 0L) 0f else (s.elapsedAt(now).toFloat() / s.phaseDurationMs).coerceIn(0f, 1f)
        v.barTrack.post { v.bar.layoutParams = v.bar.layoutParams.apply { width = (v.barTrack.width * frac).roundToInt() }; v.bar.requestLayout() }
        v.sub.text = subLine(s); v.sub.setTextColor(muted)
        val locked = s.strict && s.phase == Phase.FOCUS
        v.pause.visibility = if (locked) View.GONE else View.VISIBLE
        v.pause.text = if (paused) "Resume" else "Pause"
        v.pause.setTextColor(if (ColorUtils.calculateLuminance(strong) > 0.5) onSurface else Color.WHITE)
        v.pause.background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(strong) }
        v.open.setTextColor(onSurface)
        v.open.background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(Color.TRANSPARENT); setStroke(dpi(1), 0xFFBDBDBD.toInt()) }
    }

    private fun sessionTitle(s: TimerState): String = when (s.phase) {
        Phase.FOCUS -> if (s.subject.isNotBlank()) "Focus · ${s.subject}" else "Focus"
        Phase.SHORT_BREAK -> if (s.standaloneBreak) "Break" else "Short break"
        Phase.LONG_BREAK -> "Long break"
        Phase.IDLE -> "Flowstate"
    }

    private fun subLine(s: TimerState): String = when {
        s.mode == TimerMode.POMODORO && !s.standaloneBreak -> "Round ${s.round + 1} of ${s.plan.rounds.coerceAtLeast(1)} · Pomodoro"
        s.countsUp -> "Stopwatch"
        else -> s.mode.label
    }

    companion object {
        private const val KEY_RIGHT = "dock_right"
        private const val KEY_Y = "y"

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun clock(ms: Long): String {
            val total = (ms + 999L) / 1_000L
            val h = total / 3_600; val m = (total % 3_600) / 60; val sec = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
        }
    }
}
