package com.flowstate.focus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/**
 * Capsule navigation that floats over the content. Only the selected tab shows its label, and the
 * pill resizes with a spring (bouncier when Liquid UI is on). [visible] slides it off screen so the
 * parent can hide it while scrolling down.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selectedRoute: String?,
    visible: Boolean,
    liquid: Boolean,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 140.dp,
        animationSpec = spring(
            dampingRatio = if (liquid) Spring.DampingRatioMediumBouncy else Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navOffset",
    )
    Surface(
        modifier = modifier.widthIn(max = 600.dp).navigationBarsPadding().padding(bottom = 16.dp).offset(y = offsetY),
        shape = CircleShape,
        // Keep the floating bar visually distinct over content in both themes.
        color = cs.surfaceContainerHigh.copy(alpha = if (cs.background == Color.Black) 0.98f else 0.82f),
        shadowElevation = 14.dp,
        border = BorderStroke(
            1.dp,
            if (cs.background == Color.Black) cs.outlineVariant else cs.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { tab ->
                NavItem(tab, tab.route == selectedRoute, liquid) {
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
private fun NavItem(tab: NavTab, selected: Boolean, liquid: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, label = "navBg")
    val tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
    val sizeSpec = if (liquid) spring<IntSize>(0.55f, Spring.StiffnessMediumLow) else spring<IntSize>(1f, Spring.StiffnessMedium)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .animateContentSize(sizeSpec)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = if (selected) 12.dp else 9.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}
