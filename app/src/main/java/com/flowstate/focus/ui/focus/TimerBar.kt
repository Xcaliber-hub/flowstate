package com.flowstate.focus.ui.focus

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import com.flowstate.focus.ui.components.FrostedGlassSurface
import com.flowstate.focus.ui.components.formatClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 1.8.2: feeds the floating timer bar. Reads the same [TimerEngine] singleton the Focus screen and the
 * foreground timer service use, so the bar, the Focus tab and the notification always agree.
 */
@HiltViewModel
class TimerBarViewModel @Inject constructor(private val engine: TimerEngine) : ViewModel() {
    val state: StateFlow<TimerState> = engine.state
    fun pause() = engine.pause()
    fun resume() = engine.resume()
}

/** What the bar's two lines say: the subject (or the phase) on top, details below. */
internal fun timerBarTitle(s: TimerState): String = s.subject.trim().ifEmpty { phaseName(s) }

internal fun timerBarSubtitle(s: TimerState): String {
    val parts = mutableListOf<String>()
    if (s.status == Status.PAUSED) parts += "Paused"
    else if (s.awaitingCheckIn) parts += "Still focusing?"
    if (s.subject.isNotBlank()) parts += phaseName(s)
    if (s.mode == TimerMode.POMODORO && !s.standaloneBreak && s.plan.rounds > 1) {
        val rounds = s.plan.rounds
        // [TimerState.round] counts finished focus blocks: during a break it already includes the one just done.
        val current = if (s.phase == Phase.FOCUS) s.round else (s.round - 1).coerceAtLeast(0)
        parts += "Round ${(current % rounds) + 1} of $rounds"
    }
    if (parts.isEmpty()) parts += if (s.isBreak) "Time left" else s.mode.label
    return parts.joinToString(" · ")
}

private fun phaseName(s: TimerState): String = when (s.phase) {
    Phase.FOCUS -> "Focus"
    Phase.SHORT_BREAK -> if (s.standaloneBreak) "Break" else "Short break"
    Phase.LONG_BREAK -> "Long break"
    Phase.IDLE -> s.mode.label
}

// The producer assigns its receiver value below; Compose lint misses this with Kotlin 2.0.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberTicker(running: Boolean): State<Long> =
    produceState(System.currentTimeMillis(), running) {
        value = System.currentTimeMillis()
        while (running) {
            delay(250)
            value = System.currentTimeMillis()
        }
    }

/**
 * The floating mini-player for a session: progress ring, subject and phase, the live time and a
 * play/pause button. Tapping the body opens the Focus tab. It fills whatever size the dock gives it,
 * so it works both full width above the nav and squeezed beside the collapsed nav circle.
 */
@Composable
fun TimerBar(
    state: TimerState,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.status == Status.RUNNING
    val now = rememberTicker(running)
    // Recompose the clock text only when the visible second changes.
    val seconds by remember(state) {
        derivedStateOf {
            if (state.countsUp) state.elapsedAt(now.value) / 1000 else (state.remainingAt(now.value) + 999) / 1000
        }
    }
    val clock = formatClock(seconds)
    val title = timerBarTitle(state)
    val subtitle = timerBarSubtitle(state)
    // Strict focus cannot be paused (the engine refuses); show the button disabled instead of doing nothing.
    val canToggle = !(running && state.strict && state.phase == Phase.FOCUS)

    FrostedGlassSurface(shape = CircleShape, tinted = true, blurRadius = 16.dp, modifier = modifier.fillMaxSize()) {
        val content = LocalContentColor.current
        Row(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle, $clock. Open Focus" }
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Small progress ring; drawn in the draw phase so it never recomposes the row.
            val ringColor = content
            Canvas(Modifier.size(26.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(ringColor.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                val t = now.value
                val frac = when {
                    state.countsUp -> (state.elapsedAt(t) % 3_600_000L) / 3_600_000f
                    state.phaseDurationMs > 0L -> state.remainingAt(t) / state.phaseDurationMs.toFloat()
                    else -> 0f
                }
                drawArc(ringColor, -90f, 360f * frac.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                clock,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = if (running) content else content.copy(alpha = 0.6f),
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canToggle, onClickLabel = if (running) "Pause" else "Resume") {
                        if (running) onPause() else onResume()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (running) "Pause" else "Resume",
                    tint = if (canToggle) content else content.copy(alpha = 0.35f),
                )
            }
        }
    }
}
