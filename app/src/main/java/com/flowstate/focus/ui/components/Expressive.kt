package com.flowstate.focus.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 1.7.0 Material 3 Expressive pieces, drawn by hand so no alpha library is needed:
 * the scalloped "cookie" shape, wavy progress, the thick split slider and squarer selected chips.
 * Everything is a plain Canvas or Shape; no blur, no extra layers.
 */

/** M3 Expressive "cookie": a circle with [lobes] soft scallops of relative depth [amp]. */
class CookieShape(private val lobes: Int = 9, private val amp: Float = 0.075f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = minOf(size.width, size.height) / 2f / (1f + amp)
        val path = Path()
        val steps = 180
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val r = base * (1f + amp * cos(lobes * t).toFloat())
            val x = c.x + r * cos(t).toFloat()
            val y = c.y + r * sin(t).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

val Cookie9 = CookieShape(9, 0.08f)
val Cookie8 = CookieShape(8, 0.07f)
val Cookie10 = CookieShape(10, 0.07f)

/** A cookie-shaped badge: filled, or outlined with [border]. */
@Composable
fun CookieBadge(
    size: Dp,
    fill: Color,
    modifier: Modifier = Modifier,
    shape: Shape = Cookie9,
    border: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * M3 Expressive wavy linear progress: a wavy active part, a gap, a flat track and a stop dot.
 * With [animate] the wave drifts; the phase is read only while drawing, so nothing recomposes.
 */
@Composable
fun WavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
    track: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
    strokeWidth: Dp = 5.dp,
    amplitude: Dp = 2.6.dp,
    wavelength: Dp = 14.dp,
    animate: Boolean = false,
) {
    val phase = rememberWavePhase(animate)
    Canvas(modifier.height(amplitude * 2 + strokeWidth + 2.dp)) {
        val sw = strokeWidth.toPx()
        val mid = size.height / 2f
        val end = size.width * progress().coerceIn(0f, 1f)
        val gap = 6.dp.toPx()
        val amp = amplitude.toPx()
        val wl = wavelength.toPx()
        if (end + gap < size.width - sw / 2) {
            drawLine(track, Offset(end + gap, mid), Offset(size.width - sw / 2, mid), sw, StrokeCap.Round)
        }
        drawCircle(color, sw * 0.35f, Offset(size.width - sw / 2, mid))
        if (end > sw / 2) {
            val path = Path()
            val shift = phase.value * wl
            var x = sw / 2
            path.moveTo(x, mid + amp * sin(2 * PI * (x + shift) / wl).toFloat())
            val step = 1.5f
            while (x < end) {
                x = minOf(end, x + step)
                path.lineTo(x, mid + amp * sin(2 * PI * (x + shift) / wl).toFloat())
            }
            drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun rememberWavePhase(animate: Boolean): State<Float> {
    if (!animate) return remember { mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = "wave")
    return t.animateFloat(0f, -1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "wavePhase")
}

/** A wavy slider (the master volume): [WavyProgress] you can drag or tap. */
@Composable
fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    label: String = "",
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Box(
        modifier
            .height(40.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); finished(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)); finished() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { finished() }) { ch, _ ->
                    change((ch.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        WavyProgress(progress = { current }, modifier = Modifier.fillMaxWidth(), amplitude = 2.8.dp, wavelength = 14.dp)
    }
}

/**
 * M3 Expressive slider: a thick rounded active track, a gap, a thin tall handle, then the inactive
 * track with a stop dot. [enabled] false draws the "off" look (handle parked at the start) but
 * still accepts a drag, which switches the sound on.
 */
@Composable
fun SplitSlider(
    value: Float,
    on: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    label: String = "",
) {
    val cs = MaterialTheme.colorScheme
    val active = cs.secondary
    val inactive = cs.surfaceVariant
    val handle = cs.onSurface
    val offHandle = cs.outlineVariant
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Canvas(
        modifier
            .height(40.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(if (on) current else 0f, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); finished(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)); finished() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { finished() }) { ch, _ ->
                    change((ch.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val h = size.height
        val trackH = 24.dp.toPx()
        val top = (h - trackH) / 2f
        val hw = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val big = CornerRadius(trackH / 2f)
        val small = CornerRadius(6.dp.toPx())
        val frac = if (on) current.coerceIn(0f, 1f) else 0f
        val hx = (hw / 2f + (size.width - hw) * frac).coerceIn(hw / 2f, size.width - hw / 2f)
        if (on && hx - hw / 2 - gap > 2f) {
            drawPath(Path().apply {
                addRoundRect(RoundRect(0f, top, hx - hw / 2 - gap, top + trackH, big, small, small, big))
            }, active)
        }
        val inStart = hx + hw / 2 + gap
        if (size.width - inStart > 2f) {
            drawPath(Path().apply {
                addRoundRect(RoundRect(inStart, top, size.width, top + trackH, small, big, big, small))
            }, inactive)
            drawCircle(if (on) active else offHandle, 2.dp.toPx(), Offset(size.width - 10.dp.toPx(), h / 2f))
        }
        val handleH = if (on) h else trackH + 8.dp.toPx()
        drawRoundRect(
            if (on) handle else offHandle,
            topLeft = Offset(hx - hw / 2f, (h - handleH) / 2f),
            size = Size(hw, handleH),
            cornerRadius = CornerRadius(hw / 2f),
        )
    }
}

/**
 * Selectable chip whose corners square off when selected (M3 Expressive shape morph), filled with
 * the light accent. Text never wraps.
 */
@Composable
fun ExpressiveChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val cs = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 11.dp else 17.dp, label = "chipRadius")
    val shape = RoundedCornerShape(radius)
    Row(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) cs.secondary else cs.surfaceContainer)
            .then(if (selected) Modifier else Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), shape))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(start = if (icon != null) 9.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (selected) cs.onSecondary else cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSecondary else cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * M3 Expressive connected button group: segments with small inner corners; the selected one
 * becomes a full pill and grows a little.
 */
@Composable
fun <T> ConnectedButtonGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = o == selected
            val outer = 22.dp
            val inner = 8.dp
            val start by animateDpAsState(if (on || i == 0) outer else inner, label = "segStart")
            val end by animateDpAsState(if (on || i == options.lastIndex) outer else inner, label = "segEnd")
            val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
            Box(
                Modifier
                    .weight(if (on) 1.25f else 1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (on) cs.secondary else cs.surfaceContainerHigh)
                    .clickable(role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) cs.onSecondary else cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** Small uppercase section label, as in the mockups ("MASTER", "UPCOMING"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em, fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}
