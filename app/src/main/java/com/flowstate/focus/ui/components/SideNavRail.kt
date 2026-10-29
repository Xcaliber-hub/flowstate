package com.flowstate.focus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Width of the side rail and the goal bar under it; content keeps a lane this wide plus margins. */
val SideDockWidth = 56.dp

/**
 * Compact vertical navigation: a slim pill of icons. The selected tab sits in a soft tinted circle
 * (primary container), the rest are plain icons, as in the reference design. Sliding in and out on
 * scroll is handled by the dock that holds it.
 */
@Composable
fun SideNavRail(
    tabs: List<NavTab>,
    selectedRoute: String?,
    liquid: Boolean,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    DepthSurface(
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier,
        elevation = Depth.Floating,
        colors = depthColors(base = cs.surfaceContainerHigh, darkAlpha = 0.94f, lightAlpha = 0.82f),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.forEach { tab ->
                RailItem(tab, tab.route == selectedRoute, liquid) {
                    if (tab.route != selectedRoute) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(tab)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(tab: NavTab, selected: Boolean, liquid: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, label = "railFill")
    val tint by animateColorAsState(if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, label = "railTint")
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = if (liquid) spring<Float>(0.45f, Spring.StiffnessMediumLow) else spring<Float>(1f, Spring.StiffnessMedium),
        label = "railScale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(fill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
    }
}
