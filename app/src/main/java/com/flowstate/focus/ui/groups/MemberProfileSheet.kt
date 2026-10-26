package com.flowstate.focus.ui.groups

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
internal fun MemberProfileSheet(member: GroupMember, rank: Int, onDismiss: () -> Unit) {
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
            ProfileAvatar(member.name, member.avatarId, 96)
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
