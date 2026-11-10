package com.flowstate.focus.ui.syllabus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.domain.SyllabusStep
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.LocalGlassUi
import com.flowstate.focus.ui.components.glassPanel
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material3.Icon

/** Progress ring (1.8.0 mockups): a track and a round-capped arc from the top, content in the middle. */
@Composable
fun SyllabusRing(
    fraction: Float,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "syllabusRing")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2 + 1f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(cs.surfaceVariant, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            if (animated > 0f) drawArc(cs.secondary, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Thin rounded bar: Slate track, lighter fill. */
@Composable
fun SyllabusBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val cs = MaterialTheme.colorScheme
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "syllabusBar")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(this.size.height / 2, this.size.height / 2)
        drawRoundRect(cs.surfaceVariant, cornerRadius = r)
        if (animated > 0f) drawRoundRect(cs.primary, size = Size(maxOf(this.size.height, this.size.width * animated), this.size.height), cornerRadius = r)
    }
}

/** A dashed outline for the next step to log. */
fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()), style = Stroke(w, pathEffect = dash),
    )
}

enum class TickLook { ON, NEXT, OFF }

/** One of the seven step chips on a chapter row. Tap to tick or untick. */
@Composable
fun TickChip(step: SyllabusStep, look: TickLook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.height(30.dp).clip(shape)
    val styled = when (look) {
        TickLook.ON -> base.background(cs.secondary)
        TickLook.NEXT -> base.background(cs.surfaceContainerHigh).dashedOutline(cs.primary, 10.dp)
        TickLook.OFF -> base.background(cs.surfaceContainer).border(1.dp, cs.secondary.copy(alpha = 0.16f), shape)
    }
    Box(
        styled
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = step.full
                stateDescription = if (look == TickLook.ON) "Done" else "Not done"
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        AutoSizeText(
            step.short,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
            color = when (look) { TickLook.ON -> cs.onSecondary; TickLook.NEXT -> cs.onSurface; TickLook.OFF -> cs.outline },
            minSize = 7.sp,
        )
    }
}

/**
 * 1.8.0: the Tasks | Syllabus segmented toggle at the top of Tasks (mockup flowstate-1.8.0-tasks.png).
 * The chosen segment is a light Slate pill with a check; the other keeps its icon. With Glass UI on the
 * unselected segment is a glass panel, otherwise a flat container.
 */
@Composable
fun TasksSyllabusToggle(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val glass = LocalGlassUi.current
    val options = listOf(SyllabusViewModel.TAB_TASKS to "Tasks", SyllabusViewModel.TAB_SYLLABUS to "Syllabus")
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            val outer = 26.dp
            val inner = 10.dp
            val start by animateDpAsState(if (on || i == 0) outer else inner, label = "tabStart")
            val end by animateDpAsState(if (on || i == options.lastIndex) outer else inner, label = "tabEnd")
            val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
            val surface = when {
                on -> Modifier.clip(shape).background(cs.secondary)
                glass -> Modifier.glassPanel(shape)
                else -> Modifier.clip(shape).background(cs.surfaceContainerHigh)
            }
            Row(
                Modifier
                    .weight(if (on) 1.15f else 1f)
                    .height(52.dp)
                    .then(surface)
                    .clip(shape)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) })
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    on -> Icons.Rounded.Check
                    value == SyllabusViewModel.TAB_TASKS -> Icons.Rounded.CheckBox
                    else -> Icons.AutoMirrored.Rounded.MenuBook
                }
                val tint = if (on) cs.onSecondary else cs.onSurfaceVariant
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                AutoSizeText(label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = if (on) cs.onSecondary else cs.onSurface, minSize = 11.sp)
            }
        }
    }
}
