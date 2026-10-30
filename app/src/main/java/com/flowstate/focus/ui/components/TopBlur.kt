package com.flowstate.focus.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Records whatever it wraps into [layer] and draws it as normal, so [TopBlur] can show a blurred
 * copy of the same pixels. Put it on the screen content, never on the nav dock or bar.
 */
fun Modifier.blurSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * A progressive blur along the top edge, like the Hark app: content scrolling up under the
 * status bar softens and fades into the background instead of colliding with the clock and icons.
 * Fully blurred at the very top, clear by the bottom of the band.
 *
 * Real blur needs Android 12 (API 31) or newer. Older phones get only the tinted fade, which still
 * keeps the status bar readable.
 */
@Composable
fun TopBlur(source: GraphicsLayer, modifier: Modifier = Modifier, extra: Dp = 56.dp, radius: Dp = 22.dp) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bg = MaterialTheme.colorScheme.background
    val blurLayer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        modifier
            .fillMaxWidth()
            .height(top + extra)
            // Offscreen so the gradient mask below cuts only this band, not the screen behind it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                if (canBlur) {
                    blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                    blurLayer.record(IntSize(size.width.toInt(), size.height.toInt())) { drawLayer(source) }
                    drawLayer(blurLayer)
                }
                // Tint: mostly background at the top so the status bar reads cleanly, none at the bottom.
                drawRect(Brush.verticalGradient(0f to bg.copy(alpha = if (canBlur) 0.55f else 0.9f), 1f to bg.copy(alpha = 0f)))
                // Mask: keep the band strong at the top and fade it out to nothing at the bottom edge.
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.8f), 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
