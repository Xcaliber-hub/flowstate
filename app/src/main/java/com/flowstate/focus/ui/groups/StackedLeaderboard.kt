package com.flowstate.focus.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowstate.focus.data.groups.GroupMember

/** Deep navy to pale grey, the palette the stacked board fades through from rank 1 down. */
private val StackPalette = listOf(
    Color(0xFF06141B), Color(0xFF11212D), Color(0xFF253745),
    Color(0xFF4A5C6A), Color(0xFF9BA8AB), Color(0xFFCCD0CF),
)

private val PillHeight = 84.dp
/** How much of each pill shows above the next one. */
private val PillStep = 64.dp
private val PillShape = RoundedCornerShape(percent = 50)

/** The shade for [index] of [count], spread evenly across [StackPalette]. */
internal fun stackColor(index: Int, count: Int): Color {
    if (count <= 1) return StackPalette.first()
    val pos = index.toFloat() / (count - 1) * (StackPalette.size - 1)
    val lo = pos.toInt().coerceIn(0, StackPalette.size - 2)
    return lerp(StackPalette[lo], StackPalette[lo + 1], pos - lo)
}

/**
 * The weekly leaderboard as a stack of overlapping capsules: rank 1 at the back in the darkest
 * shade, each lower rank tucked over the one above it and a little lighter. Text flips between
 * light and dark with the pill's shade, and your own row gets an accent ring.
 */
@Composable
internal fun StackedLeaderboard(
    members: List<GroupMember>,
    selfUid: String?,
    onMemberClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            members.forEachIndexed { index, member ->
                StackPill(
                    member = member,
                    rank = index + 1,
                    color = stackColor(index, members.size),
                    self = member.uid == selfUid,
                    onClick = { onMemberClick(member.uid) },
                )
            }
        },
    ) { measurables, constraints ->
        val pillPx = PillHeight.roundToPx()
        val stepPx = PillStep.roundToPx()
        val child = constraints.copy(minHeight = pillPx, maxHeight = pillPx, minWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(child) }
        val height = if (placeables.isEmpty()) 0 else stepPx * (placeables.size - 1) + pillPx
        layout(constraints.maxWidth, height) {
            // Placed in rank order, so each pill draws over (and takes touches above) the one before it.
            placeables.forEachIndexed { i, p -> p.place(0, i * stepPx) }
        }
    }
}

@Composable
private fun StackPill(member: GroupMember, rank: Int, color: Color, self: Boolean, onClick: () -> Unit) {
    val onPill = if (color.luminance() < 0.35f) Color(0xFFF5F7FA) else Color(0xFF121820)
    val muted = lerp(onPill, color, 0.4f)
    val ring = if (self) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Box(
        Modifier
            .fillMaxWidth()
            .height(PillHeight)
            .shadow(elevation = 12.dp, shape = PillShape, clip = false)
            .clip(PillShape)
            .background(color)
            .then(if (ring != null) Modifier.border(ring, PillShape) else Modifier)
            .clickable(onClickLabel = "View ${member.name}'s profile", onClick = onClick),
    ) {
        // Content sits in the top band, the part that stays visible under the next pill.
        Row(
            Modifier.fillMaxWidth().height(PillStep).padding(start = 18.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank",
                modifier = Modifier.width(26.dp).semantics { contentDescription = "Rank $rank" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = muted,
            )
            ProfileAvatar(member.name, member.avatarId, 38, ring = when (rank) { 1 -> 0xFFFFC107; 2 -> 0xFFB0BEC5; 3 -> 0xFFB87333; else -> null })
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    member.name + if (self) " · You" else "",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = onPill,
                )
                Text("Weekly focus", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(duration(member.weeklyFocusMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onPill)
        }
    }
}
