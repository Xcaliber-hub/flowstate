package com.flowstate.focus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one depth recipe every card-like surface in the app shares, so cards read as lifted off the
 * canvas the same way everywhere:
 *  - a soft accent-tinted shadow (primary at 18%; tinted shadows need API 28+, older devices fall
 *    back to the platform's neutral shadow),
 *  - a hairline border (outlineVariant; full strength on dark/AMOLED, where the border carries the
 *    depth because a shadow barely shows on black, and 35% in light mode),
 *  - a slightly translucent surface (96% on dark, 78% in light mode).
 */
object Depth {
    /** Roughly a 12 dp blur with a ~3 dp downward offset, like the Material key light. */
    val Card: Dp = 6.dp
    /** For rows and tiles nested inside a card. */
    val Inner: Dp = 2.dp
    /** The floating navigation bar sits above everything. */
    val Floating: Dp = 12.dp

    const val SHADOW_ALPHA = 0.18f
    const val DARK_SURFACE_ALPHA = 0.96f
    const val LIGHT_SURFACE_ALPHA = 0.78f
    const val LIGHT_BORDER_ALPHA = 0.35f
}

@Immutable
data class DepthColors(val surface: Color, val border: Color, val shadow: Color)

/** True for the dark (true-black) palette. Works for dynamic colour too, unlike comparing to Black. */
@Composable
@ReadOnlyComposable
fun isDarkCanvas(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Depth colours for a surface. [base] defaults to surfaceContainer, the card colour. */
@Composable
@ReadOnlyComposable
fun depthColors(
    base: Color = MaterialTheme.colorScheme.surfaceContainer,
    darkAlpha: Float = Depth.DARK_SURFACE_ALPHA,
    lightAlpha: Float = Depth.LIGHT_SURFACE_ALPHA,
): DepthColors {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkCanvas()
    return DepthColors(
        surface = base.copy(alpha = if (dark) darkAlpha else lightAlpha),
        border = if (dark) cs.outlineVariant else cs.outlineVariant.copy(alpha = Depth.LIGHT_BORDER_ALPHA),
        shadow = cs.primary.copy(alpha = Depth.SHADOW_ALPHA),
    )
}

/**
 * Draws only the tinted shadow. It never clips ([clip] = false), so ripples and Liquid UI scaling of
 * the content are untouched; the surface that follows is responsible for its own shape.
 */
fun Modifier.depthShadow(shape: Shape, color: Color, elevation: Dp = Depth.Card): Modifier =
    shadow(elevation = elevation, shape = shape, clip = false, ambientColor = color, spotColor = color)

/** The depth border as a stroke, for components that take a BorderStroke. */
@Composable
@ReadOnlyComposable
fun depthBorder(colors: DepthColors = depthColors()): BorderStroke = BorderStroke(1.dp, colors.border)

/**
 * A card-like surface with the shared depth. Use it for anything that should read as a card:
 * GlassCard, the floating nav bar, tiles inside sheets. Surface clips content to [shape], so ripples
 * stay inside the rounded corners.
 */
@Composable
fun DepthSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = Depth.Card,
    colors: DepthColors = depthColors(),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier.depthShadow(shape, colors.shadow, elevation),
        shape = shape,
        color = colors.surface,
        // Alpha-adjusted colours cannot be matched by Surface's automatic contentColorFor lookup.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(content = content)
    }
}
