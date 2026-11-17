package com.flowstate.focus.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Centers content and caps its width so tablets, foldables and landscape stay readable. */
@Composable
fun ScreenContainer(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
    }
}

/** Gap between the status bar and the floating frosted top bar. */
val TopBarTopGap = 8.dp

/** Where the app's navigation sits: the floating bottom bar, or the experimental left-edge dock. */
enum class NavLayout { BOTTOM, SIDE }

val LocalNavLayout = staticCompositionLocalOf { NavLayout.BOTTOM }

/** 1.8.2: height of the floating timer bar above the bottom nav, and the gap between the two. */
val TimerBarHeight = 56.dp
val TimerBarGap = 8.dp

/**
 * 1.8.2: extra room the bottom chrome needs on this screen beyond the nav pill: the floating timer
 * bar's height plus its gap while a session runs (0 dp otherwise, and always 0 with the side dock).
 */
val LocalBottomBarExtra = compositionLocalOf { 0.dp }

/**
 * Edge-to-edge padding: clear of the status bar on top and of the navigation. With the bottom bar the
 * content stops above it; with the side dock the content keeps a lane free on the left instead. While the floating timer bar shows (1.8.2),
 * the bottom padding grows by [LocalBottomBarExtra] so the end of a list is never hidden behind it.
 */
@Composable
fun screenContentPadding(): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return when (LocalNavLayout.current) {
        NavLayout.BOTTOM -> PaddingValues(start = 20.dp, end = 20.dp, top = top + TopBarTopGap + TopBarHeight + 16.dp, bottom = 136.dp + LocalBottomBarExtra.current)
        NavLayout.SIDE -> PaddingValues(
            start = 8.dp + SideDockWidth + 10.dp, end = 20.dp, top = top + TopBarTopGap + TopBarHeight + 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp,
        )
    }
}

/** Space a floating action button keeps from the bottom edge so it clears the navigation. */
@Composable
@ReadOnlyComposable
fun fabBottomPadding(): Dp = if (LocalNavLayout.current == NavLayout.SIDE) 24.dp else 104.dp + LocalBottomBarExtra.current
