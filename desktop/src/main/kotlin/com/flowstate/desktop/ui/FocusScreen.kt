package com.flowstate.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.desktop.FocusSelection
import com.flowstate.desktop.cloud.RemoteTimer
import com.flowstate.desktop.store.DesktopSettings
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class FocusUi(
    val timer: TimerState,
    val selection: FocusSelection,
    val settings: DesktopSettings,
    /** Focus already recorded today (all devices); the running block is added on top. */
    val recordedTodayMs: Long,
    val sessionsToday: Int,
    val streak: Int,
    val tasks: List<TaskEntity>,
    val remote: RemoteTimer?,
    val signedIn: Boolean,
    val now: Long,
    val today: LocalDate = LocalDate.now(),
)

class FocusActions(
    val onMode: (TimerMode) -> Unit = {},
    val onDuration: (Int) -> Unit = {},
    val onSubject: (String) -> Unit = {},
    val onStrict: (Boolean) -> Unit = {},
    val onStart: () -> Unit = {},
    val onBreak: (Int) -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onForceStop: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onStartFocusFromBreak: () -> Unit = {},
    val onConfirmPresence: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
)

fun FocusUi.liveTodayMs(): Long = recordedTodayMs + if (timer.phase == Phase.FOCUS && timer.isActive) timer.elapsedAt(now) else 0L

@Composable
fun FocusScreen(ui: FocusUi, a: FocusActions) {
    val t = ui.timer
    val mode = if (t.isActive) t.mode else ui.selection.mode
    val goalMs = ui.settings.dailyGoalMin * 60_000L
    val todayMs = ui.liveTodayMs()
    val remote = ui.remote
    var keptRemote by remember(remote?.deviceId, remote?.state?.phaseStartedAt, remote?.state?.status) { mutableStateOf(false) }
    val locked = remote != null && !t.isActive

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Focus",
            subtitle = ui.today.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)) + " · " +
                if (locked) "Running on ${remote!!.deviceLabel}" else mode.label,
            ringFraction = if (goalMs > 0) todayMs / goalMs.toFloat() else 0f,
        ) {
            Chip {
                Icon(if (remote?.onPhone == true) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = Slate.Fog, modifier = Modifier.size(16.dp))
                if (!ui.signedIn) T("Signed out · the timer stays on this PC", 13.sp, color = Slate.Fog)
                else {
                    T("Timer runs on one device at a time ·", 13.sp, color = Slate.Fog)
                    T(when { locked -> remote!!.deviceLabel.replaceFirstChar { it.uppercase() }; else -> "This PC" }, 13.sp, FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().card(28.dp)) {
                val showOverlay = locked && !keptRemote
                Box(Modifier.fillMaxSize().then(if (showOverlay) Modifier.blur(14.dp) else Modifier)) {
                    if (locked) RemoteTimerView(remote!!, ui.now, onTakeOver = a.onTakeOver, compact = !showOverlay)
                    else TimerPanel(ui, mode, a)
                }
                if (showOverlay) {
                    Box(Modifier.fillMaxSize().background(Slate.Window.copy(alpha = 0.45f)))
                    LockedCard(remote!!, ui.now, Modifier.align(Alignment.Center), onTakeOver = a.onTakeOver, onKeep = { keptRemote = true })
                }
                if (t.awaitingCheckIn) {
                    Box(Modifier.fillMaxSize().background(Slate.Window.copy(alpha = 0.55f)))
                    CheckInCard(t, ui.now, Modifier.align(Alignment.Center), a.onConfirmPresence)
                }
            }
            Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TodayCard(todayMs, goalMs, ui.sessionsToday, ui.streak)
                UpNextCard(ui.tasks, ui.selection.subject.ifBlank { t.subject }, enabled = !t.isActive && !locked, onPick = { a.onSubject(it.title) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TimerPanel(ui: FocusUi, mode: TimerMode, a: FocusActions) {
    val t = ui.timer
    val durationMin = ui.selection.durationMin ?: if (mode == TimerMode.COUNTDOWN) ui.settings.countdownMin else ui.settings.focusMin
    val strict = ui.selection.strict ?: ui.settings.strictByDefault
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(30.dp))
        Segmented(TimerMode.entries.map { it.label }, TimerMode.entries.indexOf(mode), enabled = !t.isActive) { a.onMode(TimerMode.entries[it]) }
        Spacer(Modifier.height(28.dp))
        val (fraction, clock, top, bottom) = ringContent(t, mode, durationMin, ui.settings, ui.now)
        Box(Modifier.size(380.dp), contentAlignment = Alignment.Center) {
            TimerRing(fraction, t.status == Status.PAUSED)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(top, 16.sp, FontWeight.Medium, Slate.Mist)
                Spacer(Modifier.height(10.dp))
                T(clock, 88.sp, FontWeight.Light, letterSpacing = (-2).sp)
                Spacer(Modifier.height(10.dp))
                if (!t.isActive && !mode.countsUp) DurationStepper(durationMin, mode, a.onDuration)
                else T(bottom, 14.sp, color = Slate.Mist)
            }
        }
        Spacer(Modifier.height(26.dp))
        SubjectPill(if (t.isActive) t.subject else ui.selection.subject, editable = !t.isActive, onChange = a.onSubject)
        Spacer(Modifier.height(26.dp))
        Controls(t, strict, a, ui.settings.shortBreakMin)
        Spacer(Modifier.height(30.dp))
    }
}

private data class RingContent(val fraction: Float, val clock: String, val top: String, val bottom: String)

private fun ringContent(t: TimerState, mode: TimerMode, durationMin: Int, s: DesktopSettings, now: Long): RingContent {
    if (!t.isActive) {
        return if (mode.countsUp) RingContent(0f, "00:00", mode.label, "Counts up until you stop")
        else RingContent(1f, formatClock(durationMin * 60_000L), mode.label, "")
    }
    val block = (t.round % t.plan.rounds.coerceAtLeast(1)) + 1
    val rounds = t.plan.rounds.coerceAtLeast(1)
    val paused = t.status == Status.PAUSED
    return when {
        t.countsUp -> {
            val e = t.elapsedAt(now)
            RingContent((e % 3_600_000L) / 3_600_000f, formatElapsed(e), mode.label, if (paused) "paused · elapsed" else "elapsed")
        }
        t.isBreak -> {
            val label = if (t.phase == Phase.LONG_BREAK) "Long break" else "Short break"
            val rem = t.remainingAt(now)
            RingContent(rem / t.phaseDurationMs.toFloat(), formatClock(rem), label,
                (if (paused) "paused · " else "") + if (t.standaloneBreak) "until you're back" else "then block ${block} of $rounds")
        }
        else -> {
            val rem = t.remainingAt(now)
            val tail = if (t.mode == TimerMode.POMODORO) " · Block $block of $rounds" else ""
            RingContent(rem / t.phaseDurationMs.toFloat(), formatClock(rem), mode.label, (if (paused) "paused" else "remaining") + tail)
        }
    }
}

@Composable
private fun TimerRing(fraction: Float, paused: Boolean) {
    Canvas(Modifier.size(380.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        // Minute ticks round the dial.
        for (i in 0 until 60) {
            val ang = (i / 60f) * 2 * PI.toFloat() - PI.toFloat() / 2
            val long = i % 5 == 0
            val outer = r - 2.dp.toPx()
            val inner = outer - (if (long) 10.dp else 6.dp).toPx()
            drawLine(Slate.Fog.copy(alpha = if (long) 0.35f else 0.18f), Offset(c.x + cos(ang) * inner, c.y + sin(ang) * inner), Offset(c.x + cos(ang) * outer, c.y + sin(ang) * outer), 1.4f)
        }
        val stroke = 14.dp.toPx()
        val ringR = r - 26.dp.toPx()
        val tl = Offset(c.x - ringR, c.y - ringR)
        val sz = Size(ringR * 2, ringR * 2)
        drawArc(Slate.Steel.copy(alpha = 0.55f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
        if (fraction > 0f) drawArc(
            Brush.sweepGradient(listOf(Slate.Fog, Slate.Paper, Slate.Mist, Slate.Fog), c),
            -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, alpha = if (paused) 0.55f else 1f,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawCircle(Slate.Ink.copy(alpha = 0.35f), ringR - stroke, c)
        drawCircle(Slate.Fog.copy(alpha = 0.06f), ringR - stroke, c, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun DurationStepper(minutes: Int, mode: TimerMode, onDuration: (Int) -> Unit) {
    val range = if (mode == TimerMode.POMODORO) 5..90 else 5..180
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton(Icons.Rounded.Remove, 30.dp, enabled = minutes > range.first, contentDescription = "Shorter") { onDuration((minutes - 5).coerceIn(range)) }
        T("$minutes min focus", 14.sp, color = Slate.Mist)
        CircleIconButton(Icons.Rounded.Add, 30.dp, enabled = minutes < range.last, contentDescription = "Longer") { onDuration((minutes + 5).coerceIn(range)) }
    }
}

@Composable
private fun SubjectPill(subject: String, editable: Boolean, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.height(44.dp).widthIn(min = 220.dp, max = 460.dp).glass(shape).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editable) {
            Box(Modifier.widthIn(min = 140.dp, max = 340.dp), contentAlignment = Alignment.CenterStart) {
                if (subject.isEmpty()) T("Add a subject", 15.sp, FontWeight.SemiBold, Slate.Mist)
                BasicTextField(subject, onChange, singleLine = true, cursorBrush = SolidColor(Slate.Paper),
                    textStyle = TextStyle(color = Slate.Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            }
        } else T(subject.ifBlank { "No subject" }, 15.sp, FontWeight.SemiBold, if (subject.isBlank()) Slate.Mist else Slate.Paper)
        T("· Subject", 12.sp, FontWeight.Medium, Slate.Mist)
    }
}

@Composable
private fun Controls(t: TimerState, strict: Boolean, a: FocusActions, shortBreakMin: Int) {
    var confirmQuit by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            !t.isActive -> {
                Box {
                    var open by remember { mutableStateOf(false) }
                    PillButton("Break", Icons.Rounded.Coffee) { open = true }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        (listOf(shortBreakMin) + listOf(5, 10, 15)).distinct().sorted().forEach { m ->
                            DropdownMenuItem(text = { T("$m min break", 14.sp) }, onClick = { open = false; a.onBreak(m) })
                        }
                    }
                }
                PillButton("Start", Icons.Rounded.PlayArrow, BtnStyle.Primary, onClick = a.onStart)
                PillButton(if (strict) "Strict on" else "Strict off", if (strict) Icons.Rounded.Lock else Icons.Rounded.LockOpen, BtnStyle.Dim) { a.onStrict(!strict) }
            }
            t.isBreak -> {
                PillButton("End break", Icons.Rounded.Stop, onClick = a.onStop)
                if (t.status == Status.RUNNING) PillButton("Pause", Icons.Rounded.Pause, onClick = a.onPause)
                else PillButton("Resume", Icons.Rounded.PlayArrow, onClick = a.onResume)
                PillButton("Start focus", Icons.Rounded.PlayArrow, BtnStyle.Primary, onClick = a.onStartFocusFromBreak)
            }
            else -> {
                val strictFocus = t.strict && t.phase == Phase.FOCUS
                if (strictFocus) PillButton("Quit", Icons.Rounded.Stop) { confirmQuit = true }
                else PillButton("Stop", Icons.Rounded.Stop, onClick = a.onStop)
                if (t.status == Status.RUNNING) PillButton("Pause", Icons.Rounded.Pause, BtnStyle.Primary, enabled = !strictFocus, onClick = a.onPause)
                else PillButton("Resume", Icons.Rounded.PlayArrow, BtnStyle.Primary, onClick = a.onResume)
                if (t.mode == TimerMode.POMODORO) PillButton("Skip", Icons.Rounded.SkipNext, enabled = !strictFocus, onClick = a.onSkip)
            }
        }
    }
    if (confirmQuit) ConfirmDialog(
        title = "Quit this strict block?",
        message = "Strict mode is on. Quitting ends the block now; the time so far is still saved.",
        confirm = "Quit block",
        onConfirm = { confirmQuit = false; a.onForceStop() },
        onDismiss = { confirmQuit = false },
    )
}

/** The other device's session, read-only, behind the lock card or (after "Keep on phone") with a take-over button. */
@Composable
private fun RemoteTimerView(remote: RemoteTimer, now: Long, onTakeOver: () -> Unit, compact: Boolean) {
    val t = remote.state
    val c = ringContent(t, t.mode, 25, DesktopSettings(), now)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Segmented(TimerMode.entries.map { it.label }, TimerMode.entries.indexOf(t.mode), enabled = false) {}
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(380.dp), contentAlignment = Alignment.Center) {
            TimerRing(c.fraction, t.status == Status.PAUSED)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(c.top, 16.sp, FontWeight.Medium, Slate.Mist)
                Spacer(Modifier.height(10.dp))
                T(c.clock, 88.sp, FontWeight.Light, letterSpacing = (-2).sp)
                Spacer(Modifier.height(10.dp))
                T("on your ${remote.deviceLabel}", 14.sp, color = Slate.Mist)
            }
        }
        Spacer(Modifier.height(26.dp))
        SubjectPill(t.subject, editable = false) {}
        Spacer(Modifier.height(26.dp))
        if (compact) PillButton("Take over here", Icons.Rounded.Computer, BtnStyle.Primary, onClick = onTakeOver)
        else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Start", Icons.Rounded.PlayArrow) {}
            PillButton("Pause", Icons.Rounded.Pause, BtnStyle.Primary) {}
            PillButton("Skip", Icons.Rounded.SkipNext) {}
        }
    }
}

fun remoteSessionLine(remote: RemoteTimer, now: Long): String {
    val t = remote.state
    val what = when { t.isBreak -> "Break"; else -> "Session" }
    return when {
        t.countsUp -> "$what: ${formatElapsed(t.elapsedAt(now))} in"
        t.status == Status.PAUSED -> "$what paused: ${formatClock(t.remainingAt(now))} left"
        else -> "$what: ${formatClock(t.remainingAt(now))} left"
    }
}

@Composable
private fun LockedCard(remote: RemoteTimer, now: Long, modifier: Modifier, onTakeOver: () -> Unit, onKeep: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val t = remote.state
    Column(
        modifier.width(500.dp).glass(shape, strong = true).background(Slate.Deep.copy(alpha = 0.55f)).padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(86.dp).clip(CircleShape).background(Slate.Ink.copy(alpha = 0.6f)).border(1.dp, Slate.Fog.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(70.dp).clip(CircleShape).border(1.dp, Slate.Fog.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (remote.onPhone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = Slate.Paper, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        val what = if (t.isBreak) "A break" else "Focus"
        T("$what is running on your ${remote.deviceLabel}", 25.sp, FontWeight.Bold, align = TextAlign.Center, maxLines = 2, lineHeight = 31.sp)
        Spacer(Modifier.height(14.dp))
        Chip(height = 40.dp) { LiveDot(); T(remoteSessionLine(remote, now), 15.sp, FontWeight.SemiBold) }
        Spacer(Modifier.height(16.dp))
        T(listOf(t.mode.label, t.subject).filter { it.isNotBlank() }.joinToString(" · "), 14.sp, color = Slate.Mist, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        T("Only one device can run the timer at a time.", 14.sp, color = Slate.Mist, align = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Take over here", Icons.Rounded.Computer, BtnStyle.Primary, modifier = Modifier.weight(1f), onClick = onTakeOver)
            PillButton("Keep on ${remote.deviceLabel}", if (remote.onPhone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, modifier = Modifier.weight(1f), onClick = onKeep)
        }
    }
}

@Composable
private fun CheckInCard(t: TimerState, now: Long, modifier: Modifier, onHere: () -> Unit) {
    val left = (t.checkInAt + 2 * 60_000L - now).coerceAtLeast(0L)
    Column(
        modifier.width(440.dp).glass(RoundedCornerShape(30.dp), strong = true).background(Slate.Deep.copy(alpha = 0.6f)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("Still focusing?", 25.sp, FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        T("The timer pauses in ${formatClock(left)} if there's no answer, and the unanswered minutes aren't counted.", 14.sp, color = Slate.Mist, maxLines = 3, align = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PillButton("I'm here", Icons.Rounded.PlayArrow, BtnStyle.Primary, onClick = onHere)
    }
}

@Composable
private fun TodayCard(todayMs: Long, goalMs: Long, sessions: Int, streak: Int) {
    SectionCard(Modifier.fillMaxWidth()) {
        T("Today's focus", 12.5.sp, color = Slate.Mist)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            T(formatDuration(todayMs), 34.sp, FontWeight.Bold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.width(8.dp))
            T("of ${formatGoal(goalMs)} goal", 16.sp, FontWeight.Medium, Slate.Mist, modifier = Modifier.padding(bottom = 5.dp))
        }
        Spacer(Modifier.height(16.dp))
        ProgressBar(if (goalMs > 0) todayMs / goalMs.toFloat() else 0f)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("$sessions", "Sessions", Modifier.weight(1f))
            MiniTile(if (todayMs >= goalMs) "Done" else formatDuration(goalMs - todayMs), "Left to goal", Modifier.weight(1f))
            MiniTile("$streak", "Day streak", Modifier.weight(1f))
        }
    }
}

private fun formatGoal(ms: Long): String {
    val min = ms / 60_000
    return if (min % 60 == 0L) "${min / 60}h" else formatDuration(ms)
}

@Composable
private fun UpNextCard(tasks: List<TaskEntity>, current: String, enabled: Boolean, onPick: (TaskEntity) -> Unit, modifier: Modifier) {
    val open = tasks.filter { !it.done }
    SectionCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Up next", 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
            T(if (open.size == 1) "1 task" else "${open.size} tasks", 12.5.sp, color = Slate.Mist)
        }
        Spacer(Modifier.height(6.dp))
        if (open.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            T("No open tasks. Add some in Tasks and pick one here as your subject.", 13.sp, color = Slate.Mist, maxLines = 3)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            open.take(12).forEachIndexed { i, task ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Slate.Fog.copy(alpha = 0.08f)))
                val cur = current.isNotBlank() && current == task.title
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onPick(task) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (cur) Slate.Paper else Slate.Slate, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (cur) Box(Modifier.size(8.dp).clip(CircleShape).background(Slate.Paper)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(task.title, 14.sp, FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        T(listOf(task.category, if (cur) "in progress" else priorityLabel(task.priority)).filter { it.isNotBlank() }.joinToString(" · "), 12.sp, color = Slate.Mist)
                    }
                    task.deadline?.let { d ->
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Slate.Steel.copy(alpha = 0.6f)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                            T(deadlineLabel(d), 12.sp, color = Slate.Fog)
                        }
                    }
                }
            }
        }
    }
}

fun priorityLabel(p: Int) = when (p) { 2 -> "High priority"; 1 -> "Medium"; else -> "Low" }

fun deadlineLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate.Deep,
        title = { T(title, 18.sp, FontWeight.Bold) },
        text = { T(message, 14.sp, color = Slate.Fog, maxLines = 6) },
        confirmButton = { PillButton(confirm, style = BtnStyle.Primary, height = 42.dp, onClick = onConfirm) },
        dismissButton = { PillButton("Cancel", height = 42.dp, onClick = onDismiss) },
    )
}
