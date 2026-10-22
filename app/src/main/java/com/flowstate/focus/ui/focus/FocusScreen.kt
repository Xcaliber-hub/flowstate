package com.flowstate.focus.ui.focus

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.formatClock
import com.flowstate.focus.ui.components.formatDuration
import com.flowstate.focus.ui.components.screenContentPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FocusScreen(settings: AppSettings, vm: FocusViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    val keepOn = settings.keepScreenOn && ui.timer.status == Status.RUNNING
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    fun requestStart() {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    ScreenContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Entrance(0) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Focus", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ModeChips(ui.mode, enabled = !ui.timer.isActive, onSelect = vm::setMode)
                    }
                }
            }
            item(key = "timer") {
                Entrance(1) {
                    TimerCard(
                        ui = ui,
                        onStart = ::requestStart,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onStop = vm::stop,
                        onForceStop = vm::forceStop,
                        onStrict = vm::setStrict,
                        onSubject = vm::setSubject,
                        onDuration = vm::setDuration,
                    )
                }
            }
            item(key = "tiles") {
                Entrance(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatTile("Today", formatDuration(ui.todayMs), null, ui.settings.liquidUi, Modifier.weight(1f))
                        StatTile("Streak", if (ui.streak == 1) "1 day" else "${ui.streak} days", Icons.Rounded.LocalFireDepartment, ui.settings.liquidUi, Modifier.weight(1f))
                    }
                }
            }
            item(key = "goal") { Entrance(3) { GoalCard(ui) } }
            item(key = "quote") { Entrance(4) { QuoteCard(ui.settings.liquidUi) } }
            item(key = "sessions") { Entrance(5) { SessionsCard(ui.todaySessions, ui.settings.liquidUi) } }
        }
    }
}

@Composable
private fun ModeChips(selected: TimerMode, enabled: Boolean, onSelect: (TimerMode) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimerMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                enabled = enabled,
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun TimerCard(
    ui: FocusUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onStrict: (Boolean) -> Unit,
    onSubject: (String) -> Unit,
    onDuration: (Int) -> Unit,
) {
    val t = ui.timer
    GlassCard(Modifier.fillMaxWidth(), liquid = ui.settings.liquidUi) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TimerDisplay(state = t, mode = ui.mode, idleMs = ui.durationMin * 60_000L, liquid = ui.settings.liquidUi)
            Spacer(Modifier.height(20.dp))
            if (!t.isActive) {
                SetupSection(ui, onStrict, onSubject, onDuration)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start focus", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                ActiveControls(t, onPause, onResume, onStop, onForceStop)
            }
        }
    }
}

@Composable
private fun SetupSection(ui: FocusUiState, onStrict: (Boolean) -> Unit, onSubject: (String) -> Unit, onDuration: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.mode.countsUp) {
            val range = if (ui.mode == TimerMode.POMODORO) 5f..90f else 5f..180f
            var local by remember(ui.durationMin, ui.mode) { mutableFloatStateOf(ui.durationMin.toFloat()) }
            Row(Modifier.fillMaxWidth()) {
                Text(if (ui.mode == TimerMode.POMODORO) "Focus length" else "Duration", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("${local.roundToInt()} min", style = MaterialTheme.typography.titleSmall)
            }
            Slider(
                value = local.coerceIn(range),
                onValueChange = { local = it },
                onValueChangeFinished = { onDuration(local.roundToInt()) },
                valueRange = range,
            )
        }
        OutlinedTextField(
            value = ui.subject,
            onValueChange = onSubject,
            label = { Text("Subject (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Strict mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "No pausing or quitting until the block ends",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.strict, onCheckedChange = onStrict)
        }
    }
}

@Composable
private fun ActiveControls(t: TimerState, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onForceStop: () -> Unit) {
    if (t.strict && t.phase == Phase.FOCUS) {
        Text(
            "Strict mode is on",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HoldToQuit(onForceStop)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val running = t.status == Status.RUNNING
            FilledTonalButton(
                onClick = if (running) onPause else onResume,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(30.dp),
            ) {
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (running) "Pause" else "Resume")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(30.dp)) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (t.countsUp && t.phase == Phase.FOCUS) "Finish" else "Stop")
            }
        }
    }
}

/** Emergency exit for strict mode: it takes a deliberate four-second hold, so it cannot be an impulse tap. */
@Composable
private fun HoldToQuit(onQuit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val fill = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .drawBehind { drawRect(fill, size = Size(size.width * progress.value, size.height)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(4_000, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onQuit()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = "Hold for four seconds to quit this strict session"
                onLongClick(label = "Quit session") { onQuit(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Hold to quit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** Ticks only while running, and only this composable recomposes when the visible second changes. */
// The producer assigns its receiver value below; Compose lint misses this with Kotlin 2.0.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberNow(running: Boolean): State<Long> =
    produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(200)
        }
    }

@Composable
private fun TimerDisplay(state: TimerState, mode: TimerMode, idleMs: Long, liquid: Boolean) {
    val running = state.status == Status.RUNNING
    val now = rememberNow(running)
    val countsUp = if (state.isActive) state.countsUp else mode.countsUp

    val seconds by remember(state, idleMs, countsUp) {
        derivedStateOf {
            when {
                !state.isActive -> if (countsUp) 0L else idleMs / 1000
                countsUp -> state.elapsedAt(now.value) / 1000
                else -> (state.remainingAt(now.value) + 999) / 1000
            }
        }
    }
    val progress: () -> Float = {
        when {
            !state.isActive -> if (countsUp) 0f else 1f
            countsUp -> (state.elapsedAt(now.value) % 3_600_000L) / 3_600_000f
            else -> state.remainingAt(now.value) / state.phaseDurationMs.toFloat()
        }
    }
    val label = when (state.phase) {
        Phase.IDLE -> mode.label
        Phase.FOCUS -> if (state.status == Status.PAUSED) "Paused" else "Focusing"
        Phase.SHORT_BREAK -> "Short break"
        Phase.LONG_BREAK -> "Long break"
    }
    val clock = formatClock(seconds)

    Box(
        modifier = Modifier
            .size(280.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $clock" },
        contentAlignment = Alignment.Center,
    ) {
        ProgressRing(progress = progress, liquid = liquid, active = running)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock, style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center)
            val sub = when {
                state.mode == TimerMode.POMODORO && state.isActive -> "Round ${state.round + 1}"
                state.subject.isNotBlank() -> state.subject
                else -> ""
            }
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberWobble(enabled: Boolean): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "wobble")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing), RepeatMode.Restart),
        label = "wobblePhase",
    )
}

/**
 * Progress ring. Progress and the Liquid UI wobble are read inside the draw block only,
 * so animating the ring never triggers recomposition.
 */
@Composable
private fun ProgressRing(progress: () -> Float, liquid: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val track = if (cs.background == androidx.compose.ui.graphics.Color.Black) cs.surfaceVariant
    else cs.surfaceVariant.copy(alpha = 0.5f)
    val primary = cs.primary
    val tertiary = cs.tertiary
    val wobble = rememberWobble(liquid && active)
    Canvas(modifier.fillMaxSize()) {
        val stroke = 18.dp.toPx()
        val pad = stroke + 6.dp.toPx()
        val topLeft = Offset(pad, pad)
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

        val sweep = 360f * progress().coerceIn(0f, 1f)
        if (sweep < 1f) return@Canvas
        val wave = if (liquid && active) sin(wobble.value) else 0f
        rotate(-90f, center) {
            if (liquid && active) {
                drawArc(primary.copy(alpha = 0.16f), 0f, sweep, false, topLeft, arcSize, style = Stroke(stroke + 12.dp.toPx() + wave * 4.dp.toPx(), cap = StrokeCap.Round))
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(primary, tertiary, primary), center),
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke * (1f + 0.08f * wave), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, liquid: Boolean, modifier: Modifier) {
    GlassCard(modifier, liquid) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun GoalCard(ui: FocusUiState) {
    val goalMs = ui.settings.dailyGoalMin * 60_000L
    val ratio = if (goalMs > 0) (ui.todayMs / goalMs.toFloat()).coerceIn(0f, 1f) else 0f
    GlassCard(Modifier.fillMaxWidth(), ui.settings.liquidUi) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today's goal", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${formatDuration(ui.todayMs)} of ${formatDuration(goalMs)}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(20.dp))
        Text("This week", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ui.week.forEachIndexed { index, ms ->
                val day = LocalDate.now().minusDays((6 - index).toLong())
                val fill = if (goalMs > 0) (ms / goalMs.toFloat()).coerceIn(0f, 1f) else 0f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f + 0.9f * fill))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private val Quotes = listOf(
    "Start with ten minutes. Momentum will do the rest.",
    "One focused hour beats three distracted ones.",
    "Put the phone down. The work is the reward.",
    "Small sessions, stacked daily, change everything.",
    "You do not need motivation, only a timer and a first step.",
    "Deep work is a skill. Every session trains it.",
    "Finish this block, then decide what comes next.",
    "Quiet attention is rare. Protect it.",
    "Progress is built from sessions you actually finish.",
    "Today's effort is tomorrow's ease.",
    "Do one thing well, then the next.",
    "Focus is choosing what to ignore.",
)

@Composable
private fun QuoteCard(liquid: Boolean) {
    val quote = Quotes[LocalDate.now().toEpochDay().mod(Quotes.size.toLong()).toInt()]
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text(quote, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SessionsCard(sessions: List<SessionEntity>, liquid: Boolean) {
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text("Today's sessions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(
                "No sessions yet. Start one above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
            sessions.take(8).forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).format(fmt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        s.subject.ifBlank { TimerMode.valueOf(s.mode).label },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(formatDuration(s.durationMs), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
