package com.flowstate.focus.ui.dock

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowstate.focus.ui.components.NavTab
import com.flowstate.focus.ui.components.SideDockWidth
import com.flowstate.focus.ui.components.SideNavRail

/**
 * The left-edge column used with side navigation: the compact nav rail and the daily-goal water bar,
 * stacked. [swapped] puts the water bar on top and the rail under it. Scrolling down slides the whole
 * column off to the left; scrolling up brings it back.
 */
@Composable
fun SideDock(
    tabs: List<NavTab>,
    selectedRoute: String?,
    visible: Boolean,
    liquid: Boolean,
    swapped: Boolean,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetX by animateDpAsState(
        targetValue = if (visible) 0.dp else -(SideDockWidth + 24.dp),
        animationSpec = spring(
            dampingRatio = if (liquid) Spring.DampingRatioMediumBouncy else Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dockOffset",
    )
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .offset(x = offsetX)
            .padding(start = 10.dp, top = top + 16.dp, bottom = bottom + 24.dp)
            .width(SideDockWidth),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val rail: @Composable ColumnScope.() -> Unit = { SideNavRail(tabs, selectedRoute, liquid, onSelect) }
        // Takes the height left under (or over) the rail, up to a comfortable maximum.
        val water: @Composable ColumnScope.() -> Unit = {
            WaterGoalBar(liquid = liquid, modifier = Modifier.weight(1f, fill = false).heightIn(min = 140.dp, max = 320.dp))
        }
        if (swapped) { water(); rail() } else { rail(); water() }
    }
}
