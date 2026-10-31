package com.flowstate.focus.ui.dock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowstate.focus.ui.components.NavTab
import com.flowstate.focus.ui.components.SideDockWidth
import com.flowstate.focus.ui.components.SideNavRail

/** Rail height: 5 items of 40 dp, 4 gaps of 4 dp, 8 dp padding top and bottom. */
private val RailHeight = 232.dp
private val DockGap = 14.dp

/**
 * The left-edge column used with side navigation: the compact nav rail and the daily-goal water bar.
 * It stays fixed in place (it never slides away on scroll) and the pair is centred vertically between
 * the status bar and the gesture bar, so the space above and below it matches. [swapped] puts the
 * water bar on top and the rail under it.
 */
@Composable
fun SideDock(
    tabs: List<NavTab>,
    selectedRoute: String?,
    liquid: Boolean,
    swapped: Boolean,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 8.dp, top = top + 16.dp, bottom = bottom + 16.dp)
            .width(SideDockWidth),
    ) {
        // The water bar (with its time label) takes what is left, between 150 and 300 dp.
        val waterHeight = (maxHeight - RailHeight - DockGap).coerceIn(150.dp, 300.dp)
        Column(
            Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(DockGap, Alignment.CenterVertically),
        ) {
            val rail: @Composable () -> Unit = { SideNavRail(tabs, selectedRoute, liquid, onSelect) }
            val water: @Composable () -> Unit = { WaterGoalBar(liquid = liquid, modifier = Modifier.height(waterHeight)) }
            if (swapped) { water(); rail() } else { rail(); water() }
        }
    }
}
