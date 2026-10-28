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
import androidx.compose.runtime.staticCompositionLocalOf
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

/** Where the app's navigation sits: the floating bottom bar, or the experimental right-edge rail. */
enum class NavLayout { BOTTOM, SIDE }

val LocalNavLayout = staticCompositionLocalOf { NavLayout.BOTTOM }

/**
 * Edge-to-edge padding: clear of the status bar on top and of the navigation. With the bottom bar the
 * content stops above it; with the side rail the content keeps a lane free on the right instead.
 */
@Composable
fun screenContentPadding(): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return when (LocalNavLayout.current) {
        NavLayout.BOTTOM -> PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 136.dp)
        NavLayout.SIDE -> PaddingValues(
            start = 20.dp, end = 84.dp, top = top + 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp,
        )
    }
}

/** Space a floating action button keeps from the bottom edge so it clears the navigation. */
@Composable
@ReadOnlyComposable
fun fabBottomPadding() = if (LocalNavLayout.current == NavLayout.SIDE) 24.dp else 104.dp
