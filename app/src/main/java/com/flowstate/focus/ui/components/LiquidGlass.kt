package com.flowstate.focus.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.flowstate.focus.domain.GlassStyle
import kotlin.math.ceil

/*
 * Liquid glass (Settings › Appearance › Glass UI › Liquid glass UI), after iOS 26: the floating bars are
 * clear glass rather than milky frost. What scrolls behind stays visible and only lightly blurred, it
 * bends (magnifies) toward the curved rim like a thick lens, the rim splits light into a faint rainbow,
 * and a bright specular edge catches the light on the top-left and bottom-right.
 *
 * How it is drawn
 * - Android 13+ (API 33): the bar re-draws the same recorded screen content the frosted glass uses
 *   ([LocalBlurSource]), cropped to the bar plus a small margin, into one GraphicsLayer whose RenderEffect
 *   is a light blur chained into an AGSL RuntimeShader ([LIQUID_GLASS_AGSL]). The shader does the rim
 *   refraction from a rounded-rect SDF, chromatic aberration, no colour or tone change (clear since 1.9.0), the
 *   specular edge and, for the nav bar, a magnifying lens bubble under the finger while dragging.
 *   The RuntimeShader is compiled once per bar; its RenderEffect is rebuilt only when a uniform changes
 *   (bar size, theme, lens position while dragging), not while content merely scrolls behind it.
 * - Android 12 / 12L (API 31–32): "clear glass" approximation: a very small blur, no tint and a stronger
 *   rim and highlights. No refraction.
 * - Older phones, or no recorded backdrop: the frosted fallback ([FrostedGlassSurface]).
 */

/** True when the floating bars should be liquid glass (Glass UI on, style Liquid). */
@Composable
@ReadOnlyComposable
fun liquidGlassActive(): Boolean = LocalGlassUi.current && LocalGlassStyle.current == GlassStyle.LIQUID

/** True when liquid glass is picked and this phone can draw it (Android 12+ with a recorded backdrop). */
@Composable
@ReadOnlyComposable
fun liquidGlassDrawn(): Boolean = liquidGlassActive() && LocalBlurSource.current != null &&
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * A lens the glass bends on top of itself: the nav bar's tab highlight. [bounds] is in the glass
 * surface's own pixels and [strength] runs from 0 (resting droplet, no lens) to 1 (full magnifying
 * bubble). Both are read in the draw phase only, so moving the lens never recomposes anything.
 */
@Stable
class LiquidLens {
    internal var bounds: () -> Rect = { Rect.Zero }
    internal var strength: () -> Float = { 0f }
    /** -1..1: how fast the lens is sliding (sign = direction). The pop-out bubble stretches with it like a drop of liquid. */
    internal var stretch: () -> Float = { 0f }
}

/**
 * The surface for the floating bars (bottom nav, timer bar, top bar): liquid glass when the user picked
 * it and the phone can draw it, otherwise exactly the [FrostedGlassSurface] from before.
 * [cornerRadius] null means a capsule (fully round ends), which is what every bar uses today.
 */
@Composable
fun GlassBarSurface(
    modifier: Modifier = Modifier,
    tinted: Boolean = true,
    blurRadius: Dp = 28.dp,
    cornerRadius: Dp? = null,
    lens: LiquidLens? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape: Shape = if (cornerRadius == null) CircleShape else RoundedCornerShape(cornerRadius)
    if (liquidGlassDrawn()) LiquidGlassSurface(shape, cornerRadius, modifier, tinted, lens, content)
    else FrostedGlassSurface(shape, modifier, blurRadius, tinted, content)
}

/** Fill, rim and highlight colours for the liquid look, tuned per theme so text stays readable. */
internal object LiquidColors {
    /** The resting tab "droplet" inside the nav bar. */
    @Composable fun dropletFill(): Color = if (isDarkCanvas()) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.10f) // clear, the rim marks it

    /** Rim light around the edge: brightest top-left, a second glint bottom-right, dim elsewhere. */
    fun rimBrush(dark: Boolean, center: Offset, boost: Float = 1f): Brush {
        val k = if (dark) 0.85f * boost else boost
        fun a(v: Float) = Color.White.copy(alpha = (v * k).coerceIn(0f, 1f))
        // Sweep angles: 0 = right, 0.25 = bottom, 0.5 = left, 0.75 = top (clockwise, y points down).
        return Brush.sweepGradient(
            0f to a(0.30f),
            0.125f to a(0.80f), // bottom-right glint
            0.27f to a(0.16f),
            0.5f to a(0.40f),
            0.625f to a(0.98f), // top-left highlight
            0.76f to a(0.50f),
            0.9f to a(0.18f),
            1f to a(0.30f),
            center = center,
        )
    }
}

@Composable
private fun LiquidGlassSurface(
    shape: Shape,
    cornerRadius: Dp?,
    modifier: Modifier,
    tinted: Boolean,
    lens: LiquidLens?,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = LocalBlurSource.current
    val dark = isDarkCanvas()
    val bg = MaterialTheme.colorScheme.background
    // 1.9.0: liquid glass is truly clear. No theme-colour wash (even when [tinted]), no scrim and no tone
    // shift; only refraction, the sheen and the rim say it is glass. Frosted glass keeps its tint.
    val refracts = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // Compiled once per bar; only its uniforms change afterwards.
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LiquidShader() else null }
    val backdrop = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val dims = remember(density, refracts) {
        with(density) {
            LiquidDims(
                margin = 12.dp.toPx(),
                blur = (if (refracts) 0.6.dp else 1.5.dp).toPx(), // just enough to smooth refraction steps
                refraction = 7.dp.toPx(),
                bevel = 8.dp.toPx(),
                aberration = 2.2.dp.toPx(),
                lensGrow = 3.dp.toPx(),
                rim = (if (refracts) 0.9.dp else 1.4.dp).toPx(),
                glow = 2.5.dp.toPx(),
                edge = 1.dp.toPx(),
            )
        }
    }
    val cornerPx = cornerRadius?.let { with(density) { it.toPx() } }
    val rimBoost = if (refracts) 1f else 1.25f
    val shadow = depthColors().shadow

    Box(
        modifier
            .depthShadow(shape, shadow, Depth.Floating)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val mid = Offset(size.width / 2f, size.height / 2f)
                val rimBrush = LiquidColors.rimBrush(dark, mid, rimBoost)
                val glowBrush = LiquidColors.rimBrush(dark, mid, 0.35f * rimBoost)
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.08f else 0.18f),
                    0.45f to Color.White.copy(alpha = 0f),
                )
                val edgeShade = Color.Black.copy(alpha = if (dark) 0.18f else 0.07f)
                val w = size.width
                val h = size.height
                val radius = (cornerPx ?: (minOf(w, h) / 2f)).coerceAtMost(minOf(w, h) / 2f)
                val layerSize = IntSize(ceil(w + dims.margin * 2f).toInt(), ceil(h + dims.margin * 2f).toInt())
                val plainBlur: RenderEffect = BlurEffect(dims.blur, dims.blur, TileMode.Clamp)
                onDrawWithContent {
                    if (source != null && w >= 1f && h >= 1f) {
                        val m = dims.margin
                        var effect: RenderEffect = plainBlur
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                            val lensRect = lens?.bounds?.invoke() ?: Rect.Zero
                            val s = (lens?.strength?.invoke() ?: 0f).coerceIn(0f, 1.2f)
                            val g = dims.lensGrow * s
                            effect = shader.effect(
                                glassLeft = m, glassTop = m, glassWidth = w, glassHeight = h,
                                cornerRadius = radius,
                                refraction = dims.refraction, bevel = dims.bevel, aberration = dims.aberration,
                                lensLeft = lensRect.left + m - g, lensTop = lensRect.top + m - g,
                                lensRight = lensRect.right + m + g, lensBottom = lensRect.bottom + m + g,
                                lensStrength = if (lensRect.width > 1f) s else 0f,
                                dark = dark, blurRadius = dims.blur,
                            )
                        }
                        backdrop.renderEffect = effect
                        // The screen behind the bar (plus a margin, so the blur has real pixels at the edges).
                        backdrop.record(layerSize) {
                            drawRect(bg)
                            translate(m - origin.x, m - origin.y) { drawLayer(source) }
                        }
                        translate(-m, -m) { drawLayer(backdrop) }
                    }
                    drawRect(sheen)
                    drawContent()
                    // A faint shade just inside the edge gives the rim depth (most visible in light mode),
                    // then the soft inner glow and the bright specular rim on top.
                    drawOutline(outline, edgeShade, style = Stroke(dims.edge))
                    drawOutline(outline, glowBrush, style = Stroke(dims.glow))
                    drawOutline(outline, rimBrush, style = Stroke(dims.rim))
                }
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides Glass.content()) { content() }
    }
}

private class LiquidDims(
    val margin: Float,
    val blur: Float,
    val refraction: Float,
    val bevel: Float,
    val aberration: Float,
    val lensGrow: Float,
    val rim: Float,
    val glow: Float,
    val edge: Float,
)

/**
 * The compiled liquid-glass shader for one bar. [effect] returns the blur → shader chain, and only
 * builds a new RenderEffect when a uniform actually changed (a RenderEffect snapshots its shader's
 * uniforms when it is created).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LiquidShader {
    private val shader = RuntimeShader(LIQUID_GLASS_AGSL)
    private val last = FloatArray(PARAMS) { Float.NaN }
    private val next = FloatArray(PARAMS)
    private var cached: RenderEffect? = null

    fun effect(
        glassLeft: Float, glassTop: Float, glassWidth: Float, glassHeight: Float,
        cornerRadius: Float, refraction: Float, bevel: Float, aberration: Float,
        lensLeft: Float, lensTop: Float, lensRight: Float, lensBottom: Float, lensStrength: Float,
        dark: Boolean, blurRadius: Float,
    ): RenderEffect {
        next[0] = glassLeft; next[1] = glassTop; next[2] = glassWidth; next[3] = glassHeight
        next[4] = cornerRadius; next[5] = refraction; next[6] = bevel; next[7] = aberration
        next[8] = lensLeft; next[9] = lensTop; next[10] = lensRight; next[11] = lensBottom
        next[12] = lensStrength; next[13] = if (dark) 1f else 0f; next[14] = blurRadius
        cached?.let { if (next.contentEquals(last)) return it }
        shader.setFloatUniform("glassOrigin", glassLeft, glassTop)
        shader.setFloatUniform("glassSize", glassWidth, glassHeight)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("refraction", refraction)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("aberration", aberration)
        shader.setFloatUniform("lensRect", lensLeft, lensTop, lensRight, lensBottom)
        shader.setFloatUniform("lensStrength", lensStrength)
        shader.setFloatUniform("darkMode", if (dark) 1f else 0f)
        val blur = android.graphics.RenderEffect.createBlurEffect(
            blurRadius.coerceAtLeast(0.5f), blurRadius.coerceAtLeast(0.5f), android.graphics.Shader.TileMode.CLAMP,
        )
        val glass = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        // Chain: the inner effect (blur) runs first, then the glass shader reads the blurred pixels.
        val effect = android.graphics.RenderEffect.createChainEffect(glass, blur).asComposeRenderEffect()
        next.copyInto(last)
        cached = effect
        return effect
    }

    private companion object {
        const val PARAMS = 15
    }
}

/**
 * AGSL for the liquid glass. Coordinates are the backdrop layer's pixels: the glass rectangle sits at
 * [glassOrigin] with [glassSize]; outside it the content passes through untouched (and is clipped anyway).
 */
internal const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 glassOrigin;
uniform float2 glassSize;
uniform float cornerRadius;
uniform float refraction;
uniform float bevel;
uniform float aberration;
uniform float4 lensRect;
uniform float lensStrength;
uniform float darkMode;

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float2 sdNormal(float2 p, float2 halfSize, float r) {
    float e = 0.75;
    float dx = sdRoundRect(p + float2(e, 0.0), halfSize, r) - sdRoundRect(p - float2(e, 0.0), halfSize, r);
    float dy = sdRoundRect(p + float2(0.0, e), halfSize, r) - sdRoundRect(p - float2(0.0, e), halfSize, r);
    float2 g = float2(dx, dy);
    float len = length(g);
    if (len < 0.0001) {
        return float2(0.0, 0.0);
    }
    return g / len;
}

float3 fetch(float2 c, float2 dir, float spread) {
    if (spread < 0.05) {
        return float3(content.eval(c).rgb);
    }
    float r = float(content.eval(c + dir * spread).r);
    float g = float(content.eval(c).g);
    float b = float(content.eval(c - dir * spread).b);
    return float3(r, g, b);
}

half4 main(float2 coord) {
    float2 halfSize = glassSize * 0.5;
    float2 center = glassOrigin + halfSize;
    float2 p = coord - center;
    float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
    float d = sdRoundRect(p, halfSize, radius);
    if (d > 1.0) {
        return content.eval(coord);
    }
    float2 n = sdNormal(p, halfSize, radius);

    // 0 deep inside, 1 at the rim, along a quarter-circle profile like the rounded edge of a thick lens.
    float t = clamp(1.0 + d / bevel, 0.0, 1.0);
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    // Pull samples inward so the rim magnifies what lies under it.
    float2 samplePos = coord - n * bend * refraction;
    float spread = aberration * bend;
    float2 dir = n;
    float lensMix = 0.0;

    // The drag bubble: a smaller lens that magnifies everything under it and fringes at its edge.
    float s = clamp(lensStrength, 0.0, 1.2);
    if (s > 0.001) {
        float2 lHalf = (lensRect.zw - lensRect.xy) * 0.5;
        float2 lCenter = lensRect.xy + lHalf;
        float2 lp = coord - lCenter;
        float lRadius = max(min(lHalf.x, lHalf.y), 1.0);
        float ld = sdRoundRect(lp, lHalf, lRadius);
        if (ld < 0.0) {
            float mag = 1.0 + 0.30 * s;
            float lt = clamp(1.0 + ld / (lRadius * 0.85), 0.0, 1.0);
            float lbend = 1.0 - sqrt(max(1.0 - lt * lt, 0.0));
            float2 ln = sdNormal(lp, lHalf, lRadius);
            samplePos = lCenter + lp / mag - ln * lbend * refraction * 1.5 * s;
            spread = max(spread, aberration * 2.4 * s * lbend);
            dir = ln;
            lensMix = s * smoothstep(0.0, 2.0, -ld);
        }
    }

    float3 col = fetch(samplePos, dir, spread);

    // True transparent (1.9.0): the colours pass through unchanged, with no saturation or tone shift.
    // darkMode stays declared and referenced so setFloatUniform("darkMode") keeps finding it.
    col += float3(0.0 * darkMode + 0.0 * lensMix);

    // Specular edge: light from the top-left, with a weaker glint on the opposite (bottom-right) edge.
    float2 lightDir = normalize(float2(-0.6, -0.8));
    float facing = dot(n, lightDir);
    float edge = clamp(1.0 + d / (bevel * 0.14), 0.0, 1.0);
    edge = edge * edge;
    float spec = edge * (0.50 * pow(max(facing, 0.0), 3.0) + 0.28 * pow(max(-facing, 0.0), 3.0));
    col += float3(spec, spec, spec);

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The nav bar's pop-out lens (Android 13+). Unlike [LiquidShader], which only bends the screen *behind* a bar,
 * this one bends a recording of the screen *and* the bar with its icons, so the bubble can swell past the bar's
 * rim and still magnify and warp everything under it, like the reference's floating glass lens.
 * Coordinates are the bubble layer's own pixels; the bubble is a stadium centred at [center].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BubbleShader {
    private val shader = RuntimeShader(LIQUID_BUBBLE_AGSL)
    private val last = FloatArray(PARAMS) { Float.NaN }
    private val next = FloatArray(PARAMS)
    private var cached: RenderEffect? = null

    fun effect(
        centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float,
        refraction: Float, aberration: Float, magnify: Float, strength: Float, dark: Boolean,
    ): RenderEffect {
        next[0] = centerX; next[1] = centerY; next[2] = halfWidth; next[3] = halfHeight
        next[4] = refraction; next[5] = aberration; next[6] = magnify; next[7] = strength
        next[8] = if (dark) 1f else 0f
        cached?.let { if (next.contentEquals(last)) return it }
        shader.setFloatUniform("center", centerX, centerY)
        shader.setFloatUniform("halfSize", halfWidth, halfHeight)
        shader.setFloatUniform("radius", minOf(halfWidth, halfHeight))
        shader.setFloatUniform("refraction", refraction)
        shader.setFloatUniform("aberration", aberration)
        shader.setFloatUniform("magnify", magnify)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("darkMode", if (dark) 1f else 0f)
        val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        next.copyInto(last)
        cached = effect
        return effect
    }

    private companion object {
        const val PARAMS = 9
    }
}

/**
 * AGSL for the pop-out bubble: a thick, clear stadium lens. The middle magnifies a little; towards the rim the
 * glass curves away so everything under it is pulled inward and bent hard (icons warp as the rim passes over
 * them), the rim splits light into a thin rainbow, and a bright specular edge catches the light top-left with a
 * weaker glint bottom-right. Outside the stadium it is fully transparent (anti-aliased edge).
 */
internal const val LIQUID_BUBBLE_AGSL = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float refraction;
uniform float aberration;
uniform float magnify;
uniform float strength;
uniform float darkMode;

float sdRoundRect(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float2 sdNormal(float2 p, float2 hs, float r) {
    float e = 0.75;
    float dx = sdRoundRect(p + float2(e, 0.0), hs, r) - sdRoundRect(p - float2(e, 0.0), hs, r);
    float dy = sdRoundRect(p + float2(0.0, e), hs, r) - sdRoundRect(p - float2(0.0, e), hs, r);
    float2 g = float2(dx, dy);
    float len = length(g);
    if (len < 0.0001) {
        return float2(0.0, 0.0);
    }
    return g / len;
}

half4 main(float2 coord) {
    float2 p = coord - center;
    float d = sdRoundRect(p, halfSize, radius);
    if (d > 1.0) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }
    float aa = clamp(0.5 - d, 0.0, 1.0);
    float2 n = sdNormal(p, halfSize, radius);

    // 0 in the flat middle, 1 at the rim, on a quarter-circle profile: the edge of a thick lens.
    float bevel = max(min(halfSize.x, halfSize.y) * 0.62, 1.0);
    float t = clamp(1.0 + d / bevel, 0.0, 1.0);
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));

    // Magnify around the centre, then pull samples inward toward the rim.
    float2 samplePos = center + p / magnify - n * bend * refraction;
    float spread = aberration * (0.15 + bend);
    float3 col;
    if (spread < 0.05) {
        col = float3(content.eval(samplePos).rgb);
    } else {
        col = float3(
            float(content.eval(samplePos + n * spread).r),
            float(content.eval(samplePos).g),
            float(content.eval(samplePos - n * spread).b)
        );
    }

    // True transparent (1.9.0): what lies beneath passes through unchanged.
    col += float3(0.0 * darkMode); // keeps the darkMode uniform referenced

    // Specular rim: strong top-left, softer glint bottom-right.
    float2 lightDir = normalize(float2(-0.55, -0.83));
    float facing = dot(n, lightDir);
    float rim = clamp(1.0 + d / max(bevel * 0.20, 1.0), 0.0, 1.0);
    rim = rim * rim;
    float spec = rim * (0.55 * pow(max(facing, 0.0), 2.5) + 0.32 * pow(max(-facing, 0.0), 2.5));
    col += float3(spec, spec, spec);

    // A thin prismatic band right at the edge.
    float ang = atan(n.y, n.x);
    float3 prism = 0.5 + 0.5 * cos(float3(ang * 2.0, ang * 2.0 + 2.094, ang * 2.0 + 4.188));
    col += prism * rim * 0.12 * clamp(strength, 0.0, 1.0);

    // A soft shade just inside the lower edge gives the drop some thickness.
    float lower = clamp(n.y, 0.0, 1.0) * bend;
    col *= 1.0 - 0.10 * lower;

    col = clamp(col, 0.0, 1.0);
    return half4(half3(col) * half(aa), half(aa));
}
"""
