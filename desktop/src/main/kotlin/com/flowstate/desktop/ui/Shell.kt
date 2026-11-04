package com.flowstate.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.desktop.Screen
import kotlin.math.PI
import kotlin.math.sin

private val screenIcons: Map<Screen, ImageVector> = mapOf(
    Screen.FOCUS to Icons.Outlined.Timer,
    Screen.STATS to Icons.Rounded.BarChart,
    Screen.TASKS to Icons.Outlined.CheckBox,
    Screen.GROUPS to Icons.Outlined.Groups,
    Screen.SETTINGS to Icons.Outlined.Settings,
)

/** Window background: near-black with two soft slate glows. */
fun Modifier.windowBackground(): Modifier = this
    .background(Slate.Window)
    .background(Brush.radialGradient(listOf(Slate.Steel.copy(alpha = 0.35f), Color.Transparent), center = Offset(260f, 120f), radius = 1100f))
    .background(Brush.radialGradient(listOf(Slate.Slate.copy(alpha = 0.18f), Color.Transparent), center = Offset(1500f, 1000f), radius = 900f))

/**
 * The app frame: the floating glass nav capsule (92 wide, fully rounded) with the daily-goal water bar
 * at its foot, and the current screen beside it.
 */
@Composable
fun Shell(
    screen: Screen,
    onNavigate: (Screen) -> Unit,
    goalFraction: Float,
    waterTime: Float,
    badges: Set<Screen> = emptySet(),
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().windowBackground()) {
        NavRail(screen, onNavigate, goalFraction, waterTime, badges)
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, top = 22.dp, end = 28.dp, bottom = 26.dp)) { content() }
    }
}

@Composable
private fun NavRail(screen: Screen, onNavigate: (Screen) -> Unit, goalFraction: Float, waterTime: Float, badges: Set<Screen>) {
    val shape = RoundedCornerShape(46.dp)
    Column(
        Modifier.padding(start = 18.dp, top = 22.dp, bottom = 26.dp).width(92.dp).fillMaxHeight().glass(shape).padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Screen.entries.forEach { s ->
            val on = s == screen
            val itemShape = RoundedCornerShape(32.dp)
            Box(Modifier.padding(bottom = 8.dp)) {
                Column(
                    Modifier.size(70.dp, 64.dp).clip(itemShape)
                        .then(if (on) Modifier.background(Slate.Fog.copy(alpha = 0.12f)).border(1.dp, Slate.Fog.copy(alpha = 0.18f), itemShape) else Modifier)
                        .clickable { onNavigate(s) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(screenIcons.getValue(s), s.label, tint = if (on) Slate.Paper else Slate.Mist, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(5.dp))
                    T(s.label, 12.sp, if (on) FontWeight.Bold else FontWeight.Medium, if (on) Slate.Paper else Slate.Mist)
                }
                if (s in badges) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 14.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(Slate.Paper))
            }
        }
        Spacer(Modifier.weight(1f))
        WaterTube(goalFraction, waterTime)
        Spacer(Modifier.height(24.dp))
    }
}

/** The daily-goal water bar: a slim tube filled to today's share of the goal, with a gently moving surface. */
@Composable
fun WaterTube(fraction: Float, time: Float, height: Dp = 170.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T("${(fraction.coerceIn(0f, 1f) * 100).toInt()}%", 13.sp, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val shape = RoundedCornerShape(10.dp)
        Canvas(Modifier.size(16.dp, height).clip(shape).background(Slate.Fog.copy(alpha = 0.06f)).border(1.dp, Slate.Fog.copy(alpha = 0.18f), shape)) {
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0f) return@Canvas
            val top = size.height * (1f - f)
            val amp = 2.2f
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, top)
                val steps = 16
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    lineTo(x, top + amp * sin((i / steps.toFloat()) * 2 * PI.toFloat() + time * 2.4f))
                }
                lineTo(size.width, size.height)
                close()
            }
            clipPath(path) {
                drawRect(Brush.verticalGradient(listOf(Slate.Fog, Slate.Mist, Slate.Slate), startY = top - 4f, endY = size.height))
            }
        }
        Spacer(Modifier.height(8.dp))
        T("Daily\ngoal", 11.sp, color = Slate.Mist, maxLines = 2, align = TextAlign.Center, lineHeight = 14.sp)
    }
}

/** The glass header capsule on every screen: goal ring, title, subtitle and trailing actions. */
@Composable
fun TopBar(title: String, subtitle: String, ringFraction: Float, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).glass(RoundedCornerShape(32.dp)).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(38.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Slate.Fog.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(Slate.Paper, -90f, 360f * ringFraction.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(Modifier.weight(1f)) {
            T(title, 21.sp, FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            T(subtitle, 12.5.sp, color = Slate.Mist)
        }
        trailing()
    }
}

/** A single-line text field in the pill style. */
@Composable
fun PillField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    password: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onEnter: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.height(height).clip(shape).background(Slate.Ink.copy(alpha = 0.55f)).border(1.dp, Slate.Fog.copy(alpha = 0.16f), shape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) T(placeholder, 14.sp, color = Slate.Mist)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Slate.Paper, fontSize = 14.sp),
                cursorBrush = SolidColor(Slate.Paper),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().then(
                    if (onEnter != null) Modifier.onEnterKey(onEnter) else Modifier
                ),
            )
        }
    }
}

fun Modifier.onEnterKey(action: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) { action(); true } else false
}
