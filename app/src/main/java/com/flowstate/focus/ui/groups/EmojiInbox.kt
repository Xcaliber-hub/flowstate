package com.flowstate.focus.ui.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.data.groups.EmojiPing
import com.flowstate.focus.domain.groups.EmojiPings
import com.flowstate.focus.ui.components.FrostedGlassSurface
import kotlin.math.sin

/**
 * Emojis friends sent while you were away. The oldest pops up first over a dimmed screen; "Nice"
 * clears it (it is deleted, so it never shows again) and the next one follows. "Send one back"
 * returns the same emoji to the friend in the group it came from.
 */
@Composable
fun EmojiInbox(modifier: Modifier = Modifier) {
    val vm: GroupsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val ping = state.incomingPings.firstOrNull()
    // Keep the last ping on the card while it fades out.
    var shown by remember { mutableStateOf<EmojiPing?>(null) }
    if (ping != null) shown = ping
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(ping != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    // Swallow taps so the screen behind stays put until the ping is cleared.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                shown?.let { p ->
                    PingCard(
                        ping = p,
                        remaining = (state.incomingPings.size - 1).coerceAtLeast(0),
                        onDismiss = { vm.dismissPing(p) },
                        onReply = { vm.sendEmoji(p.groupId, p.fromUid, p.emoji); vm.dismissPing(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PingCard(ping: EmojiPing, remaining: Int, onDismiss: () -> Unit, onReply: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Each new ping drops in: the card springs up from small, then the emoji lands with a jelly squash.
    val cardScale = remember(ping) { Animatable(0.7f) }
    val squash = remember(ping) { Animatable(0f) }
    LaunchedEffect(ping) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        cardScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f))
    }
    LaunchedEffect(ping) {
        squash.snapTo(1f)
        squash.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 260f))
    }
    val float = rememberInfiniteTransition(label = "float")
    val t by float.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")

    FrostedGlassSurface(
        RoundedCornerShape(32.dp),
        Modifier
            .padding(horizontal = 32.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = cardScale.value; scaleY = cardScale.value },
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    ping.emoji,
                    fontSize = 88.sp,
                    modifier = Modifier.graphicsLayer {
                        val s = squash.value
                        scaleX = 1f + 0.35f * s
                        scaleY = 1f - 0.3f * s
                        translationY = sin(t) * 6.dp.toPx()
                        rotationZ = sin(t * 0.5f) * 6f
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileAvatar(ping.fromName, ping.fromAvatarId, 28)
                Text("${ping.fromName} sent you ${ping.emoji}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            EmojiPings.meaning(ping.emoji).takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Text(
                ping.groupName + if (remaining > 0) " · $remaining more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReply, modifier = Modifier.weight(1f)) { Text("Send one back") }
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(if (remaining > 0) "Next" else "Nice") }
            }
        }
    }
}
