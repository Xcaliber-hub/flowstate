package com.flowstate.focus.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The screen content recorded by [blurSource], for glass surfaces floating above it. */
val LocalBlurSource = staticCompositionLocalOf<GraphicsLayer?> { null }

/** Settings › Glass UI. Off brings back the earlier plain, solid surfaces everywhere. */
val LocalGlassUi = staticCompositionLocalOf { true }

/** Colours that read well on frosted glass in either theme. */
object Glass {
    @Composable fun content(): Color = if (isDarkCanvas()) Color.White else Color(0xFF1B1B1F)
    @Composable fun selectedFill(): Color = if (isDarkCanvas()) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.55f)
    @Composable fun selectedBorder(): Color = if (isDarkCanvas()) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.95f)
    /** The theme colour washed into tinted glass (top bar, nav bar, chat bars, your own bubbles). */
    @Composable fun accentWash(strong: Boolean = false): Color =
        MaterialTheme.colorScheme.primary.copy(alpha = when {
            strong && isDarkCanvas() -> 0.34f
            strong -> 0.26f
            isDarkCanvas() -> 0.20f
            else -> 0.14f
        })
}

/**
 * Glass for surfaces inside the scrolling content (cards, chat bubbles). They cannot blur the
 * layer they are part of, so they fake it: a see-through wash, a soft top sheen and a bright rim.
 * [wash] adds a colour on top, such as [Glass.accentWash].
 */
@Composable
fun Modifier.glassPanel(shape: Shape, wash: Color? = null): Modifier {
    val dark = isDarkCanvas()
    val base = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.26f else 0.95f),
        1f to Color.White.copy(alpha = if (dark) 0.06f else 0.4f),
    )
    return this.clip(shape)
        .drawBehind {
            drawRect(base)
            if (wash != null) drawRect(wash)
            drawRect(Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.09f else 0.3f),
                0.55f to Color.White.copy(alpha = 0f),
            ))
        }
        .border(1.dp, rim, shape)
}

/**
 * Frosted glass, like a media player's floating controls: whatever scrolls behind is blurred,
 * washed with a soft tint, lit from the top and edged with a thin bright rim.
 *
 * Real blur needs Android 12 (API 31) or newer and a [LocalBlurSource]. Without them the surface
 * falls back to an almost-opaque tint so text stays readable.
 */
@Composable
fun FrostedGlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 28.dp,
    /** Wash the glass with the theme colour; used by the top bar, nav bar and chat bars. */
    tinted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!LocalGlassUi.current) {
        // Plain mode: the solid, bordered surface from before Glass UI.
        val cs = MaterialTheme.colorScheme
        Box(
            modifier
                .depthShadow(shape, depthColors().shadow, Depth.Floating)
                .clip(shape)
                .background(if (tinted) cs.secondaryContainer else cs.surfaceContainerHigh)
                .border(1.dp, cs.outlineVariant, shape),
        ) {
            CompositionLocalProvider(LocalContentColor provides if (tinted) cs.onSecondaryContainer else cs.onSurface) { content() }
        }
        return
    }
    val source = LocalBlurSource.current
    val wash = if (tinted) Glass.accentWash() else null
    val canBlur = source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = isDarkCanvas()
    val bg = MaterialTheme.colorScheme.background
    val blurLayer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    val tint = when {
        !canBlur -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
        dark -> Color(0xFF2A2A2E).copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.42f)
    }
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.34f else 0.9f),
        1f to Color.White.copy(alpha = if (dark) 0.08f else 0.35f),
    )
    val shadow = depthColors().shadow

    Box(
        modifier
            .depthShadow(shape, shadow, Depth.Floating)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                if (canBlur && source != null) {
                    // Paint the screen behind the bar, blurred, then move it so it lines up exactly.
                    drawRect(bg)
                    blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                    blurLayer.record(source.size) { drawLayer(source) }
                    translate(-origin.x, -origin.y) { drawLayer(blurLayer) }
                }
                drawRect(tint)
                if (wash != null) drawRect(wash)
                // Soft light from above, like the sheen on real glass.
                drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.10f else 0.25f),
                    0.6f to Color.White.copy(alpha = 0f),
                ))
            }
            .border(1.dp, rim, shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides Glass.content()) { content() }
    }
}
