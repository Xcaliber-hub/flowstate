package com.flowstate.focus.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowstate.focus.domain.Bucket
import java.time.DayOfWeek
import java.time.LocalDate

/** Replays a spring from 0 to 1 whenever [key] changes, so charts animate in when data or period changes. */
@Composable
private fun rememberReveal(key: Any): Animatable<Float, *> {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) }
    return anim
}

@Composable
private fun BucketLabels(buckets: List<Bucket>) {
    Row(Modifier.fillMaxWidth()) {
        buckets.forEach {
            Text(
                it.label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Composable
fun BarChart(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val reveal = rememberReveal(buckets)
    val max = (buckets.maxOfOrNull { it.valueMs } ?: 0L).coerceAtLeast(1L)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            if (buckets.isEmpty()) return@Canvas
            val slot = size.width / buckets.size
            val barWidth = slot * 0.62f
            val minHeight = 4.dp.toPx()
            buckets.forEachIndexed { i, b ->
                val h = maxOf(size.height * (b.valueMs / max.toFloat()) * reveal.value, minHeight)
                drawRoundRect(
                    color = if (b.valueMs > 0) primary else track,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        BucketLabels(buckets)
    }
}

@Composable
fun LineChart(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val reveal = rememberReveal(buckets)
    val max = (buckets.maxOfOrNull { it.valueMs } ?: 0L).coerceAtLeast(1L)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            if (buckets.size < 2) return@Canvas
            val stepX = size.width / (buckets.size - 1)
            val inset = 4.dp.toPx()
            fun yOf(v: Long) = size.height - inset - (v / max.toFloat()) * (size.height - inset * 2)
            val line = Path().apply {
                buckets.forEachIndexed { i, b ->
                    val x = i * stepX
                    if (i == 0) moveTo(x, yOf(b.valueMs)) else lineTo(x, yOf(b.valueMs))
                }
            }
            clipRect(right = size.width * reveal.value) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        BucketLabels(buckets)
    }
}

/** Calendar heatmap: one column per week, Monday at the top, shading relative to the daily goal. */
@Composable
fun Heatmap(perDay: Map<LocalDate, Long>, goalMs: Long, weeks: Int = 15, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val reveal = rememberReveal(perDay to goalMs)
    val today = LocalDate.now()
    val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
    Canvas(modifier.fillMaxWidth().height(132.dp)) {
        val gap = 4.dp.toPx()
        val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val date = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                if (date.isAfter(today)) continue
                val ms = perDay[date] ?: 0L
                val intensity = if (goalMs > 0) (ms / goalMs.toFloat()).coerceIn(0f, 1f) else 0f
                drawRoundRect(
                    color = if (ms > 0) primary.copy(alpha = (0.25f + 0.75f * intensity) * reveal.value) else empty,
                    topLeft = Offset(w * (cell + gap), d * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(cell * 0.3f),
                )
            }
        }
    }
}
