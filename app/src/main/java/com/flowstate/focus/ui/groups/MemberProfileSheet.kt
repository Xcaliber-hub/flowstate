package com.flowstate.focus.ui.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.flowstate.focus.domain.groups.EmojiPings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.domain.groups.League
import com.flowstate.focus.ui.components.DepthSurface

/**
 * A group member's weekly profile: large avatar, full name (never truncated), then a card with this
 * week's focus, league and rank, number of sessions, and average focus per session. Stats that the
 * member's app version does not share yet read "—".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberProfileSheet(
    member: GroupMember,
    rank: Int,
    onDismiss: () -> Unit,
    canSendEmoji: Boolean = false,
    onSendEmoji: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val league = League.of(member.weeklyFocusMs)
    val sessions = member.weeklySessions
    val average = sessions?.takeIf { it > 0 }?.let { member.weeklyFocusMs / it }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerHigh.copy(alpha = 0.96f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var trayOpen by remember { mutableStateOf(false) }
            var sent by remember { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProfileAvatar(member.name, member.avatarId, 96)
                if (canSendEmoji) {
                    // Left of the avatar: tap to pick an emoji for this friend.
                    EmojiSendButton(open = trayOpen, sent = sent, modifier = Modifier.align(Alignment.CenterStart)) { trayOpen = !trayOpen }
                }
            }
            AnimatedVisibility(
                visible = canSendEmoji && trayOpen,
                enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                EmojiTray(Modifier.padding(top = 14.dp)) { emoji ->
                    onSendEmoji(emoji)
                    sent = emoji
                    trayOpen = false
                }
            }
            sent?.let { emoji ->
                Text(
                    "Sent $emoji. ${member.name.ifBlank { "They" }} will see it when they open Flowstate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                member.name.ifBlank { "Focus member" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            DepthSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatCell(duration(member.weeklyFocusMs), "This week's focus", Modifier.weight(1f))
                        Column(
                            Modifier.weight(1f).padding(vertical = 16.dp).semantics(mergeDescendants = true) {
                                contentDescription = "${league?.label ?: "No"} league, rank $rank"
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LeagueBadge(league)
                            Spacer(Modifier.height(6.dp))
                            Text("Rank $rank", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
                    Row(Modifier.fillMaxWidth()) {
                        StatCell(sessions?.toString() ?: "—", "No. of sessions", Modifier.weight(1f))
                        StatCell(average?.let(::duration) ?: "—", "Avg focus per session", Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.padding(vertical = 16.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A shield in the league's colour, or a locked grey shield below Bronze. */
@Composable
private fun LeagueBadge(league: League?) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Shield,
            contentDescription = null,
            tint = league?.let { Color(it.color) } ?: cs.outline,
            modifier = Modifier.size(32.dp),
        )
        if (league == null) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = cs.surface, modifier = Modifier.size(14.dp))
        }
    }
}

/** Round button on the left of the avatar. Shows a smiley, or the last emoji sent, with a little pop. */
@Composable
private fun EmojiSendButton(open: Boolean, sent: String?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    LaunchedEffect(sent) {
        if (sent != null) { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 420f)) }
    }
    Box(
        modifier
            .size(52.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(CircleShape)
            .background(if (open) cs.primaryContainer else cs.surfaceContainerHighest)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Send an emoji") { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics { contentDescription = "Send an emoji" },
        contentAlignment = Alignment.Center,
    ) {
        if (sent != null && !open) Text(sent, fontSize = 24.sp)
        else Icon(Icons.Rounded.AddReaction, contentDescription = null, tint = if (open) cs.onPrimaryContainer else cs.onSurface)
    }
}

/** The emojis in a row; each one springs up when pressed. */
@Composable
private fun EmojiTray(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EmojiPings.ALL.forEach { emoji ->
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 1.35f else 1f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "emoji")
            Box(
                Modifier
                    .size(38.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationY = if (pressed) -6.dp.toPx() else 0f }
                    .clip(CircleShape)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Send $emoji") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPick(emoji)
                    },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 24.sp) }
        }
    }
}
