package com.flowstate.focus.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flowstate.focus.data.sync.RemoteTimer
import com.flowstate.focus.domain.Status
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.formatClock
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 1.6.0: shown instead of the timer while another device of this account runs it. The timer runs on
 * one device at a time; "Take over here" moves the session (phase, time left, subject) to this phone
 * and the other device stops. "Keep on PC" folds the card down to a single line.
 */
@Composable
fun RemoteTimerCard(remote: RemoteTimer, liquid: Boolean, onTakeOver: () -> Unit) {
    // Ticks once a second while shown (a plain effect: lint misreads produceState with a loop).
    var now by remember(remote) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(remote) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    var kept by rememberSaveable(remote.deviceId, remote.state.phaseStartedAt) { mutableStateOf(false) }
    val t = remote.state
    val clock = if (t.countsUp) formatClock(t.elapsedAt(now) / 1_000) else formatClock(t.remainingAt(now) / 1_000)
    val what = if (t.isBreak) "Break" else "Focus"
    val status = when {
        t.status == Status.PAUSED -> "Paused · $clock left"
        t.countsUp -> "$clock so far"
        else -> "$clock left"
    }
    val deviceIcon = if (remote.onPc) Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid
    GlassCard(Modifier.fillMaxWidth(), liquid = liquid) {
        if (kept) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(deviceIcon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Running on your ${remote.deviceLabel}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = onTakeOver) { AutoSizeText("Take over") }
            }
            return@GlassCard
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(deviceIcon, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(
                "$what is running on your ${remote.deviceLabel}",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Text(clock, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light, maxLines = 1)
            Text(
                listOf(t.mode.label, t.subject).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (t.status == Status.PAUSED) " · Paused" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Text(
                "Only one device can run the timer at a time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onTakeOver, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                AutoSizeText("Take over here")
            }
            OutlinedButton(onClick = { kept = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Icon(deviceIcon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                AutoSizeText("Keep on ${remote.deviceLabel}")
            }
        }
    }
}

/** A dismissable one-line message, e.g. after the PC took the session over. */
@Composable
fun FocusNoticeCard(text: String, liquid: Boolean, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), liquid = liquid) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { AutoSizeText("OK") }
        }
    }
}
