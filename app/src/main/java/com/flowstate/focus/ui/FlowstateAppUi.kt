package com.flowstate.focus.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Groups
import com.flowstate.focus.ui.groups.GroupsScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flowstate.focus.domain.AppSettings
import androidx.compose.runtime.CompositionLocalProvider
import com.flowstate.focus.ui.components.FloatingNavBar
import com.flowstate.focus.ui.components.LocalNavLayout
import com.flowstate.focus.ui.components.NavLayout
import com.flowstate.focus.ui.dock.SideDock
import com.flowstate.focus.ui.components.NavTab
import com.flowstate.focus.ui.focus.FocusScreen
import com.flowstate.focus.ui.settings.BlockedAppsScreen
import com.flowstate.focus.ui.settings.SettingsScreen
import com.flowstate.focus.ui.stats.StatsScreen
import com.flowstate.focus.ui.tasks.TasksScreen

object Routes {
    const val FOCUS = "focus"
    const val GROUPS = "groups"
    const val TASKS = "tasks"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val BLOCKED = "blocked"
}

private val Tabs = listOf(
    NavTab(Routes.FOCUS, "Focus", Icons.Rounded.Timer),
    NavTab(Routes.TASKS, "Tasks", Icons.Rounded.Checklist),
    NavTab(Routes.STATS, "Statistics", Icons.Rounded.BarChart),
    NavTab(Routes.GROUPS, "Groups", Icons.Rounded.Groups),
    NavTab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun FlowstateAppUi(settings: AppSettings) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    var navVisible by remember { mutableStateOf(true) }

    // Hide the nav bar while scrolling down, bring it back on any upward scroll.
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f) navVisible = false else if (available.y > 8f) navVisible = true
                return Offset.Zero
            }
        }
    }

    val enter = fadeIn(tween(240, delayMillis = 80)) + scaleIn(initialScale = 0.96f, animationSpec = tween(240, delayMillis = 80))
    val exit = fadeOut(tween(90))

    // MaterialTheme supplies color roles, but does not paint a background or set LocalContentColor.
    // A root Surface keeps plain text and controls readable in light, dark and dynamic themes.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        val navLayout = if (settings.sideNav) NavLayout.SIDE else NavLayout.BOTTOM
        CompositionLocalProvider(LocalNavLayout provides navLayout) {
        Box(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
            NavHost(
                navController = nav,
                startDestination = Routes.FOCUS,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { enter },
                exitTransition = { exit },
                popEnterTransition = { enter },
                popExitTransition = { exit },
            ) {
                composable(Routes.FOCUS) { FocusScreen(settings) }
                composable(Routes.GROUPS) { GroupsScreen(settings, onOpenFocus = { nav.navigate(Routes.FOCUS) { launchSingleTop = true } }) }
                composable(Routes.TASKS) { TasksScreen(settings) }
                composable(Routes.STATS) { StatsScreen(settings) }
                composable(Routes.SETTINGS) { SettingsScreen(onOpenBlockedApps = { nav.navigate(Routes.BLOCKED) }) }
                composable(Routes.BLOCKED) { BlockedAppsScreen(onBack = { nav.popBackStack() }) }
            }
            if (Tabs.any { it.route == route }) {
                val onSelect: (NavTab) -> Unit = { tab ->
                    navVisible = true
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                when (navLayout) {
                    NavLayout.BOTTOM -> FloatingNavBar(
                        tabs = Tabs,
                        selectedRoute = route,
                        visible = navVisible,
                        liquid = settings.liquidUi,
                        onSelect = onSelect,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    // Compact rail and daily-goal water bar, stacked on the left edge.
                    NavLayout.SIDE -> SideDock(
                        tabs = Tabs,
                        selectedRoute = route,
                        visible = navVisible,
                        liquid = settings.liquidUi,
                        swapped = settings.sideNavSwapped,
                        onSelect = onSelect,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }
        }
    }
}
