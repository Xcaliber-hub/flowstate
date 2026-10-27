package com.flowstate.focus.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import com.flowstate.focus.ui.components.CardShape
import com.flowstate.focus.ui.components.DepthSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CheckInViewModel @Inject constructor(private val engine: TimerEngine) : ViewModel() {
    val state: StateFlow<TimerState> = engine.state
    fun imHere() = engine.confirmPresence()
    fun resume() = engine.resume()
}

private const val ANSWER_WINDOW_MS = 2 * 60_000L

/**
 * The in-app side of the hourly inactivity check-in. The same prompt also arrives as a notification,
 * so it works with the app closed. Shows "Still focusing?" with a live countdown, and a "Welcome back"
 * card after the timer paused itself.
 */
@Composable
fun CheckInPrompt(vm: CheckInViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.awaitingCheckIn) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(s.checkInAt) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
        val left = ((s.checkInAt + ANSWER_WINDOW_MS - now) / 1000).coerceAtLeast(0)
        PromptCard(
            icon = Icons.Rounded.Visibility,
            title = "Still focusing?",
            body = "You've been in focus for a while. The timer pauses in %d:%02d if you're away.".format(left / 60, left % 60),
            primary = "I'm here" to vm::imHere,
            dismissible = false,
        )
        return
    }

    // Shown once per auto-pause; "Not now" just hides it and the timer stays paused.
    var hiddenFor by remember { mutableStateOf(0L) }
    if (s.status == Status.PAUSED && s.autoPaused && hiddenFor != s.pausedAt) {
        PromptCard(
            icon = Icons.Rounded.Bedtime,
            title = "Welcome back",
            body = "We paused your timer when you didn't answer the check-in. The time you were away wasn't counted.",
            primary = "Resume focus" to vm::resume,
            secondary = "Not now" to { hiddenFor = s.pausedAt },
            dismissible = true,
            onDismiss = { hiddenFor = s.pausedAt },
        )
    }
}

@Composable
private fun PromptCard(
    icon: ImageVector,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    dismissible: Boolean,
    onDismiss: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        DepthSurface(shape = CardShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Box(Modifier.size(52.dp).background(cs.primary.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = cs.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(primary.first) }
                secondary?.let { (label, action) ->
                    TextButton(onClick = action, modifier = Modifier.align(Alignment.End)) { Text(label) }
                }
            }
        }
    }
}
