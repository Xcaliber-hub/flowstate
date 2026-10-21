package com.flowstate.focus.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Translucent surface with a hairline border and a soft tinted glow.
 *
 * Real backdrop blur is deliberately not used here: it is expensive on mid-range GPUs and is the
 * most common cause of dropped frames. A translucent layer over the AMOLED background gives the
 * same "frosted" read at almost no cost.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    liquid: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier
            .breathing(liquid)
            .shadow(6.dp, shape, ambientColor = cs.primary.copy(alpha = 0.18f), spotColor = cs.primary.copy(alpha = 0.18f)),
        shape = shape,
        color = cs.surfaceContainer.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** Liquid UI: a barely visible scale pulse. The scale is read in the draw phase, so nothing recomposes. */
private fun Modifier.breathing(enabled: Boolean): Modifier =
    if (!enabled) this else composed {
        val transition = rememberInfiniteTransition(label = "breathe")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.012f,
            animationSpec = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breatheScale",
        )
        graphicsLayer { scaleX = scale; scaleY = scale }
    }

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
