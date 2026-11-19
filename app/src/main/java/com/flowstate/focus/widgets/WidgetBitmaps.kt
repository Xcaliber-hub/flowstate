package com.flowstate.focus.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1.8.8: the widgets' rings, bars, charts and heatmap are drawn here in the user's accent. RemoteViews cannot tint
 * a ProgressBar or draw an arc before Android 12, so a small bitmap is the one way that works on every version.
 *
 * Bitmaps travel to the launcher through a binder call (about 1 MB per update), so they are drawn at no more than
 * 2 px per dp and kept to the size they are shown at.
 */
internal object WidgetBitmaps {
    private const val MAX_SCALE = 2f

    fun scale(context: Context): Float = min(context.resources.displayMetrics.density, MAX_SCALE)

    private fun px(context: Context, dp: Float): Int = max(1, (dp * scale(context)).roundToInt())

    /** A progress ring: [track] full circle, then [fraction] of it in [color] from 12 o'clock, round caps. */
    fun ring(context: Context, sizeDp: Float, strokeDp: Float, fraction: Float, color: Int, track: Int): Bitmap {
        val size = px(context, sizeDp)
        val stroke = strokeDp * scale(context)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val inset = stroke / 2f + 0.5f
        val oval = RectF(inset, inset, size - inset, size - inset)
        p.color = track
        c.drawArc(oval, 0f, 360f, false, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = color
            c.drawArc(oval, -90f, max(f * 360f, 1f), false, p)
        }
        return bmp
    }

    /** A horizontal pill bar, [fraction] filled. Shown with scaleType fitXY in a match_parent ImageView. */
    fun bar(context: Context, widthDp: Float, heightDp: Float, fraction: Float, color: Int, track: Int): Bitmap {
        val w = px(context, widthDp)
        val h = px(context, heightDp)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = h / 2f
        p.color = track
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = color
            c.drawRoundRect(RectF(0f, 0f, max(h.toFloat(), w * f), h.toFloat()), r, r, p)
        }
        return bmp
    }

    /**
     * This week's bars, Monday first, one per column of equal width (the day labels below use the same columns).
     * Days before today in the accent at 45 %, today in the full accent, days still to come as a short track stub.
     */
    fun weekChart(context: Context, widthDp: Float, heightDp: Float, week: List<Long>, todayIndex: Int, goalMs: Long, color: Int, track: Int): Bitmap {
        val w = px(context, widthDp)
        val h = px(context, heightDp)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cols = week.size.coerceAtLeast(1)
        val colW = w / cols.toFloat()
        val barW = min(colW * 0.5f, 10f * scale(context))
        val top = max(week.maxOrNull() ?: 0L, max(goalMs, 1L)).toFloat()
        val minH = barW
        week.forEachIndexed { i, ms ->
            val cx = colW * i + colW / 2f
            val left = cx - barW / 2f
            val future = i > todayIndex
            val bh = if (future || ms <= 0L) minH else max(minH, h * (ms / top))
            p.color = when {
                future || ms <= 0L -> track
                i == todayIndex -> color
                else -> WidgetColors.withAlpha(color, 0.45f)
            }
            c.drawRoundRect(RectF(left, h - bh, left + barW, h.toFloat()), barW / 2f, barW / 2f, p)
        }
        return bmp
    }

    /** 4 rows of 7 rounded cells, oldest first, shaded by level 0..4. Square cells; shown with fitCenter. */
    fun heatmap(context: Context, cellDp: Float, gapDp: Float, levels: List<Int>, color: Int, track: Int): Bitmap {
        val cell = cellDp * scale(context)
        val gap = gapDp * scale(context)
        val cols = 7
        val rows = (levels.size + cols - 1) / cols
        val w = (cols * cell + (cols - 1) * gap).roundToInt().coerceAtLeast(1)
        val h = (rows * cell + (rows - 1) * gap).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = cell * 0.24f
        val alphas = floatArrayOf(0f, 0.30f, 0.52f, 0.76f, 1f)
        levels.forEachIndexed { i, level ->
            val x = (i % cols) * (cell + gap)
            val y = (i / cols) * (cell + gap)
            p.color = if (level <= 0) track else WidgetColors.withAlpha(color, alphas[level.coerceIn(1, 4)])
            c.drawRoundRect(RectF(x, y, x + cell, y + cell), r, r, p)
        }
        return bmp
    }

    /**
     * A round avatar with a light rim: the preset image when there is one, else the initial on the accent.
     * Decoded at the size it is shown, never at the full 464 px.
     */
    fun avatar(context: Context, sizeDp: Float, avatarId: String, name: String, accent: Int, onAccent: Int, rim: Int): Bitmap {
        val size = px(context, sizeDp)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rimW = max(1f, 1.5f * scale(context))
        val r = size / 2f
        val resId = avatarId.takeIf { it.matches(Regex("[a-z0-9_]+")) }
            ?.let { context.resources.getIdentifier("avatar_$it", "drawable", context.packageName) } ?: 0
        val src = if (resId != 0) decodeSampled(context, resId, size) else null
        if (src != null) {
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            val s = size / min(src.width, src.height).toFloat()
            m.setScale(s, s)
            m.postTranslate((size - src.width * s) / 2f, (size - src.height * s) / 2f)
            shader.setLocalMatrix(m)
            p.shader = shader
            c.drawCircle(r, r, r - rimW / 2f, p)
            p.shader = null
        } else {
            p.color = accent
            c.drawCircle(r, r, r - rimW / 2f, p)
            p.color = onAccent
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = size * 0.42f
            val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            c.drawText(initial, r, r - (p.descent() + p.ascent()) / 2f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = rimW
        p.color = rim
        c.drawCircle(r, r, r - rimW / 2f, p)
        return bmp
    }

    private fun decodeSampled(context: Context, resId: Int, target: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
