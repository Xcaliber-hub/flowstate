package com.flowstate.focus.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Translucent surface with a hairline border and a soft tinted glow (see [Depth]).
 *
 * Real backdrop blur is deliberately not used here: it is expensive on mid-range GPUs and is the
 * most common cause of dropped frames. A translucent layer over the AMOLED background gives the
 * same "frosted" read at almost no cost.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DepthSurface(shape = CardShape, modifier = modifier) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** Corner shape shared by every card. */
val CardShape = RoundedCornerShape(28.dp)

/** Springs content in once. Remembered across scrolling so list items do not replay it. */
@Composable
fun Entrance(index: Int, content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!shown) delay(index * 70L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "entrance",
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress.coerceIn(0f, 1f)
            translationY = (1f - progress) * 56.dp.toPx()
        }
    ) { content() }
}
